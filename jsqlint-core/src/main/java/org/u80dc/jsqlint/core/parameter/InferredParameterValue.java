package org.u80dc.jsqlint.core.parameter;

import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.SqlValue;
import java.util.List;
import java.util.Objects;

/** 一种推断值与其选中的传播路径、初始值绑定；已知 null 与未知值明确区分。 @author u80dc */
public record InferredParameterValue(SqlValue value, ValueTrace propagationPath,
                                      List<InitialValue> initialValues, List<String> issues) {
    public InferredParameterValue {
        Objects.requireNonNull(value); Objects.requireNonNull(propagationPath);
        initialValues = List.copyOf(initialValues); issues = List.copyOf(issues);
    }
    public boolean complete() { return value instanceof SqlValue.Known && issues.isEmpty(); }
    public boolean sampled() { return initialValues.stream().anyMatch(InitialValue::generated); }

    /** 保存实际用于重演的叶子输入，便于复核随机样例经过转换后的结果。 */
    public record InitialValue(ValueTrace.Variable variable, SqlValue value, boolean generated) {
        public InitialValue { Objects.requireNonNull(variable); Objects.requireNonNull(value); }
    }
}
