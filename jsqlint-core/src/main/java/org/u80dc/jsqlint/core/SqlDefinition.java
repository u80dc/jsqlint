package org.u80dc.jsqlint.core;

import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.spi.SourceLocation;
import java.util.*;
import java.util.function.Function;

/** 框架适配后的不可变 SQL 定义；标识和元数据由模块解释，不持有 DOM 或代码输入。 @author u80dc */
public record SqlDefinition(String id, SourceLocation location, SqlNode template,
                            SqlExpressionEvaluator evaluator, Map<String, String> metadata, List<String> issues,
                            Optional<SqlBoundTransform> transform) {
    public SqlDefinition(String id,SourceLocation location,SqlNode template,SqlExpressionEvaluator evaluator,
                         Map<String,String> metadata,List<String> issues) {this(id,location,template,evaluator,metadata,issues,Optional.empty());}
    public SqlDefinition {
        Objects.requireNonNull(id); Objects.requireNonNull(location); Objects.requireNonNull(template);
        Objects.requireNonNull(evaluator); metadata = Map.copyOf(metadata); issues = List.copyOf(issues);
        Objects.requireNonNull(transform);
    }
    public SqlDefinition(String id, SourceLocation location, SqlNode template) {
        this(id, location, template, SqlExpressionEvaluator.simple(), Map.of(), List.of());
    }
    public boolean complete() { return issues.isEmpty(); }
    /** 保存 Java 来源的重演策略；整次 SQL 渲染的预算、取消及活动值由执行上下文提供。 */
    public SqlDefinition withReplayBudgets(ReplayBudgets budgets) {
        var configured = evaluator.withReplayBudgets(budgets);
        return configured == evaluator ? this : new SqlDefinition(id, location, template, configured, metadata, issues,transform);
    }
    public SqlDefinition withMetadata(Map<String,String> values) {return new SqlDefinition(id,location,template,evaluator,values,issues,transform);}
    public SqlDefinition withTransform(SqlBoundTransform value) {return new SqlDefinition(id,location,template,evaluator,metadata,issues,Optional.of(value));}
    public SqlNodeRenderer.BoundSql render(Map<String, ?> parameters) {
        return render(parameters, () -> {});
    }
    public SqlNodeRenderer.BoundSql render(Map<String, ?> parameters, Runnable checkpoint) {
        return render(parameters,SqlEvaluationContext.defaults(checkpoint,ignored->{}));
    }
    public SqlNodeRenderer.BoundSql render(Map<String, ?> parameters,
            Function<SqlExpressionEvaluator.Reference, SqlValueOrigin> inputs, Runnable checkpoint) {
        return render(parameters,inputs,SqlEvaluationContext.defaults(checkpoint,ignored->{}));
    }
    public SqlNodeRenderer.BoundSql render(Map<String, ?> parameters, SqlEvaluationContext context) {
        return render(parameters,reference->new SqlValueOrigin.Input(reference.name()),context);
    }
    public SqlNodeRenderer.BoundSql render(Map<String, ?> parameters,
            Function<SqlExpressionEvaluator.Reference, SqlValueOrigin> inputs, SqlEvaluationContext context) {
        try (var timing = context.statisticsRecorder().measure(AnalysisStatistics.Phase.RENDERING)) {
            return rendered(SqlNodeRenderer.renderBound(template, parameters, evaluator, inputs, context),context,inputs);
        }
    }
    private SqlNodeRenderer.BoundSql rendered(SqlNodeRenderer.BoundSql bound,SqlEvaluationContext context,
            Function<SqlExpressionEvaluator.Reference,SqlValueOrigin> inputs) {
        var rendered = bound.rendered();
        var normalized=new SqlNodeRenderer.BoundSql(new SqlNodeRenderer.RenderedSql(rendered.sql().map(String::strip),
                rendered.residual(), rendered.parameters(), rendered.diagnostics(), rendered.recoveryMode()), bound.bindings(),bound.rewrites());
        if(transform.isEmpty()||!normalized.rendered().complete())return normalized;
        try (var timing = context.statisticsRecorder().measure(AnalysisStatistics.Phase.REWRITING)) {
            return context.evaluate(()->SqlBoundTransformResults.validate(normalized,
                    Objects.requireNonNull(transform.get().apply(normalized,metadata,context,inputs)),context));
        }
    }
}
