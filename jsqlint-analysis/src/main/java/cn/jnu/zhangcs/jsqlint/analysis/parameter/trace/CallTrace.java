package cn.jnu.zhangcs.jsqlint.analysis.parameter.trace;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 一条完整的参数调用链。
 *
 * <p>该对象描述从目标方法参数所在调用点开始，沿着调用者参数依赖不断向上追踪后
 * 得到的一条链路。链路中的每个 {@link TraceStep} 表示一个具体调用点，
 * {@code rootValue} 表示当前链路最终追踪到的根值，可能是常量、方法参数、
 * 数组值、方法调用值或未知值。
 *
 * @param steps     调用链步骤，按“目标调用点 -> 上层调用点”的顺序排列
 * @param rootValue 当前链路最终追踪到的根值
 * @author zhangcs
 */
public record CallTrace(
        List<TraceStep> steps,
        ValueModel rootValue) {

    /**
     * 创建调用链结果。
     *
     * <p>构造时会将 {@code steps} 复制为不可变列表，并要求根值不能为空，
     * 以保证返回结果可以安全复用。
     */
    public CallTrace {
        steps = steps == null ? List.of() : List.copyOf(steps);
        Objects.requireNonNull(rootValue, "rootValue");
    }

    /**
     * 获取该调用链上所有方法内值传播步骤。
     *
     * <p>返回顺序与 {@link #steps()} 一致，即从目标调用点开始，
     * 逐层向上游调用者展开；每个调用点内部的步骤也保持“使用点 -> 定义点”的逆向顺序。
     *
     * @return 展平后的完整值传播步骤
     */
    public List<ValueFlowStep> valueFlow() {
        List<ValueFlowStep> result = new ArrayList<>();
        for (TraceStep step : steps) {
            result.addAll(step.valueFlow());
        }
        return List.copyOf(result);
    }
}
