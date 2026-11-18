package org.u80dc.jsqlint.core;

import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.spi.SourceLocation;
import java.util.*;
import java.util.function.Function;

/** 框架适配后的不可变 SQL 定义；标识和元数据由模块解释，不持有 DOM 或代码输入。 @author u80dc */
public record SqlDefinition(String id, SourceLocation location, SqlNode template,
                            SqlExpressionEvaluator evaluator, Map<String, String> metadata, List<String> issues) {
    public SqlDefinition {
        Objects.requireNonNull(id); Objects.requireNonNull(location); Objects.requireNonNull(template);
        Objects.requireNonNull(evaluator); metadata = Map.copyOf(metadata); issues = List.copyOf(issues);
    }
    public SqlDefinition(String id, SourceLocation location, SqlNode template) {
        this(id, location, template, SqlExpressionEvaluator.simple(), Map.of(), List.of());
    }
    public boolean complete() { return issues.isEmpty(); }
    public SqlNodeRenderer.BoundSql render(Map<String, ?> parameters) {
        return render(parameters, () -> {});
    }
    public SqlNodeRenderer.BoundSql render(Map<String, ?> parameters, Runnable checkpoint) {
        return rendered(SqlNodeRenderer.renderBound(template, parameters, evaluator, checkpoint));
    }
    public SqlNodeRenderer.BoundSql render(Map<String, ?> parameters,
            Function<SqlExpressionEvaluator.Reference, SqlValueOrigin> inputs, Runnable checkpoint) {
        return rendered(SqlNodeRenderer.renderBound(template, parameters, evaluator, inputs, checkpoint));
    }
    private SqlNodeRenderer.BoundSql rendered(SqlNodeRenderer.BoundSql bound) {
        var rendered = bound.rendered();
        return new SqlNodeRenderer.BoundSql(new SqlNodeRenderer.RenderedSql(rendered.sql().map(String::strip),
                rendered.residual(), rendered.parameters(), rendered.diagnostics(), rendered.recoveryMode()), bound.bindings());
    }
}
