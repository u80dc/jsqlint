package cn.jnu.zhangcs.jsqlint.analysis.parameter.infer;

import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.CallTrace;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.MethodParameterRef;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.ValueModel;

import java.util.Objects;

/**
 * 目标参数的一种可能值。
 *
 * <p>该结构把推断出的具体值和产生该值的传播路径绑定起来。调用方可以从
 * {@link #propagationPath()} 继续查看跨方法调用链和方法内 {@code valueFlow}。
 *
 * @param target          被推断的目标方法参数
 * @param value           推断出的 Java 样例值
 * @param displayValue    适合日志和报告展示的值
 * @param valueType       值类型描述
 * @param origin          值来源类型
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
        ValueModel targetValue,
        ValueModel sourceValue,
        CallTrace propagationPath) {

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
     * 返回适合单行输出的摘要。
     *
     * @return 包含来源和值的摘要字符串
     */
    public String summary() {
        return origin + " " + displayValue + " via " + targetValue.display();
    }
}
