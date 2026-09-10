package com.ewe.dingtalk_approval;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class DingTalkApprovalServiceTests {
    private DingTalkUserService users;
    private DingTalkTokenService tokens;
    private DingTalkApprovalService service;
    private MockRestServiceServer server;

    @BeforeEach
    void setup() {
        users = mock(DingTalkUserService.class);
        tokens = mock(DingTalkTokenService.class);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new DingTalkApprovalService(tokens, users, builder);
        ReflectionTestUtils.setField(service, "processCode", "test-template");
    }

    @Test
    void singleDepartmentIsSelectedAutomaticallyAndSentToDingTalk() {
        when(users.getDepartmentIds("user-a")).thenReturn(List.of(42L));
        expectCreation(42L);
        assertEquals("instance-1", service.createTestApproval("user-a", null).instanceId());
        server.verify();
    }

    @Test
    void multipleDepartmentsUseTheExplicitSelection() {
        when(users.getDepartmentIds("user-a")).thenReturn(List.of(42L, 83L));
        expectCreation(83L);
        assertEquals("instance-1", service.createTestApproval("user-a", 83L).instanceId());
        server.verify();
    }

    @Test
    void multipleDepartmentsWithoutSelectionDoNotCreateApproval() {
        when(users.getDepartmentIds("user-a")).thenReturn(List.of(42L, 83L));
        assertThrows(ResponseStatusException.class, () -> service.createTestApproval("user-a", null));
        verifyNoInteractions(tokens);
    }

    @Test
    void ForeignDepartmentIsRejectedBeforeCallingCreationApi() {
        when(users.getDepartmentIds("user-a")).thenReturn(List.of(42L));
        assertThrows(ResponseStatusException.class, () -> service.createTestApproval("user-a", 83L));
        verifyNoInteractions(tokens);
    }

    @Test
    void noDepartmentDoesNotCreateApproval() {
        when(users.getDepartmentIds("user-a")).thenReturn(List.of());
        assertThrows(ResponseStatusException.class, () -> service.createTestApproval("user-a", null));
        verifyNoInteractions(tokens);
    }

    @Test
    void missingInstanceIdIsNotReportedAsSuccess() {
        when(users.getDepartmentIds("user-a")).thenReturn(List.of(42L));
        when(tokens.getAccessToken()).thenReturn(new DingTalkTokenService.TokenResponse("test-token", 7200L));
        server.expect(requestTo("https://api.dingtalk.com/v1.0/workflow/processInstances"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThrows(ResponseStatusException.class, () -> service.createTestApproval("user-a", null));
        server.verify();
    }

    @Test
    void approveTaskPostsAgreeActionWithTaskIdAndRemark() {
        token();
        server.expect(requestTo("https://api.dingtalk.com/v1.0/workflow/processInstances/execute"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-acs-dingtalk-access-token", "test-token"))
                .andExpect(jsonPath("$.processInstanceId").value("instance-1"))
                .andExpect(jsonPath("$.taskId").value(99))
                .andExpect(jsonPath("$.result").value("agree"))
                .andExpect(jsonPath("$.remark").value("同意，请照此执行"))
                .andRespond(withSuccess("{\"success\":true,\"result\":true}", MediaType.APPLICATION_JSON));

        service.decideApprovalTask("instance-1", 99L, true, "同意，请照此执行");

        server.verify();
    }

    @Test
    void refuseTaskPostsRefuseAction() {
        token();
        server.expect(requestTo("https://api.dingtalk.com/v1.0/workflow/processInstances/execute"))
                .andExpect(jsonPath("$.result").value("refuse"))
                .andExpect(jsonPath("$.actionName").value("拒绝"))
                .andRespond(withSuccess("{\"success\":true,\"result\":true}", MediaType.APPLICATION_JSON));

        service.decideApprovalTask("instance-1", null, false, "金额超预算");

        server.verify();
    }

    @Test
    void taskIdIsOmittedWhenUnknown() {
        token();
        server.expect(requestTo("https://api.dingtalk.com/v1.0/workflow/processInstances/execute"))
                .andExpect(jsonPath("$.taskId").doesNotExist())
                .andRespond(withSuccess("{\"success\":true,\"result\":true}", MediaType.APPLICATION_JSON));

        service.decideApprovalTask("instance-1", null, true, "同意");

        server.verify();
    }

    @Test
    void failedExecuteResponseIsReportedAsBadGateway() {
        token();
        server.expect(requestTo("https://api.dingtalk.com/v1.0/workflow/processInstances/execute"))
                .andRespond(withSuccess("{\"success\":false,\"message\":\"任务已被处理\"}",
                        MediaType.APPLICATION_JSON));

        assertThrows(ResponseStatusException.class,
                () -> service.decideApprovalTask("instance-1", 99L, true, "同意"));
        server.verify();
    }

    @Test
    void commentIsPostedToDingTalk() {
        token();
        server.expect(requestTo("https://api.dingtalk.com/v1.0/workflow/processInstances/comments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.processInstanceId").value("instance-1"))
                .andExpect(jsonPath("$.commentUserId").value("user-a"))
                .andExpect(jsonPath("$.commentContent").value("补充：发票已附"))
                .andRespond(withSuccess("{\"success\":true,\"result\":true}", MediaType.APPLICATION_JSON));

        service.addComment("instance-1", "user-a", "张三", "补充：发票已附");

        server.verify();
    }

    @Test
    void blankCommentIsRejectedBeforeCallingDingTalk() {
        assertThrows(ResponseStatusException.class,
                () -> service.addComment("instance-1", "user-a", "张三", "   "));
        verifyNoInteractions(tokens);
    }

    @Test
    void approvalDetailExposesTaskIdFormValuesAndPendingTask() {
        token();
        String body = """
                {"result":{"title":"测试审批","status":"RUNNING","result":"NONE","originatorUserId":"user-a",
                  "createTime":"2024-01-01T10:00:00Z",
                  "formComponentValues":[{"name":"金额","value":"100","componentType":"TextField","id":"f1"}],
                  "tasks":[{"taskId":99,"activityId":"act-1","userId":"user-a","status":"RUNNING","result":"NONE"},
                           {"taskId":100,"activityId":"act-2","userId":"user-b","status":"COMPLETED","result":"agree"}]}}
                """;
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "https://api.dingtalk.com/v1.0/workflow/processInstances")))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        DingTalkApprovalService.ApprovalDetail detail = service.getApprovalDetail("instance-1");

        assertEquals("测试审批", detail.title());
        assertEquals(2, detail.tasks().size());
        assertEquals(99L, detail.pendingTaskOf("user-a").taskId());
        assertNull(detail.pendingTaskOf("user-b"), "已完成的审批人不应再有待办任务");
        assertEquals("金额", detail.formComponentValues().get(0).name());
        assertEquals("100", detail.formComponentValues().get(0).value());
        server.verify();
    }

    private void token() {
        when(tokens.getAccessToken()).thenReturn(new DingTalkTokenService.TokenResponse("test-token", 7200L));
    }

    private void expectCreation(long departmentId) {
        when(tokens.getAccessToken()).thenReturn(new DingTalkTokenService.TokenResponse("test-token", 7200L));
        server.expect(requestTo("https://api.dingtalk.com/v1.0/workflow/processInstances"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-acs-dingtalk-access-token", "test-token"))
                .andExpect(jsonPath("$.deptId").value(departmentId))
                .andExpect(jsonPath("$.originatorUserId").value("user-a"))
                .andExpect(jsonPath("$.processCode").value("test-template"))
                .andRespond(withSuccess("{\"instanceId\":\"instance-1\"}", MediaType.APPLICATION_JSON));
    }
}
