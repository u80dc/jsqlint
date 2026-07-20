package cn.jnu.zhangcs.jsqlint.server.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 项目版本步骤状态
 *
 * @author zhangcs
 */
@Getter
public enum ProjectVersionStepStatus {

    NOT_STARTED(0, "未开始"),

    RUNNING(1, "执行中"),

    SUCCESS(2, "执行成功"),

    FAILED(3, "执行失败"),

    UN_SUPPORT(-1, "未知");

    @EnumValue
    private final int code;

    private final String desc;

    ProjectVersionStepStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static ProjectVersionStepStatus from(Integer code) {
        if (code == null) {
            return UN_SUPPORT;
        }
        for (ProjectVersionStepStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return UN_SUPPORT;
    }
}
