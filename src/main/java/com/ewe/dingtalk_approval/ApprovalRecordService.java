package com.ewe.dingtalk_approval;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashSet;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApprovalRecordService {
    private final JdbcClient jdbc;
    private final DingTalkApprovalService dingTalk;

    public ApprovalRecordService(JdbcClient jdbc, DingTalkApprovalService dingTalk) {
        this.jdbc = jdbc;
        this.dingTalk = dingTalk;
    }

    public ApprovalRecord saveCreated(String instanceId, String userId, Long deptId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                merge into approval_record (instance_id, originator_user_id, dept_id, status, created_at, synced_at)
                key(instance_id) values (:instanceId, :userId, :deptId, 'RUNNING', :createdAt, :syncedAt)
                """).param("instanceId", instanceId).param("userId", userId).param("deptId", deptId)
                .param("createdAt", now).param("syncedAt", now).update();
        return findOwned(instanceId, userId);
    }

    public ApprovalRecord importAndSync(String instanceId, String userId) {
        DingTalkApprovalService.ApprovalDetail detail = dingTalk.getApprovalDetail(instanceId);
        if (!userId.equals(detail.originatorUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "该审批单不是当前登录用户发起的。");
        }
        upsertDetail(instanceId, detail);
        return findOwned(instanceId, userId);
    }

    public ApprovalRecord sync(String instanceId, String userId) {
        findOwned(instanceId, userId);
        DingTalkApprovalService.ApprovalDetail detail = dingTalk.getApprovalDetail(instanceId);
        if (!userId.equals(detail.originatorUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "审批单发起人与本地记录不一致。");
        }
        upsertDetail(instanceId, detail);
        return findOwned(instanceId, userId);
    }

    public void syncFromEvent(String eventId, String eventType, String instanceId) {
        if (eventId == null || eventId.isBlank() || instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("事件 ID 和审批实例 ID 不能为空。");
        }
        boolean handled = jdbc.sql("select count(*) from dingtalk_event_receipt where event_id=:eventId")
                .param("eventId", eventId).query(Long.class).single() > 0;
        if (handled) return;

        DingTalkApprovalService.ApprovalDetail detail = dingTalk.getApprovalDetail(instanceId);
        upsertDetail(instanceId, detail);
        jdbc.sql("insert into dingtalk_event_receipt(event_id,event_type,instance_id,received_at) "
                + "values (:eventId,:eventType,:instanceId,:receivedAt)")
                .param("eventId", eventId).param("eventType", eventType).param("instanceId", instanceId)
                .param("receivedAt", OffsetDateTime.now(ZoneOffset.UTC)).update();
    }

    public List<ApprovalRecord> list(String userId) {
        return jdbc.sql("select * from approval_record where originator_user_id=:userId order by created_at desc")
                .param("userId", userId).query(ApprovalRecord.class).list();
    }

    public List<ApprovalRecord> listAll() {
        return jdbc.sql("select * from approval_record order by created_at desc")
                .query(ApprovalRecord.class).list();
    }

    public Map<String, Long> statistics(String userId) {
        return statisticsFor(list(userId));
    }

    public Map<String, Long> statisticsAll() {
        return statisticsFor(listAll());
    }

    public List<ApprovalRecord> listForApprover(String userId) {
        return jdbc.sql("""
                select r.* from approval_record r
                where exists (select 1 from approval_participant p
                    where p.instance_id=r.instance_id and p.user_id=:userId)
                order by r.created_at desc
                """).param("userId", userId).query(ApprovalRecord.class).list();
    }

    public Map<String, Long> statisticsForApprover(String userId) {
        return statisticsFor(listForApprover(userId));
    }

    public ApprovalRecord syncAsManager(String instanceId) {
        find(instanceId);
        DingTalkApprovalService.ApprovalDetail detail = dingTalk.getApprovalDetail(instanceId);
        upsertDetail(instanceId, detail);
        return find(instanceId);
    }

    public ApprovalRecord importAndSyncAsManager(String instanceId) {
        DingTalkApprovalService.ApprovalDetail detail = dingTalk.getApprovalDetail(instanceId);
        upsertDetail(instanceId, detail);
        return find(instanceId);
    }

    public ApprovalRecord syncAsApprover(String instanceId, String userId) {
        DingTalkApprovalService.ApprovalDetail detail = dingTalk.getApprovalDetail(instanceId);
        requireApprover(detail, userId);
        upsertDetail(instanceId, detail);
        return find(instanceId);
    }

    public ApprovalRecord importAndSyncAsApprover(String instanceId, String userId) {
        return syncAsApprover(instanceId, userId);
    }

    private Map<String, Long> statisticsFor(List<ApprovalRecord> records) {
        return Map.of(
                "total", (long) records.size(),
                "approved", count(records, "APPROVED"),
                "rejected", count(records, "REJECTED"),
                "running", count(records, "RUNNING"),
                "terminated", count(records, "TERMINATED"));
    }

    private ApprovalRecord find(String instanceId) {
        return jdbc.sql("select * from approval_record where instance_id=:instanceId")
                .param("instanceId", instanceId).query(ApprovalRecord.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到该审批单。"));
    }

    private long count(List<ApprovalRecord> records, String state) {
        return records.stream().filter(record -> state.equals(record.status())).count();
    }

    private void upsertDetail(String instanceId, DingTalkApprovalService.ApprovalDetail detail) {
        String localStatus = normalize(detail.status(), detail.result());
        OffsetDateTime created = parse(detail.createTime(), OffsetDateTime.now(ZoneOffset.UTC));
        OffsetDateTime finished = parse(detail.finishTime(), null);
        Long deptId = parseLong(detail.originatorDeptId());
        jdbc.sql("""
                merge into approval_record (instance_id, originator_user_id, dept_id, business_id, title,
                    status, result, created_at, finished_at, synced_at)
                key(instance_id) values (:instanceId, :userId, :deptId, :businessId, :title,
                    :status, :result, :createdAt, :finishedAt, :syncedAt)
                """).param("instanceId", instanceId).param("userId", detail.originatorUserId())
                .param("deptId", deptId).param("businessId", detail.businessId()).param("title", detail.title())
                .param("status", localStatus).param("result", detail.result()).param("createdAt", created)
                .param("finishedAt", finished).param("syncedAt", OffsetDateTime.now(ZoneOffset.UTC)).update();
        replaceParticipants(instanceId, detail);
    }

    private void replaceParticipants(String instanceId, DingTalkApprovalService.ApprovalDetail detail) {
        var userIds = new LinkedHashSet<String>();
        if (detail.approverUserIds() != null) detail.approverUserIds().stream()
                .filter(id -> id != null && !id.isBlank()).forEach(userIds::add);
        if (detail.tasks() != null) detail.tasks().stream().filter(java.util.Objects::nonNull)
                .map(DingTalkApprovalService.ApprovalTask::userId)
                .filter(id -> id != null && !id.isBlank()).forEach(userIds::add);
        jdbc.sql("delete from approval_participant where instance_id=:instanceId")
                .param("instanceId", instanceId).update();
        for (String userId : userIds) {
            jdbc.sql("insert into approval_participant(instance_id,user_id) values (:instanceId,:userId)")
                    .param("instanceId", instanceId).param("userId", userId).update();
        }
    }

    private void requireApprover(DingTalkApprovalService.ApprovalDetail detail, String userId) {
        boolean allowed = (detail.approverUserIds() != null && detail.approverUserIds().contains(userId))
                || (detail.tasks() != null && detail.tasks().stream().filter(java.util.Objects::nonNull)
                        .anyMatch(task -> userId.equals(task.userId())));
        if (!allowed) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "你不是该审批单的审批人。");
    }

    static String normalize(String status, String result) {
        String normalizedResult = result == null ? "" : result.toLowerCase(Locale.ROOT);
        if (normalizedResult.equals("agree")) return "APPROVED";
        if (normalizedResult.equals("refuse")) return "REJECTED";
        String normalizedStatus = status == null ? "" : status.toLowerCase(Locale.ROOT);
        if (normalizedStatus.contains("terminate") || normalizedStatus.contains("cancel")) return "TERMINATED";
        return "RUNNING";
    }

    private ApprovalRecord findOwned(String instanceId, String userId) {
        return jdbc.sql("select * from approval_record where instance_id=:instanceId and originator_user_id=:userId")
                .param("instanceId", instanceId).param("userId", userId).query(ApprovalRecord.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到该审批单。"));
    }

    private OffsetDateTime parse(String value, OffsetDateTime fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return OffsetDateTime.parse(value); } catch (RuntimeException ignored) { return fallback; }
    }

    private Long parseLong(String value) {
        try { return value == null ? null : Long.valueOf(value); } catch (NumberFormatException ignored) { return null; }
    }

    public record ApprovalRecord(String instanceId, String originatorUserId, Long deptId, String businessId,
            String title, String status, String result, OffsetDateTime createdAt, OffsetDateTime finishedAt,
            OffsetDateTime syncedAt) {}
}
