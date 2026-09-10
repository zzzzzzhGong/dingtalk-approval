package com.ewe.dingtalk_approval;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 审批状态定时同步任务。
 *
 * <p>作为钉钉 Stream 事件推送的兜底：即使没有开启 Stream 事件订阅，
 * 也能保证“审批中”的单据最终被刷新为通过 / 拒绝 / 终止，并在状态变化时推送通知。</p>
 *
 * <p>可通过 {@code dingtalk.sync.enabled=false} 关闭，或调整
 * {@code dingtalk.sync.interval-ms} 控制同步频率。</p>
 */
@Component
@ConditionalOnProperty(name = "dingtalk.sync.enabled", havingValue = "true", matchIfMissing = true)
public class ApprovalSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(ApprovalSyncScheduler.class);

    private final ApprovalRecordService records;
    private final ApprovalNotificationService notifications;

    @Value("${dingtalk.sync.batch-size:20}")
    private int batchSize = 20;

    public ApprovalSyncScheduler(ApprovalRecordService records, ApprovalNotificationService notifications) {
        this.records = records;
        this.notifications = notifications;
    }

    /**
     * 批量刷新仍处于“审批中”的本地单据。
     *
     * <p>单条失败不会中断整批同步——某张单据在钉钉侧无权限或已被删除时，
     * 其余单据仍然可以正常同步。</p>
     */
    @Scheduled(fixedDelayString = "${dingtalk.sync.interval-ms:300000}",
            initialDelayString = "${dingtalk.sync.initial-delay-ms:30000}")
    public SyncSummary syncRunningInstances() {
        List<ApprovalRecordService.ApprovalRecord> running = records.listRunning(batchSize);
        if (running.isEmpty()) {
            return new SyncSummary(0, 0, 0);
        }

        int succeeded = 0;
        int changed = 0;
        for (ApprovalRecordService.ApprovalRecord record : running) {
            String instanceId = record.instanceId();
            try {
                ApprovalRecordService.SyncResult result = records.syncInstance(instanceId);
                succeeded++;
                if (notifications.notifyIfStatusChanged(result)) {
                    changed++;
                    log.info("审批单状态已更新，instanceId={}，{} → {}", instanceId,
                            result.before().status(), result.after().status());
                }
            } catch (RuntimeException exception) {
                log.warn("同步审批单失败，instanceId={}，将在下一轮重试", instanceId, exception);
            }
        }
        return new SyncSummary(running.size(), succeeded, changed);
    }

    /**
     * 一轮同步的统计结果。
     *
     * @param scanned   本轮扫描的审批中单据数量
     * @param succeeded 成功从钉钉刷新状态的单据数量
     * @param changed   状态发生变化并已触发通知的单据数量
     */
    public record SyncSummary(int scanned, int succeeded, int changed) {}
}
