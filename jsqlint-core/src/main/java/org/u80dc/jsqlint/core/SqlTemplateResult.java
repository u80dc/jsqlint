package org.u80dc.jsqlint.core;

import org.u80dc.jsqlint.core.node.SqlNode;
import org.u80dc.jsqlint.core.node.SqlNodeRenderer.BoundSql;
import org.u80dc.jsqlint.core.node.SqlNodeRenderer.RenderedSql;
import org.u80dc.jsqlint.core.spi.SourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 模板来源及部分渲染结果；动态节点原样保存，不能把样例值误当作原始 SQL。
 *
 * @author u80dc
 */
public record SqlTemplateResult(String moduleId, SourceLocation location, SqlNode template,
                                Optional<RenderedSql> rendered,
                                List<AnalysisDiagnostic> diagnostics, Optional<SqlDefinition> definition,
                                Optional<BoundSql> bound) implements SqlResult {
    public SqlTemplateResult {
        Objects.requireNonNull(moduleId);
        Objects.requireNonNull(location);
        Objects.requireNonNull(template);
        Objects.requireNonNull(rendered);
        diagnostics = List.copyOf(diagnostics);
        Objects.requireNonNull(definition);
        Objects.requireNonNull(bound);
        if (bound.isPresent() && definition.isEmpty()) throw new IllegalArgumentException("A bound definition snapshot requires a definition");
        if (definition.isPresent() && !template.equals(definition.get().template()))
            throw new IllegalArgumentException("The saved template must match its SQL definition");
        if (bound.isPresent() && bound.get().rendered().recoveryMode() == RenderedSql.RecoveryMode.BOUND_REPLAY
                && !template.equals(bound.get().rendered().residual()))
            throw new IllegalArgumentException("The bound replay template must match its SQL definition");
    }
    /** 兼容旧构造器；定义的默认样例需要显式提供分析阶段保存的 bound 快照。 */
    public SqlTemplateResult(String moduleId, SourceLocation location, SqlNode template,
                             Optional<RenderedSql> rendered, List<AnalysisDiagnostic> diagnostics,
                             Optional<SqlDefinition> definition) {
        this(moduleId, location, template, rendered, diagnostics, definition, Optional.empty());
    }
    public SqlTemplateResult(String moduleId, SourceLocation location, SqlNode template,
                             Optional<RenderedSql> rendered, List<AnalysisDiagnostic> diagnostics) {
        this(moduleId, location, template, rendered, diagnostics, Optional.empty(), Optional.empty());
    }

    public List<SqlNode> templates() {
        return List.of(template);
    }

    public List<RawSql.Variant> rawSqls() {
        if (definition.isPresent()) return bound.stream().flatMap(snapshot -> snapshot.rendered().sql().stream()
                .map(text -> new RawSql.Variant(text, List.of(), Optional.empty(), List.of(), Optional.empty(),
                        List.of(new RawSql.BindingSnapshot(SqlBindingSnapshots.bound(snapshot, definition.get().location(), List.of()), Optional.empty()))))).toList();
        // 尚未枚举动态模板分支时，通过 templates() 访问完整结构，不编造文本候选。
        if (template.isDynamic()) return List.of();
        return rendered.flatMap(RenderedSql::sql).stream().map(text -> new RawSql.Variant(text, List.of())).toList();
    }

    public SqlAssessment assessment() {
        // 渲染预算影响执行终态和值快照，不否定已保存的来源和模板结构。
        boolean sources = definition.map(SqlDefinition::complete).orElse(true) && diagnostics.stream()
                .noneMatch(diagnostic -> diagnostic.incomplete() && !diagnostic.code().equals("SQL_RENDER_LIMIT"));
        boolean values = definition.isPresent() ? bound.map(BoundSql::valuesComplete).orElse(false)
                : rendered.map(RenderedSql::complete).orElse(false);
        return new SqlAssessment(sources, sources, values, Optional.empty());
    }

    /** 显式参数仅用于此模板的样例，不影响原始结果或其恢复状态。 */
    public SqlSample sample(Map<String, ?> parameters) { return SqlSampleRenderer.render(this, parameters); }
}
