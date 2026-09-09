package cn.jnu.zhangcs.jsqlint.analysis.parameter.trace;

import java.util.List;
import java.util.Objects;

/**
 * 解析结果：取值模型及其流转步骤。
 *
 * @param value 取值模型
 * @param flow  从使用点到定义点的流转步骤
 */
record ResolvedValue(ValueModel value, List<ValueFlowStep> flow) {

    ResolvedValue {
        Objects.requireNonNull(value, "value");
        flow = flow == null ? List.of() : List.copyOf(flow);
    }

    /**
     * 创建不带流转步骤的解析结果。
     *
     * @param value 取值模型
     * @return 解析结果
     */
    static ResolvedValue of(ValueModel value) {
        return new ResolvedValue(value, List.of());
    }
}
