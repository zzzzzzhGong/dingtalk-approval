package com.ewe.dingtalk_approval;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 审批通知的编排层：负责决定“通知谁、通知什么”，并把每次发送结果落库留痕。
 *
 * <p>真正的消息发送由 {@link DingTalkNotificationService} 完成。
 * 本层不关心通道细节，只保证：接收人去重、消息文案统一、发送结果可追溯。</p>
 */
@Service
public class ApprovalNotificationService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalNotificationService.class);

    private final ApprovalRecordService records;
    private final DingTalkNotificationService sender;
    private final JdbcClient jdbc;

    public ApprovalNotificationService(ApprovalRecordService records, DingTalkNotificationService sender,
            JdbcClient jdbc) {
        this.records = records;
        this.sender = sender;
        this.jdbc = jdbc;
    }

    /**
     * 通知审批人：有一张新的审批单等待处理。
     *
     * <p>审批单刚创建时钉钉可能还没生成任务节点，此时接收人列表为空，属于正常情况，
     * 定时同步任务会在后续轮次补齐状态。</p>
     */
    public void notifyPendingApprovers(ApprovalRecordService.ApprovalRecord record) {
        Set<String> recipients = new LinkedHashSet<>(records.participants(record.instanceId()));
        recipients.remove(record.originatorUserId());
        if (recipients.isEmpty()) return;

        String text = """
                【待审批】%s
                发起人：%s
                部门：%s
                实例编号：%s
                请登录 EWE 审批中心或钉钉审批处理。""".formatted(
                displayTitle(record), displayUser(record.originatorUserId()), displayDept(record.deptId()),
                record.instanceId());
        dispatch(record.instanceId(), recipients, "TASK_PENDING", text);
    }

    /**
     * 通知相关人员：某位审批人已经提交了审批意见 / 审批结果。
     *
     * @param actorUserId 操作人
     * @param actorNick   操作人昵称
     * @param actionLabel 操作的中文描述，例如“同意”“拒绝”“补充意见”
     * @param remark      审批意见原文，可为空
     */
    public void notifyDecision(ApprovalRecordService.ApprovalRecord record, String actorUserId, String actorNick,
            String actionLabel, String remark) {
        Set<String> recipients = new LinkedHashSet<>();
        recipients.add(record.originatorUserId());
        recipients.addAll(records.participants(record.instanceId()));
        recipients.remove(actorUserId);
        if (recipients.isEmpty()) return;

        String text = """
                【审批动态】%s
                处理人：%s
                处理动作：%s
                审批意见：%s
                当前状态：%s
                实例编号：%s""".formatted(
                displayTitle(record), displayUser(actorNick != null && !actorNick.isBlank() ? actorNick : actorUserId),
                actionLabel, remark == null || remark.isBlank() ? "（无）" : remark,
                ApprovalStatus.parse(record.status()).label(), record.instanceId());
        dispatch(record.instanceId(), recipients, "DECISION", text);
    }

    /**
     * 通知相关人员：审批单的最终状态发生了变化（由定时同步或 Stream 事件触发）。
     * 调用方必须先用 {@link ApprovalRecordService#claimNotification} 抢占通知权，避免重复推送。
     */
    public void notifyStatusChanged(ApprovalRecordService.ApprovalRecord record, ApprovalStatus previous) {
        Set<String> recipients = new LinkedHashSet<>();
        recipients.add(record.originatorUserId());
        recipients.addAll(records.participants(record.instanceId()));
        if (recipients.isEmpty()) return;

        ApprovalStatus current = ApprovalStatus.parse(record.status());
        String text = """
                【审批结果】%s
                发起人：%s
                状态变更：%s → %s
                实例编号：%s""".formatted(
                displayTitle(record), displayUser(record.originatorUserId()),
                previous == null ? "未知" : previous.label(), current.label(), record.instanceId());
        dispatch(record.instanceId(), recipients, "STATUS_CHANGED", text);
    }

    /**
     * 判断一次同步是否产生了状态变化，若是则抢占通知权并推送通知。
     *
     * <p>定时同步、Stream 事件与页面刷新都会调用本方法，
     * 通过 {@link ApprovalRecordService#claimNotification} 保证同一状态只推送一次。</p>
     *
     * @param result 同步结果，可为 null（事件已处理过时）
     * @return true 表示本次确实发送了通知
     */
    public boolean notifyIfStatusChanged(ApprovalRecordService.SyncResult result) {
        if (result == null || !result.statusChanged()) return false;
        String instanceId = result.after().instanceId();
        if (!records.claimNotification(instanceId, result.after().status())) return false;
        notifyStatusChanged(result.after(), ApprovalStatus.parse(result.before().status()));
        return true;
    }

    /** 查询某张审批单的通知发送记录，用于管理页排障。 */
    public List<NotificationLog> history(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) return List.of();
        return jdbc.sql("""
                select id, instance_id, recipient_user_id, event, channel, status, error_message, created_at
                from approval_notification where instance_id=:instanceId
                order by created_at desc
                """).param("instanceId", instanceId)
                .query((rs, rowNum) -> new NotificationLog(
                        rs.getLong("id"),
                        rs.getString("instance_id"),
                        rs.getString("recipient_user_id"),
                        rs.getString("event"),
                        rs.getString("channel"),
                        rs.getString("status"),
                        rs.getString("error_message"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    /**
     * 向同一批接收人发送同一条消息，并逐条记录发送结果。
     */
    private void dispatch(String instanceId, Set<String> recipients, String event, String text) {
        List<String> targets = new ArrayList<>(recipients);
        DingTalkNotificationService.SendOutcome outcome = sender.send(targets, text);
        for (String userId : targets) {
            jdbc.sql("""
                    insert into approval_notification(instance_id, recipient_user_id, event, channel,
                        payload, status, error_message, created_at)
                    values (:instanceId, :userId, :event, :channel, :payload, :status, :errorMessage, :createdAt)
                    """).param("instanceId", instanceId).param("userId", userId).param("event", event)
                    .param("channel", outcome.channel().name()).param("payload", text)
                    .param("status", outcome.status()).param("errorMessage", abbreviate(outcome.errorMessage()))
                    .param("createdAt", OffsetDateTime.now(ZoneOffset.UTC)).update();
        }
        if (!outcome.isSent()) {
            log.debug("审批通知未实际发出，event={}，状态={}，原因={}", event, outcome.status(),
                    outcome.errorMessage());
        }
    }

    private static String displayTitle(ApprovalRecordService.ApprovalRecord record) {
        if (record.title() != null && !record.title().isBlank()) return record.title();
        if (record.businessId() != null && !record.businessId().isBlank()) return record.businessId();
        return "审批单";
    }

    private static String displayUser(String userId) {
        return userId == null || userId.isBlank() ? "未知" : userId;
    }

    private static String displayDept(Long deptId) {
        return deptId == null ? "未知" : String.valueOf(deptId);
    }

    private static String abbreviate(String value) {
        if (value == null) return null;
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    /**
     * 通知发送记录。
     *
     * @param status SENT 已发送 / FAILED 发送失败 / SKIPPED 未开启或被跳过
     */
    public record NotificationLog(Long id, String instanceId, String recipientUserId, String event,
            String channel, String status, String errorMessage, OffsetDateTime createdAt) {}
}
