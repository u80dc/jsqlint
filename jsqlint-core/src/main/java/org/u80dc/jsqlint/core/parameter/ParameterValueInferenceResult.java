package org.u80dc.jsqlint.core.parameter;

import java.util.List;
import java.util.Objects;

/** 单个参数的类型及逐条传播路径推断出的候选值；同值不同路径仍分别保存。 @author u80dc */
public record ParameterValueInferenceResult(String name, String type,
                                            List<InferredParameterValue> possibleValues, List<String> issues, boolean truncated) {
    public ParameterValueInferenceResult {
        Objects.requireNonNull(name); Objects.requireNonNull(type);
        possibleValues = List.copyOf(possibleValues); issues = List.copyOf(issues);
    }

    /** 未显式声明截断时，issues 只说明未知值或重演问题，不从文案推测预算状态。 */
    public ParameterValueInferenceResult(String name, String type, List<InferredParameterValue> possibleValues, List<String> issues) {
        this(name, type, possibleValues, issues, false);
    }

    /** 完整只表示重演没有未知操作或截断，不表示生成的外部样例满足业务分支约束。 */
    public boolean complete() {
        return !truncated && !possibleValues.isEmpty() && issues.isEmpty() && possibleValues.stream().allMatch(InferredParameterValue::complete);
    }
}
