package cn.jnu.zhangcs.jsqlint.analysis.parameter;

import cn.jnu.zhangcs.jsqlint.analysis.parameter.infer.ParameterValueInferenceResult;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.infer.ParameterValueInferencer;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.MethodParameterRef;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.ParameterTraceAnalyzer;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.ParameterTraceResult;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 参数传播追踪与值推断的框架中立编排服务。
 *
 * <p>将 {@link ParameterTraceAnalyzer} 的传播链追踪与 {@link ParameterValueInferencer}
 * 的取值推断串联为一个调用：给定目标方法参数引用，先追踪其值的来源链路，
 * 再基于链路推断可能的取值集合。</p>
 *
 * @author zhangcs
 */
public final class ParameterAnalysisService {

    private final ParameterTraceAnalyzer traceAnalyzer;
    private final ParameterValueInferencer inferencer;

    /**
     * 创建使用默认值推断器的实例。
     *
     * @param traceAnalyzer 参数传播链追踪器
     */
    public ParameterAnalysisService(ParameterTraceAnalyzer traceAnalyzer) {
        this(traceAnalyzer, new ParameterValueInferencer());
    }

    /**
     * 创建指定追踪器与推断器的实例。
     *
     * @param traceAnalyzer 参数传播链追踪器
     * @param inferencer    参数值推断器
     */
    public ParameterAnalysisService(
            ParameterTraceAnalyzer traceAnalyzer,
            ParameterValueInferencer inferencer) {

        this.traceAnalyzer = Objects.requireNonNull(traceAnalyzer, "traceAnalyzer");
        this.inferencer = Objects.requireNonNull(inferencer, "inferencer");
    }

    /**
     * 对单个目标参数执行追踪与推断。
     *
     * @param target 目标方法参数引用
     * @return 推断结果，包含候选值与诊断信息
     */
    public ParameterValueInferenceResult infer(MethodParameterRef target) {
        Objects.requireNonNull(target, "target");
        if (!target.isValid()) {
            return new ParameterValueInferenceResult(
                    target,
                    List.of(),
                    List.of("Invalid parameter index: " + target.displayName()));
        }
        ParameterTraceResult traceResult = traceAnalyzer.analyze(target.method(), target.index());
        return inferencer.infer(traceResult);
    }

    /**
     * 对一批目标参数批量执行追踪与推断。
     *
     * @param targets 目标方法参数引用集合，允许为 null
     * @return 与入参顺序一致的推断结果列表；入参为空时返回空列表
     */
    public List<ParameterValueInferenceResult> inferAll(Collection<MethodParameterRef> targets) {
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }
        return targets.stream()
                .map(this::infer)
                .toList();
    }
}
