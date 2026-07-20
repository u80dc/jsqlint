package cn.jnu.zhangcs.jsqlint.server.common.enums;

import lombok.Getter;

/**
 * 告警状态枚举
 *
 * @author zhangcs
 */
@Getter
public enum AlertStatus {

    OPEN("OPEN", "未处理"),

    ACKED("ACKED", "已确认"),

    RESOLVED("RESOLVED", "已解决"),

    IGNORED("IGNORED", "已忽略");

    private final String code;

    private final String desc;

    AlertStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static AlertStatus from(String code) {
        for (AlertStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return OPEN;
    }
}
