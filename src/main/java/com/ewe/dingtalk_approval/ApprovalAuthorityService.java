package com.ewe.dingtalk_approval;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 审批相关权限的唯一判定入口。
 *
 * <p>把原先散落在 Controller 里的字符串比对集中到这里，便于统一维护与测试。
 * 权限分四层：</p>
 * <ul>
 *   <li>{@link ApprovalRole#EMPLOYEE}：普通员工，只能看自己发起的审批；</li>
 *   <li>{@link ApprovalRole#APPROVER}：审批人，可进入“我的待办”处理钉钉分配给自己的任务；</li>
 *   <li>{@link ApprovalRole#MANAGER}：主管，可查看审批概览，必要时按 {@code dingtalk.manager-dept-ids} 限定部门；</li>
 *   <li>{@link ApprovalRole#DEVELOPER}：开发/管理员，可查看全量数据，用于交接期排障。</li>
 * </ul>
 *
 * <p>注意：一个人是否可以“处理”某张审批单，最终由钉钉流程决定——只有钉钉把任务派给他，
 * 他才会在实例详情里看到属于自己的待办任务。本服务只控制界面入口与统计数据的可见范围。</p>
 */
@Service
public class ApprovalAuthorityService {

    /** 审批系统内的角色层级，数值越大权限越高。 */
    public enum ApprovalRole {
        EMPLOYEE, APPROVER, MANAGER, DEVELOPER
    }

    /**
     * 主管（MANAGER）在审批概览中能看到的数据范围。
     */
    public enum ManagerScope {
        /** 只看与自己相关（自己发起或自己参与审批）的单据，默认值，权限最小。 */
        INVOLVED,
        /** 只看 {@code dingtalk.manager-dept-ids} 指定部门的数据。 */
        DEPARTMENT,
        /** 查看全部数据，对应需求中的“主管可以处理所有审批单”。 */
        ALL
    }

    @Value("${dingtalk.manager-user-ids:}")
    private String managerUserIds = "";

    @Value("${dingtalk.manager-names:Vincent Wang}")
    private String managerNames = "Vincent Wang";

    @Value("${dingtalk.developer-user-ids:235638251739-1704317153}")
    private String developerUserIds = "235638251739-1704317153";

    @Value("${dingtalk.approver-user-ids:}")
    private String approverUserIds = "";

    @Value("${dingtalk.manager-dept-ids:}")
    private String managerDeptIds = "";

    @Value("${dingtalk.manager-scope:}")
    private String managerScope = "";

    /**
     * 解析用户在系统内的角色层级：开发者 &gt; 主管 &gt; 审批人 &gt; 普通员工。
     *
     * <p>这里只反映配置中显式授予的身份。未配置审批人白名单时，
     * 任何人都能打开“我的待办”（见 {@link #isApprover}），但角色仍按普通员工处理，
     * 避免把“未限制”误读成“已授权”。</p>
     */
    public ApprovalRole roleOf(String userId, String nick) {
        if (isDeveloper(userId)) return ApprovalRole.DEVELOPER;
        if (isManager(userId, nick)) return ApprovalRole.MANAGER;
        if (contains(approverUserIds, userId)) return ApprovalRole.APPROVER;
        return ApprovalRole.EMPLOYEE;
    }

    /** 是否为配置在开发者名单中的用户。 */
    public boolean isDeveloper(String userId) {
        return contains(developerUserIds, userId);
    }

    /** 是否为主管：命中 userId 名单或钉钉昵称名单。 */
    public boolean isManager(String userId, String nick) {
        return contains(managerUserIds, userId) || contains(managerNames, nick);
    }

    /**
     * 是否允许进入“我的待办”。
     *
     * <p>未配置白名单时对所有登录用户开放——钉钉只会返回属于他自己的任务，因此不构成越权；
     * 配置白名单后则只有名单内的用户可以处理审批。</p>
     */
    public boolean isApprover(String userId) {
        return approverUserIds == null || approverUserIds.isBlank() || contains(approverUserIds, userId);
    }

    /** 是否可以查看审批概览页面。仅主管与开发者可见。 */
    public boolean canViewStatistics(String userId, String nick) {
        return isDeveloper(userId) || isManager(userId, nick);
    }

    /**
     * 经理的部门范围。返回空集合表示“不限部门”。
     */
    public Set<Long> managerDepartmentScope() {
        return parseLongs(managerDeptIds);
    }

    /**
     * 解析主管的数据可见范围。
     *
     * <p>未显式配置 {@code dingtalk.manager-scope} 时按最小权限处理：
     * 配置了部门范围则按部门，否则只显示与自己相关的数据，
     * 保持与既有版本一致的行为，避免升级后意外扩大可见范围。</p>
     */
    public ManagerScope managerScope() {
        if (managerScope != null && !managerScope.isBlank()) {
            String value = managerScope.trim().toUpperCase(java.util.Locale.ROOT);
            for (ManagerScope candidate : ManagerScope.values()) {
                if (candidate.name().equals(value)) return candidate;
            }
        }
        return managerDepartmentScope().isEmpty() ? ManagerScope.INVOLVED : ManagerScope.DEPARTMENT;
    }

    /**
     * 按配置解析出的主管数据范围：开发者始终为全部数据。
     */
    public ManagerScope effectiveManagerScope(String userId, String nick) {
        if (isDeveloper(userId)) return ManagerScope.ALL;
        if (!isManager(userId, nick)) return ManagerScope.INVOLVED;
        return managerScope();
    }

    /**
     * 在给定部门范围内，当前用户是否可以查看全部数据（不做部门过滤）。
     */
    public boolean hasCompanyWideScope(String userId, String nick) {
        return effectiveManagerScope(userId, nick) == ManagerScope.ALL;
    }

    /**
     * 当前用户是否有权查看指定部门的审批数据，用于部门层级的访问控制。
     */
    public boolean canViewDepartment(String userId, String nick, Long deptId) {
        return switch (effectiveManagerScope(userId, nick)) {
            case ALL -> true;
            case DEPARTMENT -> deptId != null && managerDepartmentScope().contains(deptId);
            case INVOLVED -> false;
        };
    }

    /**
     * 把逗号分隔的配置值解析成去重集合。空白项会被忽略。
     */
    static Set<Long> parseLongs(String configuredValues) {
        Set<Long> values = new LinkedHashSet<>();
        if (configuredValues == null || configuredValues.isBlank()) return values;
        for (String item : configuredValues.split(",")) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) continue;
            try {
                values.add(Long.valueOf(trimmed));
            } catch (NumberFormatException ignored) {
                // 配置写错时忽略该条目，避免整个统计页不可用。
            }
        }
        return values;
    }

    /** 把逗号分隔的配置值解析成列表。 */
    static List<String> parseList(String configuredValues) {
        if (configuredValues == null || configuredValues.isBlank()) return List.of();
        return Arrays.stream(configuredValues.split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList();
    }

    private boolean contains(String configuredValues, String actual) {
        if (actual == null || actual.isBlank()) return false;
        return parseList(configuredValues).stream().anyMatch(actual::equals);
    }
}
