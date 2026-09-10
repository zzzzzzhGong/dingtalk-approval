package com.ewe.dingtalk_approval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalStatusTests {

    @Test
    void mapsDingTalkResultBeforeStatus() {
        // 钉钉同意与拒绝都会把 status 置为 COMPLETED，必须先看 result。
        assertEquals(ApprovalStatus.APPROVED, ApprovalStatus.fromDingTalk("COMPLETED", "agree"));
        assertEquals(ApprovalStatus.REJECTED, ApprovalStatus.fromDingTalk("COMPLETED", "refuse"));
        assertEquals(ApprovalStatus.RUNNING, ApprovalStatus.fromDingTalk("RUNNING", "NONE"));
    }

    @Test
    void recognisesTerminatedAndCancelled() {
        assertEquals(ApprovalStatus.TERMINATED, ApprovalStatus.fromDingTalk("TERMINATED", null));
        assertEquals(ApprovalStatus.TERMINATED, ApprovalStatus.fromDingTalk("CANCELED", "NONE"));
    }

    @Test
    void ignoresCaseAndPadding() {
        assertEquals(ApprovalStatus.APPROVED, ApprovalStatus.fromDingTalk(" COMPLETED ", "AGREE"));
    }

    @Test
    void unknownValuesFallBackToRunning() {
        assertEquals(ApprovalStatus.RUNNING, ApprovalStatus.fromDingTalk(null, null));
        assertEquals(ApprovalStatus.RUNNING, ApprovalStatus.fromDingTalk("SOMETHING_NEW", "WHATEVER"));
    }

    @Test
    void parseToleratesDirtyData() {
        assertEquals(ApprovalStatus.APPROVED, ApprovalStatus.parse("approved"));
        assertEquals(ApprovalStatus.RUNNING, ApprovalStatus.parse(null));
        assertEquals(ApprovalStatus.RUNNING, ApprovalStatus.parse("不存在的状态"));
    }

    @Test
    void onlyTerminalStatesAreFinal() {
        assertFalse(ApprovalStatus.RUNNING.isFinal());
        assertTrue(ApprovalStatus.APPROVED.isFinal());
        assertTrue(ApprovalStatus.REJECTED.isFinal());
        assertTrue(ApprovalStatus.TERMINATED.isFinal());
    }
}
