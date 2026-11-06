package org.u80dc.jsqlint.core.parameter;

import java.util.List;
import java.util.Objects;

/** 单个参数的类型及逐条传播路径推断出的候选值；同值不同路径仍分别保存。 @author u80dc */
public record ParameterValueInferenceResult(String name, String type,
                                            List<InferredParameterValue> possibleValues, List<String> issues) {
    public ParameterValueInferenceResult {
        Objects.requireNonNull(name); Objects.requireNonNull(type);
        possibleValues = List.copyOf(possibleValues); issues = List.copyOf(issues);
    }

    /** 完整只表示重演没有未知操作或截断，不表示生成的外部样例满足业务分支约束。 */
    public boolean complete() {
        return !possibleValues.isEmpty() && issues.isEmpty() && possibleValues.stream().allMatch(InferredParameterValue::complete);
    }
}
