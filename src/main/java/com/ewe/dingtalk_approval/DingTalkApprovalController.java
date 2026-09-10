package com.ewe.dingtalk_approval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 审批中心对外 REST 接口。
 *
 * <p>接口分四类：</p>
 * <ul>
 *   <li>身份：登录态查询、部门查询；</li>
 *   <li>发起：测试发起审批单、查询可选部门与可见模板；</li>
 *   <li>审批人：我的待办、审批详情、同意 / 拒绝、补充意见；</li>
 *   <li>管理：审批概览统计、导入、同步。</li>
 * </ul>
 *
 * <p>所有写操作都要求请求头 {@code X-CSRF-Token} 与 Session 中的令牌一致，
 * 发起人身份一律取自登录 Session，不接受前端传入。</p>
 */
@RestController
public class DingTalkApprovalController {

    private static final Logger log = LoggerFactory.getLogger(DingTalkApprovalController.class);

    private final DingTalkApprovalService approvalService;
    private final ApprovalRecordService recordService;
    private final ApprovalDecisionService decisionService;
    private final ApprovalTodoService todoService;
    private final ApprovalAuthorityService authority;
    private final ApprovalNotificationService notifications;

    public DingTalkApprovalController(DingTalkApprovalService approvalService, ApprovalRecordService recordService,
            ApprovalDecisionService decisionService, ApprovalTodoService todoService,
            ApprovalAuthorityService authority, ApprovalNotificationService notifications) {
        this.approvalService = approvalService;
        this.recordService = recordService;
        this.decisionService = decisionService;
        this.todoService = todoService;
        this.authority = authority;
        this.notifications = notifications;
    }

    // ------------------------------------------------------------------
    // 发起审批
    // ------------------------------------------------------------------

    /** GET 只做页面跳转，不创建审批单。 */
    @GetMapping("/api/dingtalk/approval/test")
    public ResponseEntity<Void> testPage() {
        return ResponseEntity.status(302).header("Location", "/approval-test.html").build();
    }

    /**
     * 发起页所需的初始化数据：当前用户、部门列表、可见模板与 CSRF 令牌。
     */
    @GetMapping("/api/dingtalk/approval/options")
    public Map<String, Object> options(HttpSession session) {
        String userId = requireUser(session);
        String nick = sessionText(session, "dingtalk_nick");
        List<Map<String, Object>> departments = approvalService.getDepartmentIds(userId).stream()
                .distinct().map(id -> {
                    Map<String, Object> detail = approvalService.getDepartmentDetail(id);
                    return Map.<String, Object>of("deptId", id, "name", detail.getOrDefault("name", "部门 " + id));
                }).toList();
        return payload("success", true, "userId", userId, "departments", departments,
                "templates", approvalService.getVisibleProcessTemplates(userId), "csrfToken", csrfToken(session),
                "canViewStatistics", authority.canViewStatistics(userId, nick),
                "canHandleTasks", authority.isApprover(userId),
                "role", authority.roleOf(userId, nick).name(),
                "nick", nick,
                "avatarUrl", sessionText(session, "dingtalk_avatar_url"));
    }

    /**
     * 下发（或复用）当前会话的 CSRF 令牌。
     *
     * <p>单独提供一个轻量接口，避免待办页为了拿令牌而连带调用部门与审批模板接口。</p>
     */
    @GetMapping("/api/dingtalk/csrf")
    public Map<String, Object> csrf(HttpSession session) {
        requireUser(session);
        return Map.of("success", true, "csrfToken", csrfToken(session));
    }

    /** 当前登录态与权限，供各页面在渲染时决定导航项。 */
    @GetMapping("/api/dingtalk/session")
    public Map<String, Object> session(HttpSession session) {
        String userId = requireUser(session);
        String nick = sessionText(session, "dingtalk_nick");
        return payload("success", true, "userId", userId,
                "nick", nick,
                "avatarUrl", sessionText(session, "dingtalk_avatar_url"),
                "canViewStatistics", authority.canViewStatistics(userId, nick),
                "canHandleTasks", authority.isApprover(userId),
                "role", authority.roleOf(userId, nick).name());
    }

    /**
     * 创建测试审批单。
     *
     * <p>创建成功后立即回读一次钉钉详情，把审批节点写入本地并通知审批人。
     * 回读失败不影响创建结果——审批单此时已经在钉钉生成，重复提交会产生两张单，
     * 因此这里只记录日志，不向上抛出异常。</p>
     */
    @PostMapping(value = "/api/dingtalk/approval/test", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> testApproval(@RequestBody TestApprovalRequest request,
            @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
            HttpSession session) {
        String userId = requireUser(session);
        requireCsrf(session, csrfToken);
        Long deptId = approvalService.resolveDepartmentId(userId, request.deptId());
        var response = approvalService.createTestApproval(userId, deptId);
        recordService.saveCreated(response.instanceId(), userId, deptId);
        refreshAfterCreation(response.instanceId());
        return Map.of("success", true, "userId", userId, "instanceId", response.instanceId());
    }

    // ------------------------------------------------------------------
    // 审批人：待办与审批操作
    // ------------------------------------------------------------------

    /**
     * 我的待办：钉钉派给当前用户的待处理审批任务。
     */
    @GetMapping("/api/dingtalk/approvals/todo")
    public Map<String, Object> todo(HttpSession session) {
        String userId = requireUser(session);
        if (!authority.isApprover(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "你不在审批人名单中，无法处理审批单。");
        }
        List<ApprovalTodoService.TodoItem> items = todoService.listPending(userId);
        return payload("success", true, "userId", userId, "total", items.size(), "items", items);
    }

    /**
     * 审批单详情：本地记录 + 钉钉实时详情 + 本系统审批意见时间线。
     *
     * <p>当钉钉侧没有开通“工作流实例读权限”时，会退化为只返回本地数据，
     * 并在响应中标记 {@code degraded=true}，保证页面仍可用。</p>
     */
    @GetMapping("/api/dingtalk/approvals/{instanceId}")
    public Map<String, Object> detail(@PathVariable String instanceId, HttpSession session) {
        String userId = requireUser(session);
        String nick = sessionText(session, "dingtalk_nick");

        DingTalkApprovalService.ApprovalDetail detail = null;
        try {
            detail = approvalService.getApprovalDetail(instanceId);
        } catch (RuntimeException exception) {
            log.warn("获取钉钉审批详情失败，将降级为本地数据展示，instanceId={}", instanceId, exception);
        }

        if (detail == null) {
            ApprovalRecordService.ApprovalRecord local = recordService.findOrNull(instanceId);
            if (local == null || !canViewRecord(userId, nick, local)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到该审批单，或你没有查看权限。");
            }
            return payload("success", true, "approval", local, "degraded", true,
                    "message", "钉钉详情暂时不可用，以下为本地缓存数据。",
                    "timeline", decisionService.timeline(instanceId),
                    "notifications", notifications.history(instanceId),
                    "canDecide", false);
        }

        decisionService.requireVisible(detail, userId, instanceId);
        ApprovalRecordService.SyncResult result = recordService.applyDetail(instanceId, detail);
        notifications.notifyIfStatusChanged(result);

        List<Map<String, Object>> tasks = new ArrayList<>();
        if (detail.tasks() != null) {
            for (DingTalkApprovalService.ApprovalTask task : detail.tasks()) {
                if (task == null) continue;
                tasks.add(payload("taskId", task.taskId(), "activityId", task.activityId(),
                        "userId", task.userId(), "status", task.status(), "result", task.result(),
                        "createTime", task.createTime(), "finishTime", task.finishTime()));
            }
        }
        List<Map<String, Object>> formValues = new ArrayList<>();
        if (detail.formComponentValues() != null) {
            for (DingTalkApprovalService.FormField field : detail.formComponentValues()) {
                if (field == null) continue;
                formValues.add(payload("name", field.name(), "value", field.value(),
                        "componentType", field.componentType(), "id", field.id()));
            }
        }

        ApprovalRecordService.ApprovalRecord record = result.after();
        return payload("success", true, "degraded", false,
                "approval", record,
                "dingtalk", payload(
                        "title", detail.title(),
                        "status", detail.status(),
                        "result", detail.result(),
                        "businessId", detail.businessId(),
                        "createTime", detail.createTime(),
                        "finishTime", detail.finishTime(),
                        "originatorUserId", detail.originatorUserId(),
                        "originatorDeptId", detail.originatorDeptId(),
                        "originatorDeptName", detail.originatorDeptName(),
                        "formValues", formValues,
                        "tasks", tasks),
                "pendingApprovers", todoService.pendingApprovers(detail),
                "timeline", decisionService.timeline(instanceId),
                "notifications", notifications.history(instanceId),
                "canDecide", detail.pendingTaskOf(userId) != null);
    }

    /**
     * 提交审批决策：同意或拒绝。
     *
     * <p>审批意见留空时由系统自动生成，见 {@link ApprovalDecisionService}。</p>
     */
    @PostMapping(value = "/api/dingtalk/approvals/{instanceId}/decision",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> decide(@PathVariable String instanceId, @RequestBody DecisionRequest request,
            @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken, HttpSession session) {
        requireCsrf(session, csrfToken);
        String userId = requireUser(session);
        if (!authority.isApprover(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "你不在审批人名单中，无法处理审批单。");
        }
        ApprovalDecisionService.Action action = ApprovalDecisionService.Action.parse(request.action());
        ApprovalDecisionService.DecisionResult result = decisionService.decide(
                instanceId, userId, sessionText(session, "dingtalk_nick"), action, request.remark());
        return payload("success", true, "action", action.name(),
                "decision", result.decision(), "approval", result.record());
    }

    /**
     * 追加审批意见（不改变审批结果），发起人与审批人可用。
     */
    @PostMapping(value = "/api/dingtalk/approvals/{instanceId}/comments",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> comment(@PathVariable String instanceId, @RequestBody CommentRequest request,
            @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken, HttpSession session) {
        requireCsrf(session, csrfToken);
        String userId = requireUser(session);
        ApprovalDecisionService.Decision decision = decisionService.comment(
                instanceId, userId, sessionText(session, "dingtalk_nick"), request.content());
        return payload("success", true, "decision", decision);
    }

    // ------------------------------------------------------------------
    // 管理：概览、导入与同步
    // ------------------------------------------------------------------

    /**
     * 审批概览。数据范围由 {@link ApprovalAuthorityService.ManagerScope} 决定。
     */
    @GetMapping("/api/dingtalk/approvals")
    public Map<String, Object> approvals(HttpSession session) {
        String userId = requireUser(session);
        String nick = sessionText(session, "dingtalk_nick");
        requireStatisticsViewer(userId, nick);

        ApprovalAuthorityService.ManagerScope scope = authority.effectiveManagerScope(userId, nick);
        List<ApprovalRecordService.ApprovalRecord> approvals;
        Map<String, Long> statistics;
        switch (scope) {
            case ALL -> {
                approvals = recordService.listAll();
                statistics = recordService.statisticsAll();
            }
            case DEPARTMENT -> {
                List<Long> deptIds = List.copyOf(authority.managerDepartmentScope());
                approvals = recordService.listByDepartments(deptIds);
                statistics = recordService.statisticsForDepartments(deptIds);
            }
            default -> {
                approvals = recordService.listForApprover(userId);
                statistics = recordService.statisticsForApprover(userId);
            }
        }
        return payload("success", true, "scope", scope.name(), "role", authority.roleOf(userId, nick).name(),
                "statistics", statistics, "approvals", approvals);
    }

    /** 手工同步单张审批单的钉钉最终状态。 */
    @PostMapping("/api/dingtalk/approvals/{instanceId}/sync")
    public Map<String, Object> sync(@PathVariable String instanceId,
            @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken, HttpSession session) {
        requireCsrf(session, csrfToken);
        String userId = requireUser(session);
        String nick = sessionText(session, "dingtalk_nick");

        ApprovalRecordService.ApprovalRecord local = recordService.findOrNull(instanceId);
        ApprovalRecordService.SyncResult result = (local != null && canViewRecord(userId, nick, local))
                ? recordService.syncInstance(instanceId)
                : recordService.syncAsApprover(instanceId, userId);
        notifications.notifyIfStatusChanged(result);
        return payload("success", true, "approval", result.after());
    }

    /**
     * 导入一张已有审批单。仅允许管理员（ALL 范围）或该单的发起人本人操作。
     */
    @PostMapping(value = "/api/dingtalk/approvals/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> importApproval(@RequestBody ImportRequest request,
            @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken, HttpSession session) {
        requireCsrf(session, csrfToken);
        String userId = requireUser(session);
        String nick = sessionText(session, "dingtalk_nick");
        if (request.instanceId() == null || request.instanceId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审批实例 ID 不能为空。");
        }

        ApprovalRecordService.SyncResult result = authority.hasCompanyWideScope(userId, nick)
                ? recordService.syncInstance(request.instanceId())
                : recordService.importAsOriginator(request.instanceId(), userId);
        notifications.notifyIfStatusChanged(result);
        return payload("success", true, "approval", result.after());
    }

    // ------------------------------------------------------------------
    // 部门信息
    // ------------------------------------------------------------------

    @GetMapping("/api/dingtalk/user/departments")
    public Map<String, Object> getDepartments(HttpSession session) {
        return Map.of("success", true, "departments", approvalService.getDepartmentIds(requireUser(session)));
    }

    @GetMapping("/api/dingtalk/user/department-details")
    public List<Map<String, Object>> getDepartmentDetails(HttpSession session) {
        return approvalService.getDepartmentIds(requireUser(session)).stream()
                .map(approvalService::getDepartmentDetail).toList();
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    /**
     * 创建审批单后回读详情、写入审批节点并通知审批人。失败只记日志。
     */
    private void refreshAfterCreation(String instanceId) {
        try {
            ApprovalRecordService.SyncResult result = recordService.syncInstance(instanceId);
            notifications.notifyPendingApprovers(result.after());
        } catch (RuntimeException exception) {
            log.warn("审批单已创建，但本地回读钉钉详情失败，instanceId={}", instanceId, exception);
        }
    }

    /** 判断当前用户是否有权查看某条本地审批记录。 */
    private boolean canViewRecord(String userId, String nick, ApprovalRecordService.ApprovalRecord record) {
        if (authority.hasCompanyWideScope(userId, nick)) return true;
        if (authority.canViewDepartment(userId, nick, record.deptId())) return true;
        return recordService.isLocalParticipant(record.instanceId(), userId);
    }

    private String requireUser(HttpSession session) {
        String userId = (String) session.getAttribute("dingtalk_user_id");
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先扫码登录钉钉。");
        }
        return userId;
    }

    private void requireCsrf(HttpSession session, String token) {
        String expected = (String) session.getAttribute("approval_csrf_token");
        if (expected == null || !expected.equals(token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "页面验证已失效，请刷新后重试。");
        }
    }

    /** 取得当前会话的 CSRF 令牌，不存在时生成并写入 Session。 */
    private String csrfToken(HttpSession session) {
        String token = (String) session.getAttribute("approval_csrf_token");
        if (token == null) {
            token = UUID.randomUUID().toString();
            session.setAttribute("approval_csrf_token", token);
        }
        return token;
    }

    private void requireStatisticsViewer(String userId, String nick) {
        if (!authority.canViewStatistics(userId, nick)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "你没有审批统计访问权限。");
        }
    }

    private String sessionText(HttpSession session, String name) {
        Object value = session.getAttribute(name);
        return value == null ? "" : value.toString();
    }

    /**
     * 构造响应体。相比 {@code Map.of}，允许 value 为 null，便于直接透传钉钉的可空字段。
     */
    private static Map<String, Object> payload(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            map.put((String) keyValues[index], keyValues[index + 1]);
        }
        return map;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> invalidRequest(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(Map.of("success", false, "message", String.valueOf(exception.getReason())));
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<?> upstreamFailure(RestClientException exception) {
        // 不向浏览器返回可能含 access_token 的异常 URL。
        log.warn("调用钉钉接口失败", exception);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("success", false,
                "message", "钉钉接口请求失败，请检查应用权限、审批模板和网络。若正在提交，请先核对钉钉是否已创建审批单，避免重复提交。"));
    }

    /** 发起测试审批的请求体。 */
    public record TestApprovalRequest(Long deptId) {}

    /** 导入审批实例的请求体。 */
    public record ImportRequest(String instanceId) {}

    /**
     * 提交审批决策的请求体。
     *
     * @param action agree / refuse
     * @param remark 审批意见，留空由系统自动生成
     */
    public record DecisionRequest(String action, String remark) {}

    /** 提交审批意见的请求体。 */
    public record CommentRequest(String content) {}
}
