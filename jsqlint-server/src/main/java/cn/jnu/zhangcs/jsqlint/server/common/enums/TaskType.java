package cn.jnu.zhangcs.jsqlint.server.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 任务类型枚举
 *
 * @author zhangcs
 */
@Getter
public enum TaskType {

    SYNC(0, "同步任务"),

    COMPILE(1, "编译任务"),

    ANALYSE(2, "分析任务"),

    AUDIT(3, "审核任务"),

    REPORT(4, "报告生成任务"),

    INSPECT(5, "巡检任务"),

    ALERT(6, "告警任务"),

    EXECUTE(7, "执行任务"),

    /**
     * 不支持的类型
     */
    UN_SUPPORT(-1, "不支持"),
    ;

    @EnumValue
    private final int code;

    private final String desc;

    TaskType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static TaskType from(int code) {
        for (TaskType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return UN_SUPPORT;
    }

}
