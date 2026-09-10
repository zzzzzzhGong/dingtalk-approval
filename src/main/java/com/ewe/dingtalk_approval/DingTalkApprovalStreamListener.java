package com.ewe.dingtalk_approval;

import java.util.concurrent.atomic.AtomicBoolean;

import com.dingtalk.open.app.api.OpenDingTalkClient;
import com.dingtalk.open.app.api.OpenDingTalkStreamClientBuilder;
import com.dingtalk.open.app.api.security.AuthClientCredential;
import com.dingtalk.open.app.stream.protocol.event.EventAckStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "dingtalk.stream.enabled", havingValue = "true")
public class DingTalkApprovalStreamListener implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(DingTalkApprovalStreamListener.class);
    private static final String APPROVAL_EVENT = "bpms_instance_change";

    private final ApprovalRecordService records;
    private final ApprovalNotificationService notifications;
    private final OpenDingTalkClient client;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public DingTalkApprovalStreamListener(ApprovalRecordService records, ApprovalNotificationService notifications,
            @Value("${dingtalk.client-id}") String clientId,
            @Value("${dingtalk.client-secret}") String clientSecret) {
        this.records = records;
        this.notifications = notifications;
        this.client = OpenDingTalkStreamClientBuilder.custom()
                .credential(new AuthClientCredential(clientId, clientSecret))
                .registerAllEventListener(event -> {
                    if (!APPROVAL_EVENT.equals(event.getEventType())) return EventAckStatus.SUCCESS;
                    String instanceId = event.getData().getString("processInstanceId");
                    String changeType = event.getData().getString("type");
                    try {
                        ApprovalRecordService.SyncResult result = records.syncFromEvent(
                                event.getEventId(), APPROVAL_EVENT + ":" + changeType, instanceId);
                        notifications.notifyIfStatusChanged(result);
                        log.info("已同步钉钉审批事件，eventId={}, instanceId={}, type={}",
                                event.getEventId(), instanceId, changeType);
                        return EventAckStatus.SUCCESS;
                    } catch (Exception exception) {
                        log.error("同步钉钉审批事件失败，eventId={}, instanceId={}",
                                event.getEventId(), instanceId, exception);
                        return EventAckStatus.LATER;
                    }
                }).build();
    }

    @Override public void start() {
        try {
            client.start();
            running.set(true);
            log.info("钉钉 Stream 审批事件监听已启动，将同步全部可访问审批模板");
        } catch (Exception exception) {
            throw new IllegalStateException("钉钉 Stream 连接启动失败", exception);
        }
    }

    @Override public void stop() {
        try { client.stop(); } catch (Exception exception) { log.warn("停止钉钉 Stream 连接失败", exception); }
        finally { running.set(false); }
    }

    @Override public boolean isRunning() { return running.get(); }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE; }
}
