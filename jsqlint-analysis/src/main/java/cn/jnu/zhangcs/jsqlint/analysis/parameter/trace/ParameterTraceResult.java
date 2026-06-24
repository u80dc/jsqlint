package cn.jnu.zhangcs.jsqlint.analysis.parameter.trace;

import java.util.List;
import java.util.Objects;

/**
 * 单个目标方法参数的追踪结果。
 *
 * <p>该对象是 {@link ParameterTraceAnalyzer#analyze(sootup.core.signatures.MethodSignature, int)}
 * 的顶层返回值，用于同时保存目标参数、追踪到的调用链以及分析过程中产生的诊断信息。
 * 当分析结果为空或只得到部分链路时，调用方可以通过 {@link #diagnostics()} 查看原因。
 *
 * @param target      本次分析的目标方法参数，例如某个 sink 方法的第 0 个参数
 * @param traces      从目标调用点向上追踪得到的调用链集合，每个元素表示一条独立链路
 * @param diagnostics 分析过程中的非致命诊断信息，例如调用者无方法体、递归超限或循环调用
 * @author zhangcs
 */
public record ParameterTraceResult(
        MethodParameterRef target,
        List<CallTrace> traces,
        List<String> diagnostics) {

    /**
     * 创建参数追踪结果。
     *
     * <p>构造时会将 {@code traces} 和 {@code diagnostics} 复制为不可变列表，
     * 避免调用方在结果返回后意外修改分析结果。
     */
    public ParameterTraceResult {
        Objects.requireNonNull(target, "target");
        traces = traces == null ? List.of() : List.copyOf(traces);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    /**
     * 判断本次分析是否至少找到一条调用链。
     *
     * @return 找到调用链时返回 {@code true}；否则返回 {@code false}
     */
    public boolean hasTraces() {
        return !traces.isEmpty();
    }
}
