package org.u80dc.jsqlint.core;

import org.u80dc.jsqlint.core.node.SqlNode;
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
                                List<AnalysisDiagnostic> diagnostics) implements SqlResult {
    public SqlTemplateResult {
        Objects.requireNonNull(moduleId);
        Objects.requireNonNull(location);
        Objects.requireNonNull(template);
        Objects.requireNonNull(rendered);
        diagnostics = List.copyOf(diagnostics);
    }

    public List<SqlNode> templates() {
        return List.of(template);
    }

    public List<RawSql.Variant> rawSqls() {
        // 尚未枚举动态模板分支时，通过 templates() 访问完整结构，不编造文本候选。
        if (template.isDynamic()) return List.of();
        return rendered.flatMap(RenderedSql::sql).stream().map(text -> new RawSql.Variant(text, List.of())).toList();
    }

    public SqlAssessment assessment() {
        boolean complete = rendered.map(RenderedSql::complete).orElse(false);
        boolean sources = diagnostics.stream().noneMatch(AnalysisDiagnostic::incomplete);
        return new SqlAssessment(sources, sources, complete, Optional.empty());
    }

    /** 显式参数仅用于此模板的样例，不影响原始结果或其恢复状态。 */
    public SqlSample sample(Map<String, ?> parameters) { return SqlSampleRenderer.render(this, parameters); }
}
