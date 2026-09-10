package com.ewe.dingtalk_approval;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalAuthorityServiceTests {

    private ApprovalAuthorityService authority;

    @BeforeEach
    void setUp() {
        authority = new ApprovalAuthorityService();
        ReflectionTestUtils.setField(authority, "managerUserIds", "manager-1");
        ReflectionTestUtils.setField(authority, "managerNames", "Vincent Wang");
        ReflectionTestUtils.setField(authority, "developerUserIds", "dev-1");
        ReflectionTestUtils.setField(authority, "approverUserIds", "");
        ReflectionTestUtils.setField(authority, "managerDeptIds", "");
        ReflectionTestUtils.setField(authority, "managerScope", "");
    }

    @Test
    void resolvesRoleFromMostPrivilegedToLeast() {
        assertEquals(ApprovalAuthorityService.ApprovalRole.DEVELOPER, authority.roleOf("dev-1", "谁"));
        assertEquals(ApprovalAuthorityService.ApprovalRole.MANAGER, authority.roleOf("manager-1", "谁"));
        assertEquals(ApprovalAuthorityService.ApprovalRole.MANAGER, authority.roleOf("someone", "Vincent Wang"));
        assertEquals(ApprovalAuthorityService.ApprovalRole.EMPLOYEE, authority.roleOf("someone", "Jack"));
    }

    @Test
    void onlyManagersAndDevelopersCanViewStatistics() {
        assertTrue(authority.canViewStatistics("dev-1", "Jack"));
        assertTrue(authority.canViewStatistics("manager-1", "Jack"));
        assertFalse(authority.canViewStatistics("someone", "Jack"));
    }

    @Test
    void defaultScopeIsInvolvedToAvoidWideningAccess() {
        // 未配置部门与范围时，主管只能看到与自己相关的数据，保持与旧版本一致。
        assertEquals(ApprovalAuthorityService.ManagerScope.INVOLVED, authority.effectiveManagerScope("manager-1", "Jack"));
        assertFalse(authority.hasCompanyWideScope("manager-1", "Jack"));
    }

    @Test
    void developerAlwaysHasCompanyWideScope() {
        assertEquals(ApprovalAuthorityService.ManagerScope.ALL, authority.effectiveManagerScope("dev-1", "Jack"));
        assertTrue(authority.hasCompanyWideScope("dev-1", "Jack"));
    }

    @Test
    void configuringDepartmentIdsSwitchesScopeToDepartment() {
        ReflectionTestUtils.setField(authority, "managerDeptIds", "42, 83 ,bad");
        assertEquals(ApprovalAuthorityService.ManagerScope.DEPARTMENT, authority.managerScope());
        assertEquals(java.util.Set.of(42L, 83L), authority.managerDepartmentScope());
        assertTrue(authority.canViewDepartment("manager-1", "Jack", 42L));
        assertFalse(authority.canViewDepartment("manager-1", "Jack", 99L));
        assertFalse(authority.canViewDepartment("manager-1", "Jack", null));
    }

    @Test
    void explicitAllScopeLetsManagerSeeEveryDepartment() {
        ReflectionTestUtils.setField(authority, "managerScope", "all");
        assertTrue(authority.hasCompanyWideScope("manager-1", "Jack"));
        assertTrue(authority.canViewDepartment("manager-1", "Jack", 999L));
    }

    @Test
    void unknownScopeValueFallsBackToInferredDefault() {
        ReflectionTestUtils.setField(authority, "managerScope", "SOMETHING");
        assertEquals(ApprovalAuthorityService.ManagerScope.INVOLVED, authority.managerScope());
    }

    @Test
    void emptyApproverAllowlistAcceptsEveryone() {
        assertTrue(authority.isApprover("anyone"));
        assertTrue(authority.isApprover(null));
    }

    @Test
    void configuredApproverAllowlistRestrictsAccess() {
        ReflectionTestUtils.setField(authority, "approverUserIds", "a, b");
        assertTrue(authority.isApprover("a"));
        assertFalse(authority.isApprover("c"));
    }

    @Test
    void explicitApproverGetsApproverRoleButStillNoStatisticsAccess() {
        ReflectionTestUtils.setField(authority, "approverUserIds", "a");
        assertEquals(ApprovalAuthorityService.ApprovalRole.APPROVER, authority.roleOf("a", "甲"));
        assertFalse(authority.canViewStatistics("a", "甲"), "审批人不应默认拥有审批概览权限");
    }

    @Test
    void configParsingIgnoresBlankAndInvalidEntries() {
        assertEquals(List.of("a", "b"), ApprovalAuthorityService.parseList(" a , ,b "));
        assertEquals(List.of(), ApprovalAuthorityService.parseList("  "));
        assertEquals(List.of(), ApprovalAuthorityService.parseList(null));
    }
}
