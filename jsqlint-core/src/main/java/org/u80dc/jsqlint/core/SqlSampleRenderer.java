package org.u80dc.jsqlint.core;

import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.parameter.*;

import java.util.*;

/** 只渲染已保存的联合组合或显式模板参数，关闭分析输入后仍可使用。 @author u80dc */
public final class SqlSampleRenderer {
    private SqlSampleRenderer() {}

    public static List<SqlSample> render(SqlResult source) {
        return render(source, Optional.empty());
    }

    /** 显式样例派生可限制文本工作并协作取消；已有模板模型继续读取冻结快照。 */
    public static List<SqlSample> render(SqlResult source, SqlEvaluationContext context) {
        Objects.requireNonNull(context);
        return context.evaluate(() -> render(source, Optional.of(context)));
    }

    private static List<SqlSample> render(SqlResult source, Optional<SqlEvaluationContext> context) {
        Objects.requireNonNull(source);
        context.ifPresent(SqlEvaluationContext::check);
        return switch (source) {
            case SqlExtraction extraction -> render(extraction, context);
            case SqlTemplateResult template -> template.definition().isPresent()
                    ? List.of(definition(template, template.bound().orElseGet(() -> new SqlNodeRenderer.BoundSql(
                        unavailable(template, SqlNodeRenderer.RenderedSql.RecoveryMode.BOUND_REPLAY), List.of()))))
                    : List.of(template(template, template.rendered().orElseGet(() -> unavailable(template))));
        };
    }

    /** 模板参数由调用方提供；完成渲染不提升为已验证 Java 路径。 */
    public static SqlSample render(SqlTemplateResult source, Map<String, ?> parameters) {
        if (source.definition().isPresent()) {
            return definition(source, source.definition().get().render(parameters));
        }
        return template(source, SqlNodeRenderer.render(source.template(), parameters));
    }

    public static SqlSample render(SqlTemplateResult source, Map<String, ?> parameters, SqlEvaluationContext context) {
        Objects.requireNonNull(source); Objects.requireNonNull(context);
        if (source.definition().isPresent()) return definition(source, source.definition().get().render(parameters, context));
        return template(source, SqlNodeRenderer.render(source.template(), parameters, SqlExpressionEvaluator.simple(), context));
    }

    private static SqlNodeRenderer.RenderedSql unavailable(SqlTemplateResult source) {
        return unavailable(source, SqlNodeRenderer.RenderedSql.RecoveryMode.LITERAL_CONTINUATION);
    }

    private static SqlNodeRenderer.RenderedSql unavailable(SqlTemplateResult source, SqlNodeRenderer.RenderedSql.RecoveryMode mode) {
        return new SqlNodeRenderer.RenderedSql(Optional.empty(), source.template(), Map.of(),
                List.of(new SqlDiagnostic("RENDER_UNAVAILABLE", "No saved rendering snapshot; provide explicit sample parameters", SqlDiagnostic.Effect.UNAVAILABLE)), mode);
    }

    private static SqlSample definition(SqlTemplateResult source, SqlNodeRenderer.BoundSql bound) {
        var definition = source.definition().orElseThrow();
        var issues = new LinkedHashSet<>(definition.issues());
        source.diagnostics().forEach(diagnostic -> issues.add(diagnostic.code() + ": " + diagnostic.message()));
        bound.rendered().diagnostics().forEach(diagnostic -> issues.add(diagnostic.code() + ": " + diagnostic.message()));
        var bindings = SqlBindingSnapshots.bound(bound, definition.location(), List.of());
        boolean limited = bound.rendered().diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.limited() || diagnostic.unavailable());
        var assessment = SqlAssessmentPolicy.templateSample(source, bound.valuesComplete());
        var feasibility = SqlAssessmentPolicy.feasibility(limited ? SqlSample.Feasibility.UNKNOWN : SqlSample.Feasibility.UNVERIFIED,
                assessment);
        return new SqlSample(source, Optional.empty(), bound.rendered(), bindings,
                feasibility, assessment,
                new ArrayList<>(issues), Optional.empty(), Optional.empty(), source.definition(),bound.rewrites());
    }

    private static SqlSample template(SqlTemplateResult source, SqlNodeRenderer.RenderedSql rendered) {
        var issues = new LinkedHashSet<String>();
        source.diagnostics().forEach(diagnostic -> issues.add(diagnostic.code() + ": " + diagnostic.message()));
        rendered.diagnostics().forEach(diagnostic -> issues.add(diagnostic.code() + ": " + diagnostic.message()));
        var assessment = SqlAssessmentPolicy.templateSample(source, rendered.complete());
        return new SqlSample(source, Optional.empty(), rendered, List.of(),
                SqlAssessmentPolicy.feasibility(SqlSample.Feasibility.UNVERIFIED, assessment), assessment,
                new ArrayList<>(issues), Optional.empty(), Optional.empty());
    }

    private static List<SqlSample> render(SqlExtraction source, Optional<SqlEvaluationContext> context) {
        List<SqlSample> samples = new ArrayList<>();
        for (var statement : source.statements()) {
            context.ifPresent(SqlEvaluationContext::check);
            if (statement.templateInference().isPresent()) {
                for (var model : statement.templateInference().get().models()) {
                    context.ifPresent(SqlEvaluationContext::check);
                    samples.add(templateSample(source, statement, model));
                }
                continue;
            }
            if (statement.rawSql().variants().isEmpty()) {
                samples.add(sample(source, statement, Optional.empty(), Optional.empty(), Map.of(),
                        unknownBindings(statement, "SQL source is unavailable"), SqlSample.Feasibility.UNKNOWN,
                        List.of("SQL source is unavailable"), context));
            }
            for (var variant : statement.rawSql().variants()) {
                context.ifPresent(SqlEvaluationContext::check);
                var joint = variant.jointInference();
                if (joint.isPresent() && joint.get().status() == JointSqlInferenceResult.Status.UNSATISFIABLE) continue;
                if (joint.isEmpty() || joint.get().combinations().isEmpty()) {
                    String reason = joint.isEmpty() ? "Joint path inference was not requested" : "No joint parameter combination is available";
                    samples.add(sample(source, statement, Optional.of(variant), Optional.empty(), Map.of(),
                            unknownBindings(statement, reason), joint.isEmpty() ? SqlSample.Feasibility.UNVERIFIED : SqlSample.Feasibility.UNKNOWN,
                            List.of(reason), context));
                    continue;
                }
                // 不将独立参数的 possibleValues 再组合，SQL 槽位和绑定值只取自同一个 Combination。
                for (var snapshot : variant.bindingSnapshots()) {
                    context.ifPresent(SqlEvaluationContext::check);
                    var combination = snapshot.combination().orElseThrow();
                    Map<String, Object> parameters = new LinkedHashMap<>();
                    combination.parameters().forEach((name, value) -> parameters.put(name,
                            value.value() instanceof SqlValue.Known known
                                    ? known.value() instanceof String ? known.value() : new SqlValue.Unknown("SQL slot is not a string: " + name)
                                    : value.value()));
                    var issues = new ArrayList<>(combination.issues());
                    samples.add(sample(source, statement, Optional.of(variant), Optional.of(combination), parameters, snapshot.bindings(),
                            combination.status() == JointSqlInferenceResult.Status.SATISFIABLE
                                    ? SqlSample.Feasibility.SATISFIABLE : SqlSample.Feasibility.UNKNOWN, issues, context));
                }
            }
        }
        return List.copyOf(samples);
    }

    private static SqlSample templateSample(SqlExtraction source, SqlExtraction.Statement statement,
                                             TemplateInferenceResult.BoundModel model) {
        var saved = statement.templateInference().orElseThrow();
        var rendered = model.bound().rendered();
        var issues = new LinkedHashSet<>(source.issues());
        issues.addAll(statement.issues()); issues.addAll(statement.templateIssues()); issues.addAll(saved.issues());
        issues.addAll(model.issues()); model.combination().ifPresent(value -> issues.addAll(value.issues()));
        rendered.diagnostics().forEach(diagnostic -> issues.add(diagnostic.code() + ": " + diagnostic.message()));
        var bindings = model.bindingSnapshot(statement.definition().orElseThrow().location());
        var assessment = SqlAssessmentPolicy.modelSample(source, statement, model);
        var feasibility = model.combination().map(value -> value.status() == JointSqlInferenceResult.Status.SATISFIABLE
                ? SqlSample.Feasibility.SATISFIABLE : SqlSample.Feasibility.UNKNOWN)
                .orElse(saved.jointInference().isPresent() ? SqlSample.Feasibility.UNKNOWN : SqlSample.Feasibility.UNVERIFIED);
        feasibility = SqlAssessmentPolicy.feasibility(feasibility, assessment);
        return new SqlSample(source, statement.batchEntry(), rendered, bindings, feasibility, assessment,
                new ArrayList<>(issues), model.rawSql(), model.combination(), statement.definition(),model.bound().rewrites());
    }

    private static SqlSample sample(SqlExtraction source, SqlExtraction.Statement statement,
                                    Optional<RawSql.Variant> variant, Optional<JointSqlInferenceResult.Combination> combination,
                                    Map<String, Object> parameters, List<SqlSample.Binding> bindings,
                                    SqlSample.Feasibility feasibility, List<String> localIssues, Optional<SqlEvaluationContext> context) {
        var rendered = variant.map(value -> project(value, parameters, context, statement.template())).orElseGet(() -> context
                .map(execution -> SqlNodeRenderer.render(statement.template(), parameters, SqlExpressionEvaluator.simple(), execution))
                .orElseGet(() -> SqlNodeRenderer.render(statement.template(), parameters)));
        var issues = new LinkedHashSet<>(source.issues());
        issues.addAll(statement.issues()); issues.addAll(statement.rawSql().issues()); issues.addAll(localIssues);
        variant.flatMap(RawSql.Variant::jointInference).ifPresent(joint -> issues.addAll(joint.issues()));
        rendered.diagnostics().forEach(diagnostic -> issues.add(diagnostic.code() + ": " + diagnostic.message()));
        var assessment = SqlAssessmentPolicy.rawSample(source, statement, variant, rendered, bindings, combination);
        feasibility = SqlAssessmentPolicy.feasibility(feasibility, assessment);
        return new SqlSample(source, statement.batchEntry(), rendered, bindings, feasibility, assessment,
                new ArrayList<>(issues), variant, combination);
    }

    private static List<SqlSample.Binding> unknownBindings(SqlExtraction.Statement statement, String reason) {
        return SqlBindingSnapshots.unknown(statement.bindings(), reason);
    }

    /** 已保存 SQL 槽位是转换后的字符串，直接按位置投影，不重新解释表达式或启动另一套默认预算。 */
    private static SqlNodeRenderer.RenderedSql project(RawSql.Variant variant, Map<String, Object> parameters,
                                                       Optional<SqlEvaluationContext> context, SqlNode fallback) {
        var projection = new Projection(context);
        try {
            int start = 0;
            for (var slot : variant.placeholders()) {
                projection.literal(variant.text().substring(start, slot.start()));
                Object value = parameters.get(slot.name());
                if (value instanceof String text) projection.literal(text);
                else projection.unknown(slot.name(), value);
                start = slot.end();
            }
            projection.literal(variant.text().substring(start));
            SqlNode residual = projection.complete ? new StaticTextSqlNode(projection.text.toString()) : new MixedSqlNode(projection.parts);
            return new SqlNodeRenderer.RenderedSql(projection.complete ? Optional.of(projection.text.toString()) : Optional.empty(),
                    residual, parameters, List.copyOf(projection.diagnostics));
        } catch (ProjectionLimit limit) {
            context.ifPresent(execution -> execution.limit(limit.getMessage()));
            return new SqlNodeRenderer.RenderedSql(Optional.empty(), fallback, parameters,
                    List.of(new SqlDiagnostic("RENDER_LIMIT", limit.getMessage(), SqlDiagnostic.Effect.LIMIT)));
        }
    }

    private static final class Projection {
        final Optional<SqlEvaluationContext> context;
        final StringBuilder text = new StringBuilder();
        final List<SqlNode> parts = new ArrayList<>();
        final Set<SqlDiagnostic> diagnostics = new LinkedHashSet<>();
        int steps;
        boolean complete = true;
        Projection(Optional<SqlEvaluationContext> context) { this.context = context; }
        void step() {
            context.ifPresent(execution -> {
                execution.check();
                if (++steps > execution.renderBudgets().steps()) throw new ProjectionLimit("SQL sample projection work budget exceeded");
            });
        }
        void literal(String value) {
            step();
            long length = (long) text.length() + value.length();
            if (length > Integer.MAX_VALUE || context.map(execution -> length > execution.renderBudgets().length()).orElse(false))
                throw new ProjectionLimit("SQL sample projection text length budget exceeded");
            text.append(value);
            if (!value.isEmpty()) parts.add(new StaticTextSqlNode(value));
        }
        void unknown(String name, Object value) {
            step(); complete = false; parts.add(ValueSqlNode.raw(name, "null"));
            String reason = value instanceof SqlValue.Unknown unknown ? unknown.reason() : "Saved SQL slot is unavailable";
            diagnostics.add(new SqlDiagnostic("UNKNOWN_VALUE", name + ": " + reason));
        }
    }

    private static final class ProjectionLimit extends RuntimeException {
        ProjectionLimit(String message) { super(message, null, false, false); }
    }
}
