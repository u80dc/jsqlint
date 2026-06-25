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
 * Framework-neutral orchestration for parameter propagation tracing and value inference.
 */
public final class ParameterAnalysisService {

    private final ParameterTraceAnalyzer traceAnalyzer;
    private final ParameterValueInferencer inferencer;

    public ParameterAnalysisService(ParameterTraceAnalyzer traceAnalyzer) {
        this(traceAnalyzer, new ParameterValueInferencer());
    }

    public ParameterAnalysisService(
            ParameterTraceAnalyzer traceAnalyzer,
            ParameterValueInferencer inferencer) {

        this.traceAnalyzer = Objects.requireNonNull(traceAnalyzer, "traceAnalyzer");
        this.inferencer = Objects.requireNonNull(inferencer, "inferencer");
    }

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

    public List<ParameterValueInferenceResult> inferAll(Collection<MethodParameterRef> targets) {
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }
        return targets.stream()
                .map(this::infer)
                .toList();
    }
}
