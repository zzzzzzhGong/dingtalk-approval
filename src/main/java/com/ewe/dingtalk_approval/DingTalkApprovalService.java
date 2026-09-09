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

    public record ApprovalTask(String userId, String status, String result) {}

    public record ApprovalDetail(String title, String finishTime, String originatorUserId,
            String originatorDeptId, String originatorDeptName, String status, String result,
            String businessId, String createTime, List<String> approverUserIds, List<ApprovalTask> tasks) {}
}
