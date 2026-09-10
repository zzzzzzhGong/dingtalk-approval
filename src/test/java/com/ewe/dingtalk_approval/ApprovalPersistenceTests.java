package com.ewe.dingtalk_approval;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 持久化层集成测试：验证新增的审计表、通知表与通知去重列在真实 H2 上可正常使用。
 *
 * <p>这些 DDL 由 {@code schema.sql} 在应用启动时执行，一旦写错会导致应用无法启动，
 * 因此这里用真实的 Spring 上下文覆盖。</p>
 */
@SpringBootTest(properties = {
    "dingtalk.client-id=test-client", "dingtalk.client-secret=test-secret",
    "dingtalk.process-code=test-template", "dingtalk.redirect-uri=http://localhost/callback",
    "spring.datasource.url=jdbc:h2:mem:dingtalk-persistence;DB_CLOSE_DELAY=-1",
    "dingtalk.sync.enabled=false", "dingtalk.notify.enabled=false"
})
class ApprovalPersistenceTests {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ApprovalRecordService records;

    @Test
    void schemaCreatesDecisionAndNotificationTables() {
        assertTrue(tableExists("APPROVAL_DECISION"), "approval_decision 表应由 schema.sql 创建");
        assertTrue(tableExists("APPROVAL_NOTIFICATION"), "approval_notification 表应由 schema.sql 创建");
        assertTrue(columnExists("APPROVAL_RECORD", "NOTIFIED_STATUS"), "approval_record 应有 notified_status 列");
    }

    @Test
    void notificationIsClaimedOnlyOncePerStatus() {
        String instanceId = "instance-claim-test";
        insertRunningRecord(instanceId);

        assertTrue(records.claimNotification(instanceId, "APPROVED"), "首次抢占用应成功");
        assertFalse(records.claimNotification(instanceId, "APPROVED"), "同一状态不应重复推送");
        assertTrue(records.claimNotification(instanceId, "REJECTED"), "状态变化后应允许再次推送");
    }

    @Test
    void runningRecordsAreScannedForScheduledSync() {
        String instanceId = "instance-running-test";
        insertRunningRecord(instanceId);

        List<ApprovalRecordService.ApprovalRecord> running = records.listRunning(50);

        assertTrue(running.stream().anyMatch(record -> instanceId.equals(record.instanceId())));
    }

    @Test
    void terminalRecordsAreNotScannedAgain() {
        String instanceId = "instance-final-test";
        insertRunningRecord(instanceId);
        jdbc.sql("update approval_record set status='APPROVED' where instance_id=:instanceId")
                .param("instanceId", instanceId).update();

        List<ApprovalRecordService.ApprovalRecord> running = records.listRunning(50);

        assertFalse(running.stream().anyMatch(record -> instanceId.equals(record.instanceId())),
                "终态单据不应再参与定时同步");
    }

    private void insertRunningRecord(String instanceId) {
        jdbc.sql("""
                merge into approval_record (instance_id, originator_user_id, dept_id, status, created_at, synced_at)
                key(instance_id) values (:instanceId, 'user-a', 42, 'RUNNING', :now, :now)
                """).param("instanceId", instanceId)
                .param("now", OffsetDateTime.now(ZoneOffset.UTC)).update();
    }

    private boolean tableExists(String tableName) {
        return jdbc.sql("select count(*) from information_schema.tables where table_name=:name")
                .param("name", tableName).query(Long.class).single() > 0;
    }

    private boolean columnExists(String tableName, String columnName) {
        return jdbc.sql("""
                select count(*) from information_schema.columns
                where table_name=:table and column_name=:column
                """).param("table", tableName).param("column", columnName)
                .query(Long.class).single() > 0;
    }
}
