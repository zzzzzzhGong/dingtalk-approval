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
