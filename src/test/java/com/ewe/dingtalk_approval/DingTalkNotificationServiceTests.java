package com.ewe.dingtalk_approval;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class DingTalkNotificationServiceTests {

    private DingTalkTokenService tokens;
    private DingTalkNotificationService service;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        tokens = mock(DingTalkTokenService.class);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new DingTalkNotificationService(tokens, builder);
        ReflectionTestUtils.setField(service, "clientId", "test-client");
        ReflectionTestUtils.setField(service, "agentId", "");
        ReflectionTestUtils.setField(service, "channelName", "ROBOT");
        ReflectionTestUtils.setField(service, "enabled", false);
        when(tokens.getAccessToken())
                .thenReturn(new DingTalkTokenService.TokenResponse("test-token", 7200L));
    }

    @Test
    void disabledNotificationDoesNotCallDingTalk() {
        DingTalkNotificationService.SendOutcome outcome = service.send(List.of("user-a"), "hello");

        assertThat(outcome.status()).isEqualTo("SKIPPED");
        assertThat(outcome.isSent()).isFalse();
        assertThat(outcome.errorMessage()).contains("未开启");
        server.verify();
        verifyNoInteractions(tokens);
    }

    @Test
    void emptyRecipientsAreSkipped() {
        ReflectionTestUtils.setField(service, "enabled", true);

        DingTalkNotificationService.SendOutcome outcome = service.send(List.of(), "hello");

        assertThat(outcome.status()).isEqualTo("SKIPPED");
        assertThat(outcome.errorMessage()).contains("没有需要通知的接收人");
        server.verify();
        verifyNoInteractions(tokens);
    }

    @Test
    void robotChannelSendsTextMessageToEachUser() {
        ReflectionTestUtils.setField(service, "enabled", true);
        server.expect(requestTo("https://api.dingtalk.com/v1.0/robot/oToMessages/batchSend"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-acs-dingtalk-access-token", "test-token"))
                .andExpect(jsonPath("$.robotCode").value("test-client"))
                .andExpect(jsonPath("$.msgKey").value("sampleText"))
                .andExpect(jsonPath("$.userIds[0]").value("user-a"))
                .andExpect(jsonPath("$.userIds[1]").value("user-b"))
                .andExpect(jsonPath("$.msgParam").value("{\"content\":\"审批已通过\"}"))
                .andRespond(withSuccess("{\"processQueryKey\":\"query-1\"}", MediaType.APPLICATION_JSON));

        DingTalkNotificationService.SendOutcome outcome = service.send(List.of("user-a", "user-b"), "审批已通过");

        assertThat(outcome.isSent()).isTrue();
        assertThat(outcome.channel()).isEqualTo(DingTalkNotificationService.Channel.ROBOT);
        server.verify();
    }

    @Test
    void duplicateRecipientsAreCollapsed() {
        ReflectionTestUtils.setField(service, "enabled", true);
        server.expect(requestTo("https://api.dingtalk.com/v1.0/robot/oToMessages/batchSend"))
                .andExpect(jsonPath("$.userIds.length()").value(1))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        service.send(List.of("user-a", "user-a", "  "), "内容");

        server.verify();
    }

    @Test
    void workNoticeChannelRequiresAgentId() {
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "channelName", "WORK_NOTICE");

        DingTalkNotificationService.SendOutcome outcome = service.send(List.of("user-a"), "内容");

        assertThat(outcome.status()).isEqualTo("FAILED");
        assertThat(outcome.errorMessage()).contains("agent-id");
        server.verify();
    }

    @Test
    void workNoticeChannelSendsCorpConversationMessage() {
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "channelName", "WORK_NOTICE");
        ReflectionTestUtils.setField(service, "agentId", "12345");
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "https://oapi.dingtalk.com/topapi/message/corpconversation/asyncsend_v2")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.agent_id").value(12345))
                .andExpect(jsonPath("$.userid_list").value("user-a,user-b"))
                .andExpect(jsonPath("$.msg.msgtype").value("text"))
                .andExpect(jsonPath("$.msg.text.content").value("请处理审批"))
                .andRespond(withSuccess("{\"errcode\":0,\"errmsg\":\"ok\"}", MediaType.APPLICATION_JSON));

        DingTalkNotificationService.SendOutcome outcome = service.send(List.of("user-a", "user-b"), "请处理审批");

        assertThat(outcome.isSent()).isTrue();
        assertThat(outcome.channel()).isEqualTo(DingTalkNotificationService.Channel.WORK_NOTICE);
        server.verify();
    }

    @Test
    void workNoticeFailureIsReportedInsteadOfThrown() {
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "channelName", "WORK_NOTICE");
        ReflectionTestUtils.setField(service, "agentId", "12345");
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/topapi/message/corpconversation/asyncsend_v2")))
                .andRespond(withSuccess("{\"errcode\":60011,\"errmsg\":\"没有调用该接口的权限\"}",
                        MediaType.APPLICATION_JSON));

        DingTalkNotificationService.SendOutcome outcome = service.send(List.of("user-a"), "内容");

        assertThat(outcome.status()).isEqualTo("FAILED");
        assertThat(outcome.errorMessage()).contains("60011");
        server.verify();
    }

    @Test
    void messageContentIsTruncatedAndEscaped() {
        ReflectionTestUtils.setField(service, "enabled", true);
        server.expect(requestTo("https://api.dingtalk.com/v1.0/robot/oToMessages/batchSend"))
                .andExpect(jsonPath("$.msgParam").value("{\"content\":\"第一行\\n第二行 \\\"引号\\\"\"}"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        service.send(List.of("user-a"), "第一行\n第二行 \"引号\"");

        server.verify();
    }

    @Test
    void channelNameIsParsedCaseInsensitively() {
        assertThat(DingTalkNotificationService.parseChannel("work_notice"))
                .isEqualTo(DingTalkNotificationService.Channel.WORK_NOTICE);
        assertThat(DingTalkNotificationService.parseChannel("robot"))
                .isEqualTo(DingTalkNotificationService.Channel.ROBOT);
        assertThat(DingTalkNotificationService.parseChannel(null))
                .isEqualTo(DingTalkNotificationService.Channel.ROBOT);
    }
}
