package com.ewe.dingtalk_approval;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ApprovalRecordServiceTests {
    @Test void mapsInstanceResultsToStatisticsStates() {
        assertEquals("APPROVED", ApprovalRecordService.normalize("COMPLETED", "agree"));
        assertEquals("REJECTED", ApprovalRecordService.normalize("COMPLETED", "refuse"));
        assertEquals("TERMINATED", ApprovalRecordService.normalize("TERMINATED", null));
        assertEquals("RUNNING", ApprovalRecordService.normalize("RUNNING", "NONE"));
    }
}
