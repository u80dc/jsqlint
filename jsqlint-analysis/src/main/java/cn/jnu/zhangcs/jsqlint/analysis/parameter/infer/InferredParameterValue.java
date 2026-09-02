package cn.jnu.zhangcs.jsqlint.analysis.parameter.infer;

import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.CallTrace;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.MethodParameterRef;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.ValueModel;

import java.util.Objects;

/**
 * 目标参数的一种可能值。
 *
 * <p>该结构把推断出的具体值和产生该值的传播路径绑定起来。调用方可以从
 * {@link #propagationPath()} 继续查看跨方法调用链和方法内 {@code valueFlow}。</p>
 *
 * @param target          被推断的目标方法参数
 * @param value           推断出的 Java 样例值
 * @param displayValue    适合日志和报告展示的值
 * @param valueType       值类型描述
 * @param origin          值来源类型
 * @param parameterIndex  SQL 参数下标（第几个 {@code ?} 占位符，从 1 开始）；
 *                        来源为 SQL 文本实参时无对应占位符，为 0
 * @param targetValue     目标方法参数在调用点处的结构化值模型，已根据上游路径替换参数依赖
 * @param sourceValue     传播路径最上游的结构化值模型
 * @param propagationPath 产生该可能值的传播路径
 * @author zhangcs
 */
public record InferredParameterValue(
        MethodParameterRef target,
        Object value,
        String displayValue,
        String valueType,
        InferredValueOrigin origin,
        int parameterIndex,
        ValueModel targetValue,
        ValueModel sourceValue,
        CallTrace propagationPath) {

    /**
     * 兼容不带参数下标的旧签名，parameterIndex 默认为 0（非绑定占位符来源）。
     */
    public InferredParameterValue(
            MethodParameterRef target,
            Object value,
            String displayValue,
            String valueType,
            InferredValueOrigin origin,
            ValueModel targetValue,
            ValueModel sourceValue,
            CallTrace propagationPath) {
        this(target, value, displayValue, valueType, origin, 0, targetValue, sourceValue, propagationPath);
    }

    public InferredParameterValue {
        Objects.requireNonNull(target, "target");
        displayValue = displayValue == null ? "null" : displayValue;
        valueType = valueType == null || valueType.isBlank() ? "unknown" : valueType;
        origin = origin == null ? InferredValueOrigin.UNKNOWN : origin;
        Objects.requireNonNull(targetValue, "targetValue");
        Objects.requireNonNull(sourceValue, "sourceValue");
        Objects.requireNonNull(propagationPath, "propagationPath");
    }

    /**
     * 该可能值是否对应 SQL 中的某个 {@code ?} 占位符。
     *
     * @return parameterIndex 大于 0 时返回 {@code true}
     */
    public boolean boundToPlaceholder() {
        return parameterIndex > 0;
    }

    /**
     * 返回适合单行输出的摘要。
     *
     * @return 包含来源和值的摘要
     */
    public String summary() {
        return origin + " " + displayValue + " via " + targetValue.display();
    }
}
