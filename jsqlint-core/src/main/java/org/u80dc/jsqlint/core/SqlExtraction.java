package org.u80dc.jsqlint.core;

import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.template.RawSqlBuilder;
import org.u80dc.jsqlint.core.template.SqlTemplateProjection;
import org.u80dc.jsqlint.core.node.SqlNode;
import org.u80dc.jsqlint.core.node.SqlNodeRenderer.RenderedSql;
import org.u80dc.jsqlint.core.spi.*;
import org.u80dc.jsqlint.core.parameter.ParameterValueInferenceResult;
import org.u80dc.jsqlint.core.parameter.ParameterValueInferencer;
import org.u80dc.jsqlint.core.parameter.JointSqlInferenceResult;
import java.util.*;

/** 单个 SQL 调用点在一个调用上下文中的结果；关闭 SootUp 输入后仍可读取。 @author u80dc */
public record SqlExtraction(String moduleId, SqlCallSite callSite, List<SqlCallSite> callChain,
                            List<Statement> statements, List<String> issues) implements SqlResult {
    public SqlExtraction {
        Objects.requireNonNull(moduleId); Objects.requireNonNull(callSite); callChain = List.copyOf(callChain);
        statements = List.copyOf(statements); issues = List.copyOf(issues);
    }
    public boolean complete() { return issues.isEmpty() && statements.stream().allMatch(Statement::complete); }
    public SourceLocation location() { return callSite.location(); }
    public List<SqlNode> templates() { return statements.stream().map(Statement::template).toList(); }
    public List<RawSql.Variant> rawSqls() {
        return statements.stream().flatMap(statement -> statement.rawSql().variants().stream()).toList();
    }
    public SqlAssessment assessment() {
        return SqlAssessmentPolicy.extraction(this);
    }
    public record Statement(Optional<ValueTrace> sql, Optional<SourceLocation> batchEntry,
                            List<String> issues, Content content) {
        public Statement {
            Objects.requireNonNull(sql); Objects.requireNonNull(batchEntry); issues = List.copyOf(issues);
            Objects.requireNonNull(content);
            if (content instanceof Content.Modeled modeled && !modeled.sqlSource.equals(sql))
                throw new IllegalArgumentException("Modeled candidates must preserve the statement SQL source");
        }
        /** 明确区分代码候选、手工定义候选和已渲染模板，避免重复保存模板及模型派生数据。 */
        public sealed interface Content {
            SqlNode template();
            List<Binding> bindings();
            List<String> templateIssues();
            RawSql rawSql();

            record Code(SqlNode template, List<Binding> bindings, List<String> templateIssues, RawSql rawSql) implements Content {
                public Code {
                    Objects.requireNonNull(template); bindings = List.copyOf(bindings);
                    templateIssues = List.copyOf(templateIssues); rawSql = Objects.requireNonNull(rawSql).withBindings(bindings);
                    SqlBindingSnapshots.validateStatementBindings(bindings, rawSql);
                }
            }
            /** 公开手工构造的已关联定义仍可保存独立 raw 候选；不制造不存在的模板模型。 */
            record Definition(SqlDefinition definition, List<Binding> bindings, RawSql rawSql) implements Content {
                public Definition {
                    Objects.requireNonNull(definition); bindings = List.copyOf(bindings); Objects.requireNonNull(rawSql);
                    SqlBindingSnapshots.validateStatementBindings(bindings, rawSql);
                }
                public SqlNode template() { return definition.template(); }
                public List<String> templateIssues() { return definition.issues(); }
            }
            /** 定义和冻结模型是唯一来源；候选与绑定读取缓存仅在组装时创建，不重演或深图去重。 */
            final class Modeled implements Content {
                private final SqlDefinition definition;
                private final TemplateInferenceResult inference;
                private final Optional<ValueTrace> sqlSource;
                private final List<Binding> bindings;
                private final RawSql rawSql;
                private final List<Optional<RawSql.Variant>> candidates;

                public Modeled(SqlDefinition definition, TemplateInferenceResult inference) {
                    this(definition, inference, Optional.empty());
                }
                public Modeled(SqlDefinition definition, TemplateInferenceResult inference, Optional<ValueTrace> sqlSource) {
                    this.definition = Objects.requireNonNull(definition); this.inference = Objects.requireNonNull(inference);
                    this.sqlSource = Objects.requireNonNull(sqlSource);
                    if (inference.models().isEmpty() && inference.jointInference()
                            .filter(joint -> joint.status() == JointSqlInferenceResult.Status.UNSATISFIABLE).isEmpty())
                        throw new IllegalArgumentException("A modeled statement requires a rendering model or an unsatisfiability proof");
                    bindings = inference.models().stream().flatMap(model -> model.bindings().stream()).toList();
                    for (var model : inference.models())
                        if (model.bound().rendered().recoveryMode() == RenderedSql.RecoveryMode.BOUND_REPLAY
                                && !definition.template().equals(model.bound().rendered().residual()))
                            throw new IllegalArgumentException("A bound model must replay its statement template");
                    var variants = new ArrayList<RawSql.Variant>();
                    var byModel = new ArrayList<Optional<RawSql.Variant>>();
                    for (var model : inference.models()) {
                        var candidate = model.bound().rendered().sql().map(text -> new RawSql.Variant(text, List.of(), sqlSource,
                                model.bindings(), inference.jointInference(), List.of(new RawSql.BindingSnapshot(
                                    SqlBindingSnapshots.bound(model.bound(), definition.location(), model.bindings()), model.combination()))));
                        byModel.add(candidate); candidate.ifPresent(variants::add);
                    }
                    candidates = List.copyOf(byModel);
                    rawSql = new RawSql(variants, definition.issues());
                }
                public SqlDefinition definition() { return definition; }
                public TemplateInferenceResult inference() { return inference; }
                public SqlNode template() { return definition.template(); }
                public List<Binding> bindings() { return bindings; }
                public List<String> templateIssues() { return definition.issues(); }
                public RawSql rawSql() { return rawSql; }
                public Optional<RawSql.Variant> candidate(int modelIndex) { return candidates.get(modelIndex); }
                @Override public boolean equals(Object other) {
                    return this == other || other instanceof Modeled value && definition.equals(value.definition)
                            && inference.equals(value.inference) && sqlSource.equals(value.sqlSource);
                }
                @Override public int hashCode() { return Objects.hash(definition, inference, sqlSource); }
                @Override public String toString() {
                    return "Modeled[definition=" + definition.id() + ", models=" + inference.models().size() + "]";
                }
            }
        }
        /** 旧构造参数在边界校验后转换为明确内容类型，不作为另一份权威存储。 */
        public Statement(Optional<ValueTrace> sql, SqlNode template, List<Binding> bindings,
                         Optional<SourceLocation> batchEntry, List<String> issues, List<String> templateIssues, RawSql rawSql,
                         Optional<SqlDefinition> definition, Optional<TemplateInferenceResult> templateInference) {
            this(sql, batchEntry, issues, content(sql, template, bindings, templateIssues, rawSql, definition, templateInference));
        }
        private static Content content(Optional<ValueTrace> sql, SqlNode template, List<Binding> bindings,
                                       List<String> templateIssues, RawSql rawSql, Optional<SqlDefinition> definition,
                                       Optional<TemplateInferenceResult> inference) {
            Objects.requireNonNull(sql); Objects.requireNonNull(template); Objects.requireNonNull(bindings);
            Objects.requireNonNull(templateIssues); Objects.requireNonNull(rawSql); Objects.requireNonNull(definition); Objects.requireNonNull(inference);
            if (definition.isEmpty()) {
                if (inference.isPresent()) throw new IllegalArgumentException("Template inference requires a definition");
                return new Content.Code(template, bindings, templateIssues, rawSql);
            }
            if (!template.equals(definition.get().template())) throw new IllegalArgumentException("The saved template must match its SQL definition");
            if (!templateIssues.equals(definition.get().issues())) throw new IllegalArgumentException("Definition template issues must come from its definition");
            if (inference.isEmpty()) return new Content.Definition(definition.get(), bindings, rawSql);
            if (!definition.get().issues().containsAll(rawSql.issues()))
                throw new IllegalArgumentException("Template raw SQL diagnostics must be retained by its definition");
            SqlBindingSnapshots.validateModelBindings(bindings, inference.get().models());
            var modeled = new Content.Modeled(definition.get(), inference.get(), sql);
            if (!rawSql.variants().isEmpty()) {
                var remaining = new ArrayList<>(modeled.rawSql().variants());
                for (var variant : rawSql.variants())
                    if (!remaining.remove(variant)) throw new IllegalArgumentException("Raw SQL candidates must preserve their rendering models");
                if (!remaining.isEmpty()) throw new IllegalArgumentException("Raw SQL candidates cannot omit a rendering model");
            }
            return modeled;
        }
        public Statement(Optional<ValueTrace> sql, SqlNode template, List<Binding> bindings, Optional<SourceLocation> batchEntry,
                         List<String> issues, List<String> templateIssues, RawSql rawSql) {
            this(sql, batchEntry, issues, new Content.Code(template, bindings, templateIssues, rawSql));
        }
        public SqlNode template() { return content.template(); }
        public List<Binding> bindings() { return content.bindings(); }
        public List<String> templateIssues() { return content.templateIssues(); }
        public RawSql rawSql() { return content.rawSql(); }
        public Optional<SqlDefinition> definition() {
            return switch (content) {
                case Content.Definition value -> Optional.of(value.definition());
                case Content.Modeled value -> Optional.of(value.definition());
                case Content.Code ignored -> Optional.empty();
            };
        }
        public Optional<TemplateInferenceResult> templateInference() {
            return content instanceof Content.Modeled value ? Optional.of(value.inference()) : Optional.empty();
        }
        /** 显式从传播结果恢复 raw SQL 与模板，不进行参数重演或路径求解。 */
        public static Statement fromTrace(ValueTrace sql, List<Binding> bindings, Optional<SourceLocation> batchEntry,
                                          List<String> issues, Runnable checkpoint) {
            RawSql raw = RawSqlBuilder.build(sql, checkpoint);
            return new Statement(Optional.of(sql), SqlTemplateProjection.template(raw, checkpoint), bindings,
                    batchEntry, issues, raw.issues(), raw);
        }
        /** @deprecated 使用显式 rawSql 的构造器，避免在结果组装时隐式启动恢复。 */
        @Deprecated
        public Statement(Optional<ValueTrace> sql, SqlNode template, List<Binding> bindings,
                         Optional<SourceLocation> batchEntry, List<String> issues, List<String> templateIssues) {
            this(sql, template, bindings, batchEntry, issues, templateIssues,
                    sql.map(trace -> RawSqlBuilder.build(trace, () -> {}))
                            .orElseGet(() -> new RawSql(List.of(), List.of("SQL source is unavailable"))));
        }
        /** 兼容调用方已自行构造完整模板的入口。 */
        @Deprecated
        public Statement(Optional<ValueTrace> sql, SqlNode template, List<Binding> bindings,
                         Optional<SourceLocation> batchEntry, List<String> issues) {
            this(sql, template, bindings, batchEntry, issues, List.of());
        }
        public boolean sourcesComplete() {
            if (definition().isPresent()) return issues.isEmpty() && definition().get().complete()
                    && templateInference().map(TemplateInferenceResult::sourcesComplete).orElse(true);
            return issues.isEmpty() && sql.isPresent() && sql.get().complete() && bindings().stream().allMatch(Binding::complete);
        }
        /** 完整表示恢复过程未遗漏支持范围内的结构，不要求外部参数已有运行时取值。 */
        public boolean templateComplete() { return templateIssues().isEmpty(); }
        /** 参数重演单独判断；没有绑定值或拼接槽位时无需推断。 */
        public boolean inferenceComplete() {
            return SqlAssessmentPolicy.statementValues(this);
        }
        /** 主分析完整性由来源和 raw SQL 决定；模板为派生视图，样例重演另见 inferenceComplete。 */
        public boolean complete() { return sourcesComplete() && (definition().isPresent() ? templateComplete() : rawSql().complete()); }
        public SqlAssessment assessment() {
            return SqlAssessmentPolicy.statement(this);
        }
        public List<JointSqlInferenceResult> jointInferences() {
            if (templateInference().isPresent()) return templateInference().get().jointInference().stream().toList();
            return rawSql().variants().stream().flatMap(variant -> variant.jointInference().stream()).toList();
        }
    }
    public record Binding(String key, ValueTrace keyTrace, ValueTrace value, SourceLocation location,
                          ParameterValueInferenceResult inference, String options) {
        public Binding { Objects.requireNonNull(key); Objects.requireNonNull(keyTrace); Objects.requireNonNull(value); Objects.requireNonNull(location); Objects.requireNonNull(inference); Objects.requireNonNull(options); }
        public Binding(String key, ValueTrace keyTrace, ValueTrace value, SourceLocation location, ParameterValueInferenceResult inference) {
            this(key, keyTrace, value, location, inference, "");
        }
        public Binding(String key, ValueTrace keyTrace, ValueTrace value, SourceLocation location) {
            this(key, keyTrace, value, location, new ParameterValueInferenceResult(ParameterValueInferencer.variableName(value.root()),
                    value.variable().type(), List.of(), List.of("Parameter not inferred")));
        }
        /** 只判断溯源完整性；推断是否成功通过 inference.complete() 获取。 */
        public boolean complete() { return keyTrace.complete() && value.complete(); }
    }
}
