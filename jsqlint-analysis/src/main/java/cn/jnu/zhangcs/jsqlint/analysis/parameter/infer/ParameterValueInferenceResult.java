package cn.jnu.zhangcs.jsqlint.analysis.parameter.infer;

import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.MethodParameterRef;

import java.util.List;
import java.util.Objects;

/**
 * 单个目标参数的可能值推断结果。
 *
 * @param target         被推断的目标方法参数
 * @param possibleValues 所有按传播路径推断出的可能值
 * @param diagnostics    推断过程中的诊断信息
 * @author zhangcs
 */
public record ParameterValueInferenceResult(
        MethodParameterRef target,
        List<InferredParameterValue> possibleValues,
        List<String> diagnostics) {

    public ParameterValueInferenceResult {
        Objects.requireNonNull(target, "target");
        possibleValues = possibleValues == null ? List.of() : List.copyOf(possibleValues);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    /**
     * 判断是否推断出至少一种可能值。
     *
     * @return 有可能值时返回 {@code true}
     */
    public boolean hasValues() {
        return !possibleValues.isEmpty();
    }
}
