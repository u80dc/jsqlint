package org.u80dc.jsqlint.core.parameter;

import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.ReplayBudgets;
import org.u80dc.jsqlint.core.node.*;
import java.util.*;

/** 从冻结的 Java 值来源重演模板槽位；不保留代码输入或执行目标方法。 @author u80dc */
public final class TraceSqlExpressionEvaluator implements SqlExpressionEvaluator {
    private final SqlExpressionEvaluator delegate;
    private final Map<String, ValueTrace> expressions;
    private final Map<String, ValueTrace.Variable> inputs;
    private final ReplayBudgets replayBudgets;

    public TraceSqlExpressionEvaluator(SqlExpressionEvaluator delegate, Map<String, ValueTrace> expressions,
                                       Map<String, ValueTrace.Variable> inputs) {
        this(delegate, expressions, inputs, ReplayBudgets.defaults());
    }
    public TraceSqlExpressionEvaluator(SqlExpressionEvaluator delegate, Map<String, ValueTrace> expressions,
                                       Map<String, ValueTrace.Variable> inputs, ReplayBudgets replayBudgets) {
        this.delegate = Objects.requireNonNull(delegate);
        this.expressions = Collections.unmodifiableMap(new LinkedHashMap<>(expressions));
        this.inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        this.replayBudgets = Objects.requireNonNull(replayBudgets);
    }
    @Override public SqlExpressionEvaluator withReplayBudgets(ReplayBudgets budgets) {
        var configured = delegate.withReplayBudgets(budgets);
        return replayBudgets.equals(budgets) && configured == delegate ? this
                : new TraceSqlExpressionEvaluator(configured, expressions, inputs, budgets);
    }
    @Override public SqlValue evaluate(String expression, Map<String, ?> parameters) {
        if (!expressions.containsKey(expression) || parameters.containsKey(expression)) return delegate.evaluate(expression, parameters);
        var values = new LinkedHashMap<ValueTrace.Variable, SqlValue>();
        inputs.forEach((name, variable) -> {
            if (parameters.containsKey(name)) {
                Object value = parameters.get(name); values.putIfAbsent(variable, value instanceof SqlValue sqlValue ? sqlValue : new SqlValue.Known(value));
            }
        });
        var context = SqlEvaluationContext.currentOr(replayBudgets);
        var result = new ParameterValueInferencer((type, name) -> new SqlValue.Unknown("Missing Java template input: " + name), context::check, values, context.replayBudgets())
                .infer(expressions.get(expression));
        if (result.truncated()) context.limit("Java template expression replay budget exceeded");
        var possible = result.possibleValues().stream().map(InferredParameterValue::value).distinct().toList();
        return possible.size() == 1 && result.complete() ? possible.getFirst() : new SqlValue.Unknown("Java template expression has unresolved alternatives: " + expression);
    }
    @Override public SqlValue evaluateParameter(String expression, Map<String, ?> parameters) {
        return expressions.containsKey(expression) ? evaluate(expression, parameters) : delegate.evaluateParameter(expression, parameters);
    }
}
