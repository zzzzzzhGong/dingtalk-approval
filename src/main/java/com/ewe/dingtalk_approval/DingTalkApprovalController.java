package com.ewe.dingtalk_approval;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class DingTalkApprovalController {
    private final DingTalkApprovalService approvalService;
    private final ApprovalRecordService recordService;

    public DingTalkApprovalController(DingTalkApprovalService approvalService, ApprovalRecordService recordService) {
        this.approvalService = approvalService;
        this.recordService = recordService;
    }

    // GET 只打开页面，不创建审批单。
    @GetMapping("/api/dingtalk/approval/test")
    public ResponseEntity<Void> testPage() {
        return ResponseEntity.status(302).header("Location", "/approval-test.html").build();
    }

    @GetMapping("/api/dingtalk/approval/options")
    public Map<String, Object> options(HttpSession session) {
        String userId = requireUser(session);
        String csrfToken = (String) session.getAttribute("approval_csrf_token");
        if (csrfToken == null) {
            csrfToken = UUID.randomUUID().toString();
            session.setAttribute("approval_csrf_token", csrfToken);
        }
        List<Map<String, Object>> departments = approvalService.getDepartmentIds(userId).stream()
                .distinct().map(id -> {
                    Map<String, Object> detail = approvalService.getDepartmentDetail(id);
                    return Map.<String, Object>of("deptId", id, "name", detail.getOrDefault("name", "部门 " + id));
                }).toList();
        return Map.of("success", true, "userId", userId, "departments", departments,
                "templates", approvalService.getVisibleProcessTemplates(userId), "csrfToken", csrfToken);
    }

    @PostMapping(value = "/api/dingtalk/approval/test", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> testApproval(@RequestBody TestApprovalRequest request,
            @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
            HttpSession session) {
        String userId = requireUser(session);
        String expected = (String) session.getAttribute("approval_csrf_token");
        if (expected == null || !expected.equals(csrfToken)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "页面验证已失效，请刷新后重试。");
        }
        Long deptId = approvalService.resolveDepartmentId(userId, request.deptId());
        var response = approvalService.createTestApproval(userId, deptId);
        recordService.saveCreated(response.instanceId(), userId, deptId);
        return Map.of("success", true, "userId", userId, "instanceId", response.instanceId());
    }

    @GetMapping("/api/dingtalk/approvals")
    public Map<String, Object> approvals(HttpSession session) {
        String userId = requireUser(session);
        return Map.of("success", true, "statistics", recordService.statistics(userId),
                "approvals", recordService.list(userId));
    }

    @PostMapping("/api/dingtalk/approvals/{instanceId}/sync")
    public Map<String, Object> sync(@PathVariable String instanceId,
            @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken, HttpSession session) {
        requireCsrf(session, csrfToken);
        return Map.of("success", true, "approval", recordService.sync(instanceId, requireUser(session)));
    }

    @PostMapping(value = "/api/dingtalk/approvals/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> importApproval(@RequestBody ImportRequest request,
            @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken, HttpSession session) {
        requireCsrf(session, csrfToken);
        return Map.of("success", true,
                "approval", recordService.importAndSync(request.instanceId(), requireUser(session)));
    }

    @GetMapping("/api/dingtalk/user/departments")
    public Map<String, Object> getDepartments(HttpSession session) {
        return Map.of("success", true, "departments", approvalService.getDepartmentIds(requireUser(session)));
    }

    @GetMapping("/api/dingtalk/user/department-details")
    public List<Map<String, Object>> getDepartmentDetails(HttpSession session) {
        return approvalService.getDepartmentIds(requireUser(session)).stream()
                .map(approvalService::getDepartmentDetail).toList();
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

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> invalidRequest(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(Map.of("success", false, "message", exception.getReason()));
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<?> upstreamFailure(RestClientException exception) {
        // 不向浏览器返回可能含 access_token 的异常 URL。
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("success", false,
                "message", "钉钉接口请求失败，请检查应用权限、审批模板和网络。若正在提交，请先核对钉钉是否已创建审批单，避免重复提交。"));
    }

    public record TestApprovalRequest(Long deptId) {}
    public record ImportRequest(String instanceId) {}
}
