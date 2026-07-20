package cn.jnu.zhangcs.jsqlint.server.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 任务状态枚举
 *
 * @author zhangcs
 */
@Getter
public enum TaskStatus {

    PENDING(0, "待执行"),

    RUNNING(1, "执行中"),

    SUCCESS(2, "成功"),

    FAILED(3, "失败"),

    CANCELLED(4, "已取消"),

    RETRYING(5, "等待重试"),

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

    TaskStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据状态码获取任务状态枚举
     *
     * @param code 状态码
     * @return 返回状态枚举
     */
    public static TaskStatus from(int code) {
        for (TaskStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return TaskStatus.UN_SUPPORT;
    }

}
