package cn.jnu.zhangcs.jsqlint.server.common.enums;

import lombok.Getter;

/**
 * 审核问题处理状态枚举
 *
 * <p>状态机：OPEN →（确认）CONFIRMED →（修复）RESOLVED；
 * OPEN/CONFIRMED →（豁免）DISMISSED；终态前的任意状态可重新打开回 OPEN。</p>
 *
 * @author zhangcs
 */
@Getter
public enum IssueStatus {

    OPEN("OPEN", "待处理"),

    CONFIRMED("CONFIRMED", "已确认"),

    RESOLVED("RESOLVED", "已修复"),

    DISMISSED("DISMISSED", "已豁免");

    private final String code;

    private final String desc;

    IssueStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static IssueStatus from(String code) {
        for (IssueStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return OPEN;
    }

    /**
     * 校验状态流转是否合法。
     *
     * <ul>
     *   <li>OPEN → CONFIRMED / DISMISSED</li>
     *   <li>CONFIRMED → RESOLVED / DISMISSED / OPEN（重新打开）</li>
     *   <li>RESOLVED / DISMISSED → OPEN（重新打开，SQL 再现时人工复核）</li>
     * </ul>
     */
    public static boolean canTransit(IssueStatus from, IssueStatus to) {
        if (from == to) {
            return false;
        }
        return switch (from) {
            case OPEN -> to == CONFIRMED || to == DISMISSED;
            case CONFIRMED -> to == RESOLVED || to == DISMISSED || to == OPEN;
            case RESOLVED, DISMISSED -> to == OPEN;
        };
    }
}
