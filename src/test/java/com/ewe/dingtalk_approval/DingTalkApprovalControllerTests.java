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
    private final DingTalkApprovalController controller = new DingTalkApprovalController(service, records);
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
                .andExpect(jsonPath("$.statistics.total").value(0));
        verify(records, never()).listAll();
    }

    @Test
    void configuredDeveloperCanViewCompanyStatistics() throws Exception {
        ReflectionTestUtils.setField(controller, "developerUserIds", "235638251739-1704317153");
        var session = new MockHttpSession();
        session.setAttribute("dingtalk_user_id", "235638251739-1704317153");
        session.setAttribute("dingtalk_nick", "jack");
        when(records.statisticsAll()).thenReturn(Map.of("total", 0L));
        when(records.listAll()).thenReturn(List.of());
        mvc.perform(get("/api/dingtalk/approvals").session(session))
                .andExpect(status().isOk());
    }
}
