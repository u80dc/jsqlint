package cn.jnu.zhangcs.jsqlint.analysis.parameter.trace;

import sootup.core.signatures.MethodSignature;

import java.util.List;
import java.util.Objects;

/**
 * 参数追踪链路中的单个调用点步骤。
 *
 * <p>一个 {@code TraceStep} 描述一次具体方法调用：哪个调用者方法
 * {@code caller} 在哪条语句 {@code callSite} 上调用了哪个被调方法
 * {@code callee}，以及当前追踪的目标参数在该调用点对应的实参内容。
 * {@code valueFlow} 进一步记录该实参在调用者方法内部从使用点到定义点的逆向传播链。
 * 多个 {@code TraceStep} 按顺序组合后构成一条 {@link CallTrace}。
 *
 * @param caller        发生调用的调用者方法签名
 * @param callSite      调用点语句的字符串形式，用于展示和诊断
 * @param callee        被调用的方法签名
 * @param argumentIndex 当前追踪参数在调用点实参列表中的下标
 * @param argument      调用点实参解析后的结构化值模型
 * @param valueFlow     调用者方法内部的逆向值传播步骤
 * @author zhangcs
 */
public record TraceStep(
        MethodSignature caller,
        String callSite,
        MethodSignature callee,
        int argumentIndex,
        ValueModel argument,
        List<ValueFlowStep> valueFlow) {

    /**
     * 创建不携带方法内值传播链的调用点步骤。
     *
     * <p>该构造器保留给只关心跨方法调用关系的旧调用方使用。
     */
    public TraceStep(
            MethodSignature caller,
            String callSite,
            MethodSignature callee,
            int argumentIndex,
            ValueModel argument) {
        this(caller, callSite, callee, argumentIndex, argument, List.of());
    }

    /**
     * 创建调用点步骤。
     *
     * <p>调用者、被调方法和实参模型不能为空；调用点语句允许为空，
     * 为空时会规范化为空字符串，避免展示逻辑处理 {@code null}。
     * {@code valueFlow} 会复制为不可变列表，方便结果对象安全复用。
     */
    public TraceStep {
        Objects.requireNonNull(caller, "caller");
        callSite = callSite == null ? "" : callSite;
        Objects.requireNonNull(callee, "callee");
        Objects.requireNonNull(argument, "argument");
        valueFlow = valueFlow == null ? List.of() : List.copyOf(valueFlow);
    }

    /**
     * 返回适合日志输出的单行摘要。
     *
     * @return 格式为 {@code caller -> callee[index] = argument} 的摘要字符串
     */
    public String summary() {
        return caller.getName() + " -> " + callee.getName()
                + "[" + argumentIndex + "] = " + argument.display();
    }
}
