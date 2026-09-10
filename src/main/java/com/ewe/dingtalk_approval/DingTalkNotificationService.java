package com.ewe.dingtalk_approval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 钉钉消息推送。把审批进度以钉钉通知的形式发给发起人与审批人。
 *
 * <p>支持两种通道，由 {@code dingtalk.notify.channel} 选择：</p>
 * <ul>
 *   <li>{@code ROBOT}：机器人单聊消息，robotCode 直接取应用 client-id，无需额外配置（默认）；</li>
 *   <li>{@code WORK_NOTICE}：企业工作通知，需要额外配置 {@code dingtalk.agent-id}。</li>
 * </ul>
 *
 * <p>默认 {@code dingtalk.notify.enabled=false}，测试版不会向同事推送真实消息；
 * 联调时把环境变量 {@code DINGTALK_NOTIFY_ENABLED} 设为 true 即可开启。</p>
 *
 * <p>推送失败不会抛出异常打断审批主流程，而是返回失败结果交由调用方记录，
 * 保证“通知”永远是审批业务的旁路，不影响审批本身是否成功。</p>
 */
@Service
public class DingTalkNotificationService {

    private static final Logger log = LoggerFactory.getLogger(DingTalkNotificationService.class);

    /** 钉钉文本消息内容长度上限，超出会被截断，避免整条消息发送失败。 */
    private static final int MAX_CONTENT_LENGTH = 1000;

    /** 通知通道。 */
    public enum Channel { ROBOT, WORK_NOTICE }

    @Value("${dingtalk.notify.enabled:false}")
    private boolean enabled = false;

    @Value("${dingtalk.notify.channel:ROBOT}")
    private String channelName = "ROBOT";

    @Value("${dingtalk.agent-id:}")
    private String agentId = "";

    @Value("${dingtalk.client-id}")
    private String clientId;

    private final DingTalkTokenService tokenService;
    private final RestClient restClient;

    @Autowired
    public DingTalkNotificationService(DingTalkTokenService tokenService) {
        this(tokenService, RestClient.builder());
    }

    DingTalkNotificationService(DingTalkTokenService tokenService, RestClient.Builder restClientBuilder) {
        this.tokenService = tokenService;
        this.restClient = restClientBuilder.build();
    }

    /** 当前生效的通道。 */
    public Channel channel() {
        return parseChannel(channelName);
    }

    /** 通知功能是否已开启。 */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 向多个钉钉用户发送一条文本消息。
     *
     * @param userIds 接收人钉钉 userId 列表，为空时直接跳过
     * @param text    消息正文
     * @return 发送结果，包含是否成功、使用的通道与失败原因
     */
    public SendOutcome send(List<String> userIds, String text) {
        List<String> recipients = userIds == null ? List.of()
                : userIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        Channel channel = channel();

        if (recipients.isEmpty()) {
            return SendOutcome.skipped(channel, "没有需要通知的接收人");
        }
        if (!enabled) {
            log.info("钉钉通知未开启，跳过发送。接收人={}，内容={}", recipients, abbreviate(text));
            return SendOutcome.skipped(channel, "通知功能未开启（dingtalk.notify.enabled=false）");
        }

        String content = abbreviate(text);
        try {
            if (channel == Channel.WORK_NOTICE) {
                sendWorkNotice(recipients, content);
            } else {
                sendRobotMessage(recipients, content);
            }
            log.info("钉钉通知发送成功，通道={}，接收人={}", channel, recipients);
            return SendOutcome.sent(channel);
        } catch (RestClientException | IllegalStateException exception) {
            log.warn("钉钉通知发送失败，通道={}，接收人={}", channel, recipients, exception);
            return SendOutcome.failed(channel, exception.getMessage());
        }
    }

    /** 机器人单聊消息（oToMessages/batchSend）。 */
    @SuppressWarnings("unchecked")
    private void sendRobotMessage(List<String> userIds, String content) {
        String accessToken = requireAccessToken();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("robotCode", clientId);
        body.put("userIds", userIds);
        body.put("msgKey", "sampleText");
        // msgParam 必须是 JSON 字符串而不是嵌套对象，这是钉钉该接口的特殊约定。
        body.put("msgParam", "{\"content\":\"" + escapeJson(content) + "\"}");

        Map<String, Object> response = restClient.post()
                .uri("https://api.dingtalk.com/v1.0/robot/oToMessages/batchSend")
                .header("x-acs-dingtalk-access-token", accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().body(Map.class);

        if (response == null) {
            throw new IllegalStateException("钉钉未返回机器人消息发送结果");
        }
        Object invalid = response.get("invalidStaffIdList");
        if (invalid instanceof List<?> list && !list.isEmpty()) {
            log.warn("以下用户不是有效的机器人消息接收人：{}", list);
        }
    }

    /** 企业工作通知（corpconversation/asyncsend_v2）。 */
    @SuppressWarnings("unchecked")
    private void sendWorkNotice(List<String> userIds, String content) {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalStateException("使用工作通知通道必须配置 dingtalk.agent-id");
        }
        long parsedAgentId;
        try {
            parsedAgentId = Long.parseLong(agentId.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("dingtalk.agent-id 必须是数字：" + agentId);
        }

        String accessToken = requireAccessToken();
        Map<String, Object> message = Map.of("msgtype", "text", "text", Map.of("content", content));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agent_id", parsedAgentId);
        body.put("userid_list", String.join(",", userIds));
        body.put("msg", message);

        Map<String, Object> response = restClient.post()
                .uri(uriBuilder -> uriBuilder.scheme("https").host("oapi.dingtalk.com")
                        .path("/topapi/message/corpconversation/asyncsend_v2")
                        .queryParam("access_token", accessToken).build())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().body(Map.class);

        if (response == null) {
            throw new IllegalStateException("钉钉未返回工作通知发送结果");
        }
        Number errcode = response.get("errcode") instanceof Number number ? number : null;
        if (errcode != null && errcode.intValue() != 0) {
            throw new IllegalStateException("工作通知发送失败：errcode=" + errcode.intValue()
                    + "，errmsg=" + response.getOrDefault("errmsg", response));
        }
    }

    private String requireAccessToken() {
        DingTalkTokenService.TokenResponse token = tokenService.getAccessToken();
        if (token == null || token.accessToken() == null) {
            throw new IllegalStateException("无法获取钉钉 accessToken");
        }
        return token.accessToken();
    }

    /** 解析配置中的通道名，无法识别时回退到机器人通道。 */
    static Channel parseChannel(String value) {
        if (value != null && "WORK_NOTICE".equals(value.trim().toUpperCase(Locale.ROOT))) {
            return Channel.WORK_NOTICE;
        }
        return Channel.ROBOT;
    }

    private static String abbreviate(String text) {
        String value = text == null ? "" : text;
        return value.length() <= MAX_CONTENT_LENGTH ? value : value.substring(0, MAX_CONTENT_LENGTH) + "…";
    }

    /** 转义 JSON 字符串中的特殊字符，避免消息体拼装失败。 */
    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /**
     * 一次发送的结果。
     *
     * @param status   SENT / FAILED / SKIPPED
     * @param channel  实际使用的通道
     * @param errorMessage 失败或跳过原因，成功时为 null
     */
    public record SendOutcome(String status, Channel channel, String errorMessage) {
        static SendOutcome sent(Channel channel) {
            return new SendOutcome("SENT", channel, null);
        }

        static SendOutcome failed(Channel channel, String errorMessage) {
            return new SendOutcome("FAILED", channel, errorMessage);
        }

        static SendOutcome skipped(Channel channel, String reason) {
            return new SendOutcome("SKIPPED", channel, reason);
        }

        public boolean isSent() {
            return "SENT".equals(status);
        }
    }
}
