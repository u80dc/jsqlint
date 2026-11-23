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
 * 独立定义或裸模板的结果；两种内容分别保存自己的渲染快照。
 * 定义仅保存参数化快照，旧字面量预览只用于分析时的监听器回调。
 *
 * @author u80dc
 */
public record SqlTemplateResult(String moduleId, SourceLocation location, Content content,
                                List<AnalysisDiagnostic> diagnostics) implements SqlResult {
    public SqlTemplateResult {
        Objects.requireNonNull(moduleId);
        Objects.requireNonNull(location);
        Objects.requireNonNull(content);
        diagnostics = List.copyOf(diagnostics);
    }

    /** 内容类型消除定义、原模板和两种渲染结果的重复存储与无效组合。 */
    public sealed interface Content {
        record Definition(SqlDefinition definition, Optional<BoundSql> bound) implements Content {
            public Definition {
                Objects.requireNonNull(definition);
                Objects.requireNonNull(bound);
                if (bound.isPresent() && bound.get().rendered().recoveryMode() == RenderedSql.RecoveryMode.BOUND_REPLAY
                        && !definition.template().equals(bound.get().rendered().residual()))
                    throw new IllegalArgumentException("The bound replay template must match its SQL definition");
            }
        }
        record Template(SqlNode template, Optional<RenderedSql> rendered) implements Content {
            public Template {
                Objects.requireNonNull(template);
                Objects.requireNonNull(rendered);
            }
        }
    }

    /** 旧参数的校验适配；定义的字面量预览不再保存，样例读取仅使用 bound 快照。 */
    public SqlTemplateResult(String moduleId, SourceLocation location, SqlNode template,
                             Optional<RenderedSql> rendered, List<AnalysisDiagnostic> diagnostics,
                             Optional<SqlDefinition> definition, Optional<BoundSql> bound) {
        this(moduleId, location, content(template, rendered, definition, bound), diagnostics);
    }

    /** 定义的默认样例需要显式提供分析阶段保存的 bound 快照。 */
    public SqlTemplateResult(String moduleId, SourceLocation location, SqlNode template,
                             Optional<RenderedSql> rendered, List<AnalysisDiagnostic> diagnostics,
                             Optional<SqlDefinition> definition) {
        this(moduleId, location, template, rendered, diagnostics, definition, Optional.empty());
    }
    public SqlTemplateResult(String moduleId, SourceLocation location, SqlNode template,
                             Optional<RenderedSql> rendered, List<AnalysisDiagnostic> diagnostics) {
        this(moduleId, location, template, rendered, diagnostics, Optional.empty(), Optional.empty());
    }

    private static Content content(SqlNode template, Optional<RenderedSql> rendered,
                                   Optional<SqlDefinition> definition, Optional<BoundSql> bound) {
        Objects.requireNonNull(template);
        Objects.requireNonNull(rendered);
        Objects.requireNonNull(definition);
        Objects.requireNonNull(bound);
        if (definition.isPresent()) {
            if (!template.equals(definition.get().template()))
                throw new IllegalArgumentException("The saved template must match its SQL definition");
            return new Content.Definition(definition.get(), bound);
        }
        if (bound.isPresent()) throw new IllegalArgumentException("A bound definition snapshot requires a definition");
        return new Content.Template(template, rendered);
    }

    public SqlNode template() {
        return switch (content) {
            case Content.Definition value -> value.definition().template();
            case Content.Template value -> value.template();
        };
    }

    /** 定义返回参数化快照的渲染结果；裸模板返回自己的部分渲染结果。 */
    public Optional<RenderedSql> rendered() {
        return switch (content) {
            case Content.Definition value -> value.bound().map(BoundSql::rendered);
            case Content.Template value -> value.rendered();
        };
    }

    public Optional<SqlDefinition> definition() {
        return content instanceof Content.Definition value ? Optional.of(value.definition()) : Optional.empty();
    }

    public Optional<BoundSql> bound() {
        return content instanceof Content.Definition value ? value.bound() : Optional.empty();
    }

    public List<SqlNode> templates() {
        return List.of(template());
    }

    public List<RawSql.Variant> rawSqls() {
        if (content instanceof Content.Definition value) return value.bound().stream().flatMap(snapshot -> snapshot.rendered().sql().stream()
                .map(text -> new RawSql.Variant(text, List.of(), Optional.empty(), List.of(), Optional.empty(),
                        List.of(new RawSql.BindingSnapshot(SqlBindingSnapshots.bound(snapshot, value.definition().location(), List.of()), Optional.empty()))))).toList();
        // 尚未枚举动态模板分支时，通过 templates() 访问完整结构，不编造文本候选。
        if (template().isDynamic()) return List.of();
        return rendered().flatMap(RenderedSql::sql).stream().map(text -> new RawSql.Variant(text, List.of())).toList();
    }

    public SqlAssessment assessment() {
        return SqlAssessmentPolicy.template(this);
    }

    /** 显式参数仅用于此模板的样例，不影响原始结果或其恢复状态。 */
    public SqlSample sample(Map<String, ?> parameters) { return SqlSampleRenderer.render(this, parameters); }
    public SqlSample sample(Map<String, ?> parameters, org.u80dc.jsqlint.core.node.SqlEvaluationContext context) {
        return SqlSampleRenderer.render(this, parameters, context);
    }
}
