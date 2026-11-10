package org.u80dc.jsqlint.core.parameter;

import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.*;
import java.util.*;

/** 从冻结的 Java 值来源重演模板槽位；不保留代码输入或执行目标方法。 @author u80dc */
public final class TraceSqlExpressionEvaluator implements SqlExpressionEvaluator {
    private final SqlExpressionEvaluator delegate;
    private final Map<String, ValueTrace> expressions;
    private final Map<String, ValueTrace.Variable> inputs;

    public TraceSqlExpressionEvaluator(SqlExpressionEvaluator delegate, Map<String, ValueTrace> expressions,
                                       Map<String, ValueTrace.Variable> inputs) {
        this.delegate = Objects.requireNonNull(delegate);
        this.expressions = Collections.unmodifiableMap(new LinkedHashMap<>(expressions));
        this.inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
    }
    @Override public SqlValue evaluate(String expression, Map<String, ?> parameters) {
        if (!expressions.containsKey(expression) || parameters.containsKey(expression)) return delegate.evaluate(expression, parameters);
        var values = new LinkedHashMap<ValueTrace.Variable, SqlValue>();
        inputs.forEach((name, variable) -> {
            if (parameters.containsKey(name)) {
                Object value = parameters.get(name); values.putIfAbsent(variable, value instanceof SqlValue sqlValue ? sqlValue : new SqlValue.Known(value));
            }
        });
        var result = new ParameterValueInferencer((type, name) -> new SqlValue.Unknown("Missing Java template input: " + name), () -> {}, values)
                .infer(expressions.get(expression));
        var possible = result.possibleValues().stream().map(InferredParameterValue::value).distinct().toList();
        return possible.size() == 1 && result.complete() ? possible.getFirst() : new SqlValue.Unknown("Java template expression has unresolved alternatives: " + expression);
    }
    @Override public SqlValue evaluateParameter(String expression, Map<String, ?> parameters) {
        return expressions.containsKey(expression) ? evaluate(expression, parameters) : delegate.evaluateParameter(expression, parameters);
    }
}
