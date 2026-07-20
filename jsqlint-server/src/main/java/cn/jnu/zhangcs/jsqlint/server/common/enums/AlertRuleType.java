package cn.jnu.zhangcs.jsqlint.server.common.enums;

import lombok.Getter;

/**
 * 告警规则类型
 *
 * @author zhangcs
 */
@Getter
public enum AlertRuleType {

    AUDIT_ISSUE_COUNT("AUDIT_ISSUE_COUNT", "审核问题数"),

    HIGH_RISK_AUDIT_COUNT("HIGH_RISK_AUDIT_COUNT", "高危审核问题数"),

    TASK_FAILED("TASK_FAILED", "任务失败");

    private final String code;

    private final String desc;

    AlertRuleType(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static AlertRuleType from(String code) {
        for (AlertRuleType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return AUDIT_ISSUE_COUNT;
    }
}
