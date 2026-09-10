package com.ewe.dingtalk_approval;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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

    /**
     * 导入并同步一张钉钉审批单，要求当前用户就是发起人。
     * 先取详情再校验，避免非发起人触发写入。
     */
    public SyncResult importAsOriginator(String instanceId, String userId) {
        DingTalkApprovalService.ApprovalDetail detail = dingTalk.getApprovalDetail(instanceId);
        if (!userId.equals(detail.originatorUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "该审批单不是当前登录用户发起的。");
        }
        return applyDetail(instanceId, detail);
    }

    /**
     * 处理钉钉 Stream 事件：按 eventId 去重后刷新本地状态。
     *
     * @return 同步结果；事件此前已处理过时返回 null，调用方据此跳过重复通知
     */
    public SyncResult syncFromEvent(String eventId, String eventType, String instanceId) {
        if (eventId == null || eventId.isBlank() || instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("事件 ID 和审批实例 ID 不能为空。");
        }
        boolean handled = jdbc.sql("select count(*) from dingtalk_event_receipt where event_id=:eventId")
                .param("eventId", eventId).query(Long.class).single() > 0;
        if (handled) return null;

        ApprovalRecord before = findOrNull(instanceId);
        DingTalkApprovalService.ApprovalDetail detail = dingTalk.getApprovalDetail(instanceId);
        upsertDetail(instanceId, detail);
        jdbc.sql("insert into dingtalk_event_receipt(event_id,event_type,instance_id,received_at) "
                + "values (:eventId,:eventType,:instanceId,:receivedAt)")
                .param("eventId", eventId).param("eventType", eventType).param("instanceId", instanceId)
                .param("receivedAt", OffsetDateTime.now(ZoneOffset.UTC)).update();

        ApprovalRecord after = find(instanceId);
        boolean statusChanged = before != null && !before.status().equals(after.status());
        return new SyncResult(before, after, statusChanged);
    }

    public List<ApprovalRecord> list(String userId) {
        return jdbc.sql("select * from approval_record where originator_user_id=:userId order by created_at desc")
                .param("userId", userId).query(ApprovalRecord.class).list();
    }

    public List<ApprovalRecord> listAll() {
        return jdbc.sql("select * from approval_record order by created_at desc")
                .query(ApprovalRecord.class).list();
    }

    /** 按部门过滤的列表，用于部门层级权限下的经理视图。 */
    public List<ApprovalRecord> listByDepartments(List<Long> deptIds) {
        if (deptIds == null || deptIds.isEmpty()) return List.of();
        return jdbc.sql("select * from approval_record where dept_id in (:deptIds) order by created_at desc")
                .param("deptIds", deptIds).query(ApprovalRecord.class).list();
    }

    /** 找出仍是“审批中”的单据，供定时同步任务批量刷新。 */
    public List<ApprovalRecord> listRunning(int limit) {
        return jdbc.sql("""
                select * from approval_record
                where status='RUNNING'
                order by synced_at asc
                limit :limit
                """).param("limit", Math.max(1, limit)).query(ApprovalRecord.class).list();
    }

    /**
     * 查询与某个用户相关的“待办”单据：他发起的，或者他是审批参与人的审批中单据。
     *
     * <p>真正能否处理由钉钉返回的任务状态决定，本方法只负责缩小需要向钉钉查询的范围。</p>
     */
    public List<ApprovalRecord> listPendingForUser(String userId) {
        return jdbc.sql("""
                select * from approval_record r
                where r.status='RUNNING'
                  and (r.originator_user_id=:userId
                       or exists (select 1 from approval_participant p
                                  where p.instance_id=r.instance_id and p.user_id=:userId))
                order by r.created_at desc
                """).param("userId", userId).query(ApprovalRecord.class).list();
    }

    /** 查询审批实例的全部参与人（审批人 / 抄送人）。 */
    public List<String> participants(String instanceId) {
        return jdbc.sql("select user_id from approval_participant where instance_id=:instanceId")
                .param("instanceId", instanceId).query(String.class).list();
    }

    /**
     * 以数据库为锁，抢占某个状态的通知发送权。
     *
     * <p>定时同步、Stream 事件与手工同步可能同时观察到一次状态变化，
     * 通过带条件的 update 保证同一状态只通知一次。</p>
     *
     * @return true 表示本次调用抢到了通知权，应当继续发送
     */
    public boolean claimNotification(String instanceId, String status) {
        int updated = jdbc.sql("""
                update approval_record set notified_status=:status
                where instance_id=:instanceId
                  and (notified_status is null or notified_status<>:status)
                """).param("instanceId", instanceId).param("status", status).update();
        return updated > 0;
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

    /**
     * 以审批参与人的身份同步一张审批单：要求当前用户是该单的发起人或审批人。
     */
    public SyncResult syncAsApprover(String instanceId, String userId) {
        DingTalkApprovalService.ApprovalDetail detail = dingTalk.getApprovalDetail(instanceId);
        requireParticipant(detail, userId);
        return applyDetail(instanceId, detail);
    }

    /**
     * 判断用户是否在本地记录中与该审批单相关（发起人或参与人）。
     * 在钉钉详情暂时取不到时，用于做降级的可见性判断。
     */
    public boolean isLocalParticipant(String instanceId, String userId) {
        if (userId == null || userId.isBlank()) return false;
        long owned = jdbc.sql("select count(*) from approval_record where instance_id=:instanceId and originator_user_id=:userId")
                .param("instanceId", instanceId).param("userId", userId).query(Long.class).single();
        return owned > 0 || participants(instanceId).contains(userId);
    }

    private Map<String, Long> statisticsFor(List<ApprovalRecord> records) {
        return Map.of(
                "total", (long) records.size(),
                "approved", count(records, "APPROVED"),
                "rejected", count(records, "REJECTED"),
                "running", count(records, "RUNNING"),
                "terminated", count(records, "TERMINATED"));
    }

    public Map<String, Long> statisticsForDepartments(List<Long> deptIds) {
        return statisticsFor(listByDepartments(deptIds));
    }

    /**
     * 从钉钉拉取最新状态并写回本地，返回同步前后的对比结果。
     *
     * <p>调用方（定时任务 / Stream 监听 / 手工同步）可据此判断是否需要推送通知。</p>
     */
    public SyncResult syncInstance(String instanceId) {
        return applyDetail(instanceId, dingTalk.getApprovalDetail(instanceId));
    }

    /**
     * 把一份已经取到的钉钉详情写回本地，避免重复调用钉钉接口。
     *
     * @param instanceId 审批实例 ID
     * @param detail     钉钉审批实例详情
     * @return 同步前后的对比结果
     */
    public SyncResult applyDetail(String instanceId, DingTalkApprovalService.ApprovalDetail detail) {
        ApprovalRecord before = findOrNull(instanceId);
        upsertDetail(instanceId, detail);
        ApprovalRecord after = find(instanceId);
        boolean statusChanged = before != null && !before.status().equals(after.status());
        return new SyncResult(before, after, statusChanged);
    }

    /** 按主键查询，找不到抛出 404。 */
    public ApprovalRecord find(String instanceId) {
        return jdbc.sql("select * from approval_record where instance_id=:instanceId")
                .param("instanceId", instanceId).query(ApprovalRecord.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到该审批单。"));
    }

    /** 按主键查询，找不到返回 null（用于同步前判断记录是否已存在）。 */
    public ApprovalRecord findOrNull(String instanceId) {
        return jdbc.sql("select * from approval_record where instance_id=:instanceId")
                .param("instanceId", instanceId).query(ApprovalRecord.class).optional().orElse(null);
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

    /**
     * 校验用户是该审批单的发起人或审批参与人，否则抛出 403。
     */
    private void requireParticipant(DingTalkApprovalService.ApprovalDetail detail, String userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "你不是该审批单的发起人或审批人。");
        }
        boolean originator = userId.equals(detail.originatorUserId());
        boolean approver = (detail.approverUserIds() != null && detail.approverUserIds().contains(userId))
                || (detail.tasks() != null && detail.tasks().stream().filter(java.util.Objects::nonNull)
                        .anyMatch(task -> userId.equals(task.userId())));
        if (!originator && !approver) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "你不是该审批单的发起人或审批人。");
        }
    }

    /**
     * 把钉钉的 status / result 归一化为本地统计状态。
     * 保留该静态方法是为了兼容既有调用与测试，实际逻辑见 {@link ApprovalStatus}。
     */
    static String normalize(String status, String result) {
        return ApprovalStatus.fromDingTalk(status, result).name();
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

    /**
     * 一次同步的结果对比。
     *
     * @param before        同步前的本地记录，首次导入时为 null
     * @param after         同步后的本地记录
     * @param statusChanged 状态是否发生变化（首次导入不算变化，避免无意义的通知）
     */
    public record SyncResult(ApprovalRecord before, ApprovalRecord after, boolean statusChanged) {
        /** 当前状态枚举。 */
        public ApprovalStatus currentStatus() {
            return ApprovalStatus.parse(after.status());
        }
    }
}
