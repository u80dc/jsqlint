package cn.jnu.zhangcs.jsqlint.server.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

import java.util.List;

/**
 * 项目版本步骤类型
 *
 * @author zhangcs
 */
@Getter
public enum ProjectVersionStepType {

    SYNC_CODE(0, "同步代码", 1),

    COMPILE_CHECK(1, "编译检查", 2),

    SQL_SCAN(2, "SQL扫描", 3),

    RULE_AUDIT(3, "规则审核", 4),

    GENERATE_REPORT(4, "生成报告", 5),

    UN_SUPPORT(-1, "未知", 99);

    public static final List<ProjectVersionStepType> PROJECT_STEPS = List.of(
            SYNC_CODE,
            COMPILE_CHECK,
            SQL_SCAN,
            RULE_AUDIT,
            GENERATE_REPORT
    );

    @EnumValue
    private final int code;

    private final String desc;

    private final int stepOrder;

    ProjectVersionStepType(int code, String desc, int stepOrder) {
        this.code = code;
        this.desc = desc;
        this.stepOrder = stepOrder;
    }

    public static ProjectVersionStepType from(Integer code) {
        if (code == null) {
            return UN_SUPPORT;
        }
        for (ProjectVersionStepType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return UN_SUPPORT;
    }
}
