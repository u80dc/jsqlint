package org.u80dc.jsqlint.core.parameter;

import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.SqlValue;
import java.util.*;

/** SQL 候选下的联合参数解；不能将不同 Combination 中的参数再次任意组合。 @author u80dc */
public record JointSqlInferenceResult(Status status, List<Combination> combinations, List<String> issues) {
    public JointSqlInferenceResult {
        Objects.requireNonNull(status); combinations = List.copyOf(combinations); issues = List.copyOf(issues);
    }
    /** UNSATISFIABLE 表示在完整枚举的受支持路径内已证明无解；UNKNOWN 不可当作无解。 */
    public enum Status { SATISFIABLE, UNSATISFIABLE, UNKNOWN }

    public record Combination(Status status, Map<String, InferredParameterValue> parameters,
                              List<BindingValue> bindings, List<Condition> conditions,
                              Map<ValueTrace.Variable, SqlValue> inputs, List<String> issues) {
        public Combination {
            Objects.requireNonNull(status);
            parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
            bindings = List.copyOf(bindings); conditions = List.copyOf(conditions);
            inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs)); issues = List.copyOf(issues);
        }
    }
    /** keyValue 同样参与求解，避免动态 setter 下标与参数值脱离原分支。 */
    public record BindingValue(String key, InferredParameterValue keyValue, InferredParameterValue value) {
        public BindingValue { Objects.requireNonNull(key); Objects.requireNonNull(keyValue); Objects.requireNonNull(value); }
    }
    /** expression 保存已按当前控制流路径选择到达定义的条件传播树。 */
    public record Condition(ValueTrace expression, boolean expected) {
        public Condition { Objects.requireNonNull(expression); }
    }
}
