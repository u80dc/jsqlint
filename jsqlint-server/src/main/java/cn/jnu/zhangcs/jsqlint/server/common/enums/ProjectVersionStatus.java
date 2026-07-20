package cn.jnu.zhangcs.jsqlint.server.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 项目版本状态
 *
 * @author zhangcs
 */
@Getter
public enum ProjectVersionStatus {

    NOT_STARTED(0, "未开始"),

    RUNNING(1, "执行中"),

    SUCCESS(2, "执行成功"),

    FAILED(3, "执行失败"),

    UN_SUPPORT(-1, "未知");

    /**
     * 状态码
     */
    @EnumValue
    private final int code;

    /**
     * 描述信息
     */
    private final String desc;

    ProjectVersionStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static ProjectVersionStatus from(Integer code) {
        if (code == null) {
            return ProjectVersionStatus.UN_SUPPORT;
        }
        for (ProjectVersionStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return ProjectVersionStatus.UN_SUPPORT;
    }

    public static boolean is(Integer code, ProjectVersionStatus status) {
        return code != null && from(code) == status;
    }

    public static boolean in(Integer code, ProjectVersionStatus... status) {
        if (code == null) {
            return false;
        }
        ProjectVersionStatus from = from(code);
        for (ProjectVersionStatus value : status) {
            if (from == value) {
                return true;
            }
        }
        return false;
    }

}
