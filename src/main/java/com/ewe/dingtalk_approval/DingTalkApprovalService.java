package com.ewe.dingtalk_approval;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class DingTalkApprovalService {

    @Value("${dingtalk.process-code}")
    private String processCode;

    private final DingTalkTokenService tokenService;

    private final RestClient restClient;

    private final DingTalkUserService userService;

    @org.springframework.beans.factory.annotation.Autowired
    public DingTalkApprovalService(DingTalkTokenService tokenService, DingTalkUserService userService) {
        this(tokenService, userService, RestClient.builder());
    }

    DingTalkApprovalService(
        
            DingTalkTokenService tokenService,
            DingTalkUserService userService, RestClient.Builder restClientBuilder) {

        this.tokenService = tokenService;
        this.userService = userService;
        this.restClient = restClientBuilder.build();
    }

    public List<Long> getDepartmentIds(String userId) {
        return userService.getDepartmentIds(userId);
    }

    /**
     * 查询钉钉中当前用户有权发起的审批模板。可见范围由钉钉审批后台统一维护，
     * 本系统不再自行复制一套人员/部门权限规则。
     */
    @SuppressWarnings("unchecked")
    public List<ProcessTemplate> getVisibleProcessTemplates(String userId) {
        DingTalkTokenService.TokenResponse token = tokenService.getAccessToken();
        if (token == null || token.accessToken() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法获取钉钉 accessToken。");
        }
        Map<String, Object> response = restClient.post()
                .uri(uriBuilder -> uriBuilder.scheme("https").host("oapi.dingtalk.com")
                        .path("/topapi/process/listbyuserid")
                        .queryParam("access_token", token.accessToken()).build())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("userid", userId, "offset", 0, "size", 100))
                .retrieve().body(Map.class);
        if (response == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "钉钉未返回可见审批模板。");
        }
        Number errCode = (Number) response.get("errcode");
        if (errCode != null && errCode.intValue() != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "查询可见审批模板失败：" + response.getOrDefault("errmsg", "未知错误"));
        }
        Map<String, Object> result = response.get("result") instanceof Map<?, ?> value
                ? (Map<String, Object>) value : Map.of();
        Object rawList = result.get("process_list");
        if (!(rawList instanceof List<?>)) rawList = result.get("list");
        if (!(rawList instanceof List<?> templates)) return List.of();

        Map<String, ProcessTemplate> unique = new LinkedHashMap<>();
        for (Object item : templates) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            String code = text(raw.get("process_code"), raw.get("processCode"));
            String name = text(raw.get("name"), raw.get("flow_title"), raw.get("flowTitle"));
            if (code == null || name == null) continue;
            unique.putIfAbsent(code, new ProcessTemplate(code, name,
                    text(raw.get("icon_url"), raw.get("iconUrl")), text(raw.get("url")),
                    code.equals(processCode)));
        }
        return List.copyOf(unique.values());
    }

    private String text(Object... candidates) {
        for (Object value : candidates) {
            if (value != null && !value.toString().isBlank()) return value.toString();
        }
        return null;
    }

        public ApprovalResponse createTestApproval(
                String userId,
                Long deptId) {

        deptId = resolveDepartmentId(userId, deptId);

        DingTalkTokenService.TokenResponse token =
                tokenService.getAccessToken();

        if (token == null ||
                token.accessToken() == null) {

            throw new RuntimeException(
                    "Failed to get DingTalk accessToken"
            );
        }

        List<FormComponentValue> formValues =
                List.of(
                        new FormComponentValue(
                                "申请单号",
                                "TEST-" + java.util.UUID.randomUUID()
                        ),
                        new FormComponentValue(
                                "金额",
                                "100"
                        ),
                        new FormComponentValue(
                                "申请说明",
                                "Java API 测试"
                        )
                );

        ApprovalRequest request =
                new ApprovalRequest(
                        userId,
                        processCode,
                        deptId,
                        formValues
                );

        ApprovalResponse response = restClient.post()
                .uri(
                    "https://api.dingtalk.com"
                    + "/v1.0/workflow/processInstances"
                )
                .header(
                    "x-acs-dingtalk-access-token",
                    token.accessToken()
                )
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ApprovalResponse.class);
        if (response == null || response.instanceId() == null || response.instanceId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "钉钉未返回审批实例 ID，请先核对钉钉中是否已创建，避免重复提交。");
        }
        return response;
    }

    public ApprovalDetail getApprovalDetail(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审批实例 ID 不能为空。");
        }
        DingTalkTokenService.TokenResponse token = tokenService.getAccessToken();
        if (token == null || token.accessToken() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法获取钉钉 accessToken。");
        }
        ApprovalDetailResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder.scheme("https").host("api.dingtalk.com")
                        .path("/v1.0/workflow/processInstances")
                        .queryParam("processInstanceId", instanceId).build())
                .header("x-acs-dingtalk-access-token", token.accessToken())
                .retrieve().body(ApprovalDetailResponse.class);
        if (response == null || response.result() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "钉钉未返回审批实例详情。");
        }
        return response.result();
    }

    /**
     * 同意或拒绝一个审批任务，结果会实时写回钉钉流程，钉钉端与本地库保持一致。
     *
     * <p>对应钉钉「同意或拒绝审批任务」接口。{@code taskId} 可为空，
     * 为空时由钉钉按当前待办任务处理。</p>
     *
     * @param instanceId 审批实例 ID
     * @param taskId     审批任务 ID，可通过实例详情中的 tasks 获取
     * @param agree      true 表示同意，false 表示拒绝
     * @param remark     审批意见，会在钉钉审批记录中展示
     */
    public void decideApprovalTask(String instanceId, Long taskId, boolean agree, String remark) {
        requireInstanceId(instanceId);
        DingTalkTokenService.TokenResponse token = requireAccessToken();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("processInstanceId", instanceId);
        body.put("result", agree ? "agree" : "refuse");
        body.put("actionName", agree ? "同意" : "拒绝");
        body.put("remark", remark == null ? "" : remark);
        if (taskId != null) {
            body.put("taskId", taskId);
        }

        Map<String, Object> response = restClient.post()
                .uri("https://api.dingtalk.com/v1.0/workflow/processInstances/execute")
                .header("x-acs-dingtalk-access-token", token.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().body(Map.class);

        requireSuccess(response, agree ? "同意审批任务失败" : "拒绝审批任务失败");
    }

    /**
     * 向审批实例追加一条审批意见（评论），用于审批人提交补充反馈而不改变审批结果。
     *
     * @param instanceId 审批实例 ID
     * @param userId     评论人钉钉 userId
     * @param userNick   评论人昵称，仅用于钉钉端展示
     * @param content    评论内容，支持多行文本
     */
    public void addComment(String instanceId, String userId, String userNick, String content) {
        requireInstanceId(instanceId);
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审批意见不能为空。");
        }
        DingTalkTokenService.TokenResponse token = requireAccessToken();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("processInstanceId", instanceId);
        body.put("commentUserId", userId);
        body.put("commentUserNick", userNick == null ? "" : userNick);
        body.put("commentContent", content);

        Map<String, Object> response = restClient.post()
                .uri("https://api.dingtalk.com/v1.0/workflow/processInstances/comments")
                .header("x-acs-dingtalk-access-token", token.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().body(Map.class);

        requireSuccess(response, "提交审批意见失败");
    }

    private void requireInstanceId(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审批实例 ID 不能为空。");
        }
    }

    private DingTalkTokenService.TokenResponse requireAccessToken() {
        DingTalkTokenService.TokenResponse token = tokenService.getAccessToken();
        if (token == null || token.accessToken() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法获取钉钉 accessToken。");
        }
        return token;
    }

    /**
     * 校验钉钉返回体中的 success / result 字段。钉钉部分接口即使业务失败也返回 HTTP 200，
     * 因此必须显式判断，不能只依赖 HTTP 状态码。
     */
    private void requireSuccess(Map<String, Object> response, String message) {
        if (response == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, message + "：钉钉未返回内容。");
        }
        Object success = response.get("success");
        Object result = response.get("result");
        boolean ok = (success == null || Boolean.TRUE.equals(success))
                && (result == null || Boolean.TRUE.equals(result));
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    message + "：" + response.getOrDefault("message", response.getOrDefault("errmsg", "钉钉返回失败")));
        }
    }


    public Long resolveDepartmentId(String userId, Long requestedDeptId) {
        List<Long> departmentIds = getDepartmentIds(userId).stream().distinct().toList();
        if (departmentIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未查到所属部门，无法发起审批。");
        }
        if (requestedDeptId == null) {
            if (departmentIds.size() == 1) {
                return departmentIds.get(0);
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "你属于多个部门，请选择本次发起审批的部门。");
        }
        if (!departmentIds.contains(requestedDeptId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "所选部门不属于当前用户，请刷新部门列表。");
        }
        return requestedDeptId;
    }

    public record FormComponentValue(
            String name,
            String value
    ) {
    }


    public Map<String, Object> getDepartmentDetail(
        Long deptId) {

        return userService.getDepartmentDetail(deptId);
        }


    public record ApprovalRequest(
            String originatorUserId,
            String processCode,
            Long deptId,
            List<FormComponentValue> formComponentValues
    ) {
    }


    public record ApprovalResponse(
            String instanceId
    ) {
    }

    public record ProcessTemplate(String processCode, String name, String iconUrl, String url,
            boolean apiIntegrated) {}

    public record ApprovalDetailResponse(ApprovalDetail result, Object success) {}

    /**
     * 审批任务（审批节点上某个人的待办 / 已办记录）。
     *
     * @param taskId     审批任务 ID，提交同意或拒绝时必须回传
     * @param activityId 审批节点 ID
     * @param userId     任务处理人钉钉 userId
     * @param status     钉钉任务状态，如 RUNNING / COMPLETED
     * @param result     钉钉任务结果，如 NONE / agree / refuse
     * @param createTime 任务创建时间
     * @param finishTime 任务完成时间
     */
    public record ApprovalTask(Long taskId, String activityId, String userId, String status, String result,
            String createTime, String finishTime) {

        /**
         * 判断该任务是否仍是某人的待办。
         *
         * <p>钉钉在不同接口中返回的取值大小写不完全一致，这里统一按大写比较；
         * 只有“运行中且结果为空/NONE”的任务才允许提交审批意见。</p>
         */
        public boolean isPendingFor(String candidateUserId) {
            if (candidateUserId == null || userId == null || !candidateUserId.equals(userId)) {
                return false;
            }
            String normalizedStatus = status == null ? "" : status.trim().toUpperCase(java.util.Locale.ROOT);
            String normalizedResult = result == null ? "" : result.trim().toUpperCase(java.util.Locale.ROOT);
            boolean running = normalizedStatus.isEmpty() || "RUNNING".equals(normalizedStatus);
            boolean undecided = normalizedResult.isEmpty() || "NONE".equals(normalizedResult);
            return running && undecided;
        }
    }

    /**
     * 审批表单字段。{@code componentType} 用于前端区分单行文本、多行文本（富文本说明）等控件。
     */
    public record FormField(String name, String value, String componentType, String id) {}

    /**
     * 审批实例详情。
     *
     * @param formComponentValues 表单内容，审批人据此判断是否同意
     * @param tasks               各审批节点的任务，含 taskId
     */
    public record ApprovalDetail(String title, String finishTime, String originatorUserId,
            String originatorDeptId, String originatorDeptName, String status, String result,
            String businessId, String createTime, List<String> approverUserIds, List<ApprovalTask> tasks,
            List<FormField> formComponentValues) {

        /**
         * 找出指定用户在本次审批中待处理的任务，找不到返回 null。
         */
        public ApprovalTask pendingTaskOf(String userId) {
            if (tasks == null) return null;
            return tasks.stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(task -> task.isPendingFor(userId))
                    .findFirst().orElse(null);
        }
    }
}
