package com.ewe.dingtalk_approval;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import java.util.Map;

class DingTalkApprovalControllerTests {
    private final DingTalkApprovalService service = mock(DingTalkApprovalService.class);
    private final ApprovalRecordService records = mock(ApprovalRecordService.class);
    private final ApprovalDecisionService decisions = mock(ApprovalDecisionService.class);
    private final ApprovalTodoService todos = mock(ApprovalTodoService.class);
    private final ApprovalNotificationService notifications = mock(ApprovalNotificationService.class);
    private final ApprovalAuthorityService authority = new ApprovalAuthorityService();
    private final DingTalkApprovalController controller = new DingTalkApprovalController(
            service, records, decisions, todos, authority, notifications);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

    @Test
    void getOnlyOpensPage() throws Exception {
        mvc.perform(get("/api/dingtalk/approval/test")).andExpect(status().isFound());
        verifyNoInteractions(service);
    }

    @Test
    void unauthenticatedCreationIsRejected() throws Exception {
        mvc.perform(post("/api/dingtalk/approval/test").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void missingCsrfTokenIsRejected() throws Exception {
        var session = new MockHttpSession();
        session.setAttribute("dingtalk_user_id", "user-a");
        mvc.perform(post("/api/dingtalk/approval/test").session(session)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void creationUsesSessionIdentityAndAcceptsSelectedDepartment() throws Exception {
        var session = new MockHttpSession();
        session.setAttribute("dingtalk_user_id", "user-a");
        session.setAttribute("approval_csrf_token", "test-csrf");
        when(service.resolveDepartmentId("user-a", 83L)).thenReturn(83L);
        when(service.createTestApproval("user-a", 83L))
                .thenReturn(new DingTalkApprovalService.ApprovalResponse("instance-1"));
        mvc.perform(post("/api/dingtalk/approval/test").session(session)
                .header("X-CSRF-Token", "test-csrf").contentType(MediaType.APPLICATION_JSON)
                .content("{\"deptId\":\"83\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.instanceId").value("instance-1"));
        verify(service).createTestApproval("user-a", 83L);
        verify(records).saveCreated("instance-1", "user-a", 83L);
    }

    @Test
    void regularEmployeeCannotViewCompanyStatistics() throws Exception {
        var session = new MockHttpSession();
        session.setAttribute("dingtalk_user_id", "user-a");
        session.setAttribute("dingtalk_nick", "Regular Employee");
        mvc.perform(get("/api/dingtalk/approvals").session(session))
                .andExpect(status().isForbidden());
        verifyNoInteractions(records);
    }

    @Test
    void vincentCanViewCompanyStatistics() throws Exception {
        var session = new MockHttpSession();
        session.setAttribute("dingtalk_user_id", "vincent-id");
        session.setAttribute("dingtalk_nick", "Vincent Wang");
        when(records.statisticsForApprover("vincent-id")).thenReturn(Map.of("total", 0L));
        when(records.listForApprover("vincent-id")).thenReturn(List.of());
        mvc.perform(get("/api/dingtalk/approvals").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statistics.total").value(0))
                .andExpect(jsonPath("$.scope").value("INVOLVED"));
        verify(records, never()).listAll();
    }

    @Test
    void configuredDeveloperCanViewCompanyStatistics() throws Exception {
        ReflectionTestUtils.setField(authority, "developerUserIds", "235638251739-1704317153");
        var session = new MockHttpSession();
        session.setAttribute("dingtalk_user_id", "235638251739-1704317153");
        session.setAttribute("dingtalk_nick", "jack");
        when(records.statisticsAll()).thenReturn(Map.of("total", 0L));
        when(records.listAll()).thenReturn(List.of());
        mvc.perform(get("/api/dingtalk/approvals").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("ALL"));
        verify(records).listAll();
    }

    @Test
    void managerWithConfiguredDepartmentScopeOnlySeesThatDepartment() throws Exception {
        ReflectionTestUtils.setField(authority, "managerNames", "Dept Manager");
        ReflectionTestUtils.setField(authority, "managerDeptIds", "42");
        var session = new MockHttpSession();
        session.setAttribute("dingtalk_user_id", "manager-1");
        session.setAttribute("dingtalk_nick", "Dept Manager");
        when(records.listByDepartments(List.of(42L))).thenReturn(List.of());
        when(records.statisticsForDepartments(List.of(42L))).thenReturn(Map.of("total", 0L));

        mvc.perform(get("/api/dingtalk/approvals").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("DEPARTMENT"));
        verify(records, never()).listAll();
        verify(records, never()).listForApprover(anyString());
    }

    @Test
    void employeeOutsideApproverAllowlistCannotOpenTodoList() throws Exception {
        ReflectionTestUtils.setField(authority, "approverUserIds", "allowed-user");
        var session = new MockHttpSession();
        session.setAttribute("dingtalk_user_id", "other-user");
        mvc.perform(get("/api/dingtalk/approvals/todo").session(session))
                .andExpect(status().isForbidden());
        verifyNoInteractions(todos);
    }

    @Test
    void todoListReturnsPendingItems() throws Exception {
        var session = new MockHttpSession();
        session.setAttribute("dingtalk_user_id", "approver-1");
        when(todos.listPending("approver-1")).thenReturn(List.of());
        mvc.perform(get("/api/dingtalk/approvals/todo").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void decisionWithoutCsrfTokenIsRejected() throws Exception {
        var session = new MockHttpSession();
        session.setAttribute("dingtalk_user_id", "approver-1");
        mvc.perform(post("/api/dingtalk/approvals/instance-1/decision").session(session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"agree\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(decisions);
    }

    @Test
    void decisionRejectsUnknownAction() throws Exception {
        var session = new MockHttpSession();
        session.setAttribute("dingtalk_user_id", "approver-1");
        session.setAttribute("approval_csrf_token", "test-csrf");
        mvc.perform(post("/api/dingtalk/approvals/instance-1/decision").session(session)
                .header("X-CSRF-Token", "test-csrf")
                .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"drop-table\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(decisions);
    }

    @Test
    void decisionIsForwardedToServiceForApprover() throws Exception {
        var session = new MockHttpSession();
        session.setAttribute("dingtalk_user_id", "approver-1");
        session.setAttribute("dingtalk_nick", "审批人甲");
        session.setAttribute("approval_csrf_token", "test-csrf");
        when(decisions.decide(eq("instance-1"), eq("approver-1"), eq("审批人甲"),
                eq(ApprovalDecisionService.Action.AGREE), eq("同意")))
                .thenReturn(new ApprovalDecisionService.DecisionResult(null, null));

        mvc.perform(post("/api/dingtalk/approvals/instance-1/decision").session(session)
                .header("X-CSRF-Token", "test-csrf")
                .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"agree\",\"remark\":\"同意\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("AGREE"));
    }

    @Test
    void detailFallsBackToLocalRecordWhenDingTalkIsUnavailable() throws Exception {
        var session = new MockHttpSession();
        session.setAttribute("dingtalk_user_id", "user-a");
        when(service.getApprovalDetail("instance-1")).thenThrow(new IllegalStateException("钉钉不可用"));
        when(records.findOrNull("instance-1")).thenReturn(new ApprovalRecordService.ApprovalRecord(
                "instance-1", "user-a", 42L, null, "测试审批", "RUNNING", null, null, null, null));
        when(records.isLocalParticipant("instance-1", "user-a")).thenReturn(true);

        mvc.perform(get("/api/dingtalk/approvals/instance-1").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.approval.title").value("测试审批"));
    }

    @Test
    void detailIsHiddenFromUnrelatedUserWhenDingTalkIsUnavailable() throws Exception {
        var session = new MockHttpSession();
        session.setAttribute("dingtalk_user_id", "stranger");
        when(service.getApprovalDetail("instance-1")).thenThrow(new IllegalStateException("钉钉不可用"));
        when(records.findOrNull("instance-1")).thenReturn(new ApprovalRecordService.ApprovalRecord(
                "instance-1", "user-a", 42L, null, "测试审批", "RUNNING", null, null, null, null));
        when(records.isLocalParticipant("instance-1", "stranger")).thenReturn(false);

        mvc.perform(get("/api/dingtalk/approvals/instance-1").session(session))
                .andExpect(status().isNotFound());
    }
}
