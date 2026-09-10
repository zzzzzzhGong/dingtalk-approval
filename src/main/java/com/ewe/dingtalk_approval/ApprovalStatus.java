package com.ewe.dingtalk_approval;

import java.util.Locale;

/**
 * 审批单在本地库中的标准状态。
 *
 * <p>钉钉审批实例详情接口会同时返回 {@code status} 与 {@code result} 两个字段，
 * 两者的组合才能唯一确定业务语义（例如 COMPLETED + agree = 已通过）。
 * 本枚举负责把这组原始值归一化成系统内部统一的四种状态，供统计、通知与前端展示使用。</p>
 */
public enum ApprovalStatus {

    /** 审批中：钉钉尚未结束流程，或结果字段仍为 NONE。 */
    RUNNING,

    /** 已通过。 */
    APPROVED,

    /** 已拒绝。 */
    REJECTED,

    /** 已撤销或被终止。 */
    TERMINATED;

    /**
     * 把钉钉返回的 status / result 归一化为本地状态。
     *
     * <p>判定顺序很重要：钉钉在流程结束时会把 status 置为 COMPLETED，
     * 但同意与拒绝都会是 COMPLETED，因此必须先看 result，再看 status。</p>
     *
     * @param status 钉钉实例状态，可能为 null
     * @param result 钉钉审批结果，可能为 null
     * @return 归一化后的本地状态，无法识别时按“审批中”处理
     */
    public static ApprovalStatus fromDingTalk(String status, String result) {
        String normalizedResult = normalize(result);
        if ("agree".equals(normalizedResult)) {
            return APPROVED;
        }
        if ("refuse".equals(normalizedResult)) {
            return REJECTED;
        }
        String normalizedStatus = normalize(status);
        if (normalizedStatus.contains("terminate") || normalizedStatus.contains("cancel")) {
            return TERMINATED;
        }
        return RUNNING;
    }

    /**
     * 解析本地库中的状态字符串，无法识别时按“审批中”处理，避免历史脏数据导致查询失败。
     *
     * @param value 本地状态字符串
     * @return 对应枚举值
     */
    public static ApprovalStatus parse(String value) {
        if (value == null || value.isBlank()) {
            return RUNNING;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return RUNNING;
        }
    }

    /** 是否为终态（已通过 / 已拒绝 / 已撤销）。终态单据不需要再向钉钉同步。 */
    public boolean isFinal() {
        return this != RUNNING;
    }

    /** 供前端直接显示的中文状态名。 */
    public String label() {
        return switch (this) {
            case APPROVED -> "已通过";
            case REJECTED -> "已拒绝";
            case TERMINATED -> "已撤销/终止";
            case RUNNING -> "审批中";
        };
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }
}
