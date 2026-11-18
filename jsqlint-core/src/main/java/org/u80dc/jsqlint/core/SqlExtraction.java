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
        var coverage = statements.stream().map(statement -> statement.assessment().pathCoverageComplete()).toList();
        return new SqlAssessment(issues.isEmpty() && statements.stream().allMatch(Statement::sourcesComplete),
                !statements.isEmpty() && statements.stream().allMatch(statement -> statement.assessment().textComplete()),
                statements.stream().allMatch(Statement::inferenceComplete),
                coverage.isEmpty() || coverage.stream().anyMatch(Optional::isEmpty) ? Optional.empty()
                        : Optional.of(coverage.stream().allMatch(value -> value.orElseThrow())));
    }
    public record Statement(Optional<ValueTrace> sql, SqlNode template, List<Binding> bindings,
                            Optional<SourceLocation> batchEntry, List<String> issues, List<String> templateIssues, RawSql rawSql,
                            Optional<SqlDefinition> definition, Optional<TemplateInferenceResult> templateInference) {
        public Statement {
            Objects.requireNonNull(sql); Objects.requireNonNull(template); bindings = List.copyOf(bindings);
            Objects.requireNonNull(batchEntry); issues = List.copyOf(issues); templateIssues = List.copyOf(templateIssues); Objects.requireNonNull(rawSql);
            Objects.requireNonNull(definition); Objects.requireNonNull(templateInference);
            // 动态模板的不同模型可以产生不同绑定顺序，保留各 variant 的快照。
            if (definition.isEmpty()) rawSql = rawSql.withBindings(bindings);
            if (templateInference.isPresent() && definition.isEmpty()) throw new IllegalArgumentException("Template inference requires a definition");
            if (definition.isPresent() && !template.equals(definition.get().template()))
                throw new IllegalArgumentException("The saved template must match its SQL definition");
            if (templateInference.isPresent()) {
                var models = templateInference.get().models();
                if (!bindings.equals(models.stream().flatMap(model -> model.bindings().stream()).distinct().toList()))
                    throw new IllegalArgumentException("Statement bindings must preserve their template models");
                for (var model : models) {
                    if (model.bound().rendered().recoveryMode() == RenderedSql.RecoveryMode.BOUND_REPLAY
                            && !template.equals(model.bound().rendered().residual()))
                        throw new IllegalArgumentException("A bound model must replay its statement template");
                    if (model.rawSql().isPresent() && !rawSql.variants().contains(model.rawSql().get()))
                        throw new IllegalArgumentException("The statement must preserve each associated raw SQL model");
                }
            }
        }
        public Statement(Optional<ValueTrace> sql, SqlNode template, List<Binding> bindings, Optional<SourceLocation> batchEntry,
                         List<String> issues, List<String> templateIssues, RawSql rawSql) {
            this(sql, template, bindings, batchEntry, issues, templateIssues, rawSql, Optional.empty(), Optional.empty());
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
            if (definition.isPresent()) return issues.isEmpty() && definition.get().complete()
                    && templateInference.map(TemplateInferenceResult::sourcesComplete).orElse(true);
            return issues.isEmpty() && sql.isPresent() && sql.get().complete() && bindings.stream().allMatch(Binding::complete);
        }
        /** 完整表示恢复过程未遗漏支持范围内的结构，不要求外部参数已有运行时取值。 */
        public boolean templateComplete() { return templateIssues.isEmpty(); }
        /** 参数重演单独判断；没有绑定值或拼接槽位时无需推断。 */
        public boolean inferenceComplete() {
            if (templateInference.isPresent()) return templateInference.get().valuesComplete();
            return bindings.stream().allMatch(binding -> binding.inference().complete())
                    && rawSql.variants().stream().flatMap(variant -> variant.parameters().stream())
                    .allMatch(ParameterValueInferenceResult::complete);
        }
        /** 主分析完整性由来源和 raw SQL 决定；模板为派生视图，样例重演另见 inferenceComplete。 */
        public boolean complete() { return sourcesComplete() && (definition.isPresent() ? templateComplete() : rawSql.complete()); }
        public SqlAssessment assessment() {
            if (definition.isPresent()) return new SqlAssessment(sourcesComplete(), templateComplete(), inferenceComplete(),
                    templateInference.flatMap(TemplateInferenceResult::jointInference).map(org.u80dc.jsqlint.core.parameter.JointSqlInferenceResult::coverageComplete));
            return new SqlAssessment(sourcesComplete(), rawSql.complete(), inferenceComplete(), SqlAssessment.pathCoverage(rawSql.variants()));
        }
        public List<JointSqlInferenceResult> jointInferences() {
            if (templateInference.isPresent()) return templateInference.get().jointInference().stream().toList();
            return rawSql.variants().stream().flatMap(variant -> variant.jointInference().stream()).toList();
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
