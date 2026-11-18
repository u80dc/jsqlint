package org.u80dc.jsqlint.core;

import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.parameter.*;

import java.util.*;

/** 只渲染已保存的联合组合或显式模板参数，关闭分析输入后仍可使用。 @author u80dc */
public final class SqlSampleRenderer {
    private SqlSampleRenderer() {}

    public static List<SqlSample> render(SqlResult source) {
        Objects.requireNonNull(source);
        return switch (source) {
            case SqlExtraction extraction -> render(extraction);
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

    private static SqlNodeRenderer.RenderedSql unavailable(SqlTemplateResult source) {
        return unavailable(source, SqlNodeRenderer.RenderedSql.RecoveryMode.LITERAL_CONTINUATION);
    }

    private static SqlNodeRenderer.RenderedSql unavailable(SqlTemplateResult source, SqlNodeRenderer.RenderedSql.RecoveryMode mode) {
        return new SqlNodeRenderer.RenderedSql(Optional.empty(), source.template(), Map.of(),
                List.of(new SqlDiagnostic("RENDER_UNAVAILABLE", "No saved rendering snapshot; provide explicit sample parameters")), mode);
    }

    private static SqlSample definition(SqlTemplateResult source, SqlNodeRenderer.BoundSql bound) {
        var definition = source.definition().orElseThrow();
        var issues = new LinkedHashSet<>(definition.issues());
        source.diagnostics().forEach(diagnostic -> issues.add(diagnostic.code() + ": " + diagnostic.message()));
        bound.rendered().diagnostics().forEach(diagnostic -> issues.add(diagnostic.code() + ": " + diagnostic.message()));
        var bindings = SqlBindingSnapshots.bound(bound, definition.location(), List.of());
        boolean sources = source.assessment().sourcesComplete();
        boolean limited = bound.rendered().diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code().equals("RENDER_LIMIT") || diagnostic.code().equals("RENDER_UNAVAILABLE"));
        return new SqlSample(source, Optional.empty(), bound.rendered(), bindings,
                sources && !limited ? SqlSample.Feasibility.UNVERIFIED : SqlSample.Feasibility.UNKNOWN,
                new SqlAssessment(sources, source.assessment().textComplete(), bound.valuesComplete(), Optional.empty()),
                new ArrayList<>(issues), Optional.empty(), Optional.empty(), source.definition());
    }

    private static SqlSample template(SqlTemplateResult source, SqlNodeRenderer.RenderedSql rendered) {
        boolean sources = source.assessment().sourcesComplete();
        var issues = new LinkedHashSet<String>();
        source.diagnostics().forEach(diagnostic -> issues.add(diagnostic.code() + ": " + diagnostic.message()));
        rendered.diagnostics().forEach(diagnostic -> issues.add(diagnostic.code() + ": " + diagnostic.message()));
        return new SqlSample(source, Optional.empty(), rendered, List.of(),
                sources ? SqlSample.Feasibility.UNVERIFIED : SqlSample.Feasibility.UNKNOWN,
                new SqlAssessment(sources, source.assessment().textComplete(), rendered.complete(), Optional.empty()),
                new ArrayList<>(issues), Optional.empty(), Optional.empty());
    }

    private static List<SqlSample> render(SqlExtraction source) {
        List<SqlSample> samples = new ArrayList<>();
        for (var statement : source.statements()) {
            if (statement.templateInference().isPresent()) {
                for (var model : statement.templateInference().get().models()) samples.add(templateSample(source, statement, model));
                continue;
            }
            if (statement.rawSql().variants().isEmpty()) {
                samples.add(sample(source, statement, Optional.empty(), Optional.empty(), Map.of(),
                        unknownBindings(statement, "SQL source is unavailable"), SqlSample.Feasibility.UNKNOWN,
                        List.of("SQL source is unavailable")));
            }
            for (var variant : statement.rawSql().variants()) {
                var joint = variant.jointInference();
                if (joint.isPresent() && joint.get().status() == JointSqlInferenceResult.Status.UNSATISFIABLE) continue;
                if (joint.isEmpty() || joint.get().combinations().isEmpty()) {
                    String reason = joint.isEmpty() ? "Joint path inference was not requested" : "No joint parameter combination is available";
                    samples.add(sample(source, statement, Optional.of(variant), Optional.empty(), Map.of(),
                            unknownBindings(statement, reason), joint.isEmpty() ? SqlSample.Feasibility.UNVERIFIED : SqlSample.Feasibility.UNKNOWN,
                            List.of(reason)));
                    continue;
                }
                // 不将独立参数的 possibleValues 再组合，SQL 槽位和绑定值只取自同一个 Combination。
                for (var combination : joint.get().combinations()) {
                    if (combination.status() == JointSqlInferenceResult.Status.UNSATISFIABLE) continue;
                    Map<String, Object> parameters = new LinkedHashMap<>();
                    combination.parameters().forEach((name, value) -> parameters.put(name,
                            value.value() instanceof SqlValue.Known known
                                    ? known.value() instanceof String ? known.value() : new SqlValue.Unknown("SQL slot is not a string: " + name)
                                    : value.value()));
                    var issues = new ArrayList<>(combination.issues());
                    if (combination.bindings().size() != statement.bindings().size()) issues.add("Joint binding snapshot size differs from its source");
                    var bindings = variant.bindingSnapshots().stream().filter(snapshot -> snapshot.combination().filter(combination::equals).isPresent())
                            .findFirst().map(RawSql.BindingSnapshot::bindings)
                            .orElseGet(() -> SqlBindingSnapshots.unknown(statement.bindings(), "Joint binding snapshot is unavailable"));
                    samples.add(sample(source, statement, Optional.of(variant), Optional.of(combination), parameters, bindings,
                            combination.status() == JointSqlInferenceResult.Status.SATISFIABLE
                                    ? SqlSample.Feasibility.SATISFIABLE : SqlSample.Feasibility.UNKNOWN, issues));
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
        boolean sources = source.issues().isEmpty() && statement.sourcesComplete();
        boolean values = model.bound().valuesComplete() && model.bindings().stream().allMatch(binding -> binding.inference().complete())
                && model.combination().map(value -> value.parameters().values()
                .stream().allMatch(InferredParameterValue::complete)).orElse(saved.parameters().isEmpty());
        var feasibility = model.combination().map(value -> value.status() == JointSqlInferenceResult.Status.SATISFIABLE
                ? SqlSample.Feasibility.SATISFIABLE : SqlSample.Feasibility.UNKNOWN)
                .orElse(saved.jointInference().isPresent() ? SqlSample.Feasibility.UNKNOWN : SqlSample.Feasibility.UNVERIFIED);
        if (!sources || !statement.templateComplete() || feasibility == SqlSample.Feasibility.SATISFIABLE && !values)
            feasibility = SqlSample.Feasibility.UNKNOWN;
        var assessment = new SqlAssessment(sources, statement.templateComplete(), values,
                saved.jointInference().map(JointSqlInferenceResult::coverageComplete));
        return new SqlSample(source, statement.batchEntry(), rendered, bindings, feasibility, assessment,
                new ArrayList<>(issues), model.rawSql(), model.combination(), statement.definition());
    }

    private static SqlSample sample(SqlExtraction source, SqlExtraction.Statement statement,
                                    Optional<RawSql.Variant> variant, Optional<JointSqlInferenceResult.Combination> combination,
                                    Map<String, Object> parameters, List<SqlSample.Binding> bindings,
                                    SqlSample.Feasibility feasibility, List<String> localIssues) {
        var rendered = SqlNodeRenderer.render(variant.map(RawSql.Variant::template).orElse(statement.template()), parameters);
        var issues = new LinkedHashSet<>(source.issues());
        issues.addAll(statement.issues()); issues.addAll(statement.rawSql().issues()); issues.addAll(localIssues);
        variant.flatMap(RawSql.Variant::jointInference).ifPresent(joint -> issues.addAll(joint.issues()));
        rendered.diagnostics().forEach(diagnostic -> issues.add(diagnostic.code() + ": " + diagnostic.message()));
        boolean values = rendered.complete() && bindings.stream().allMatch(binding ->
                binding.keyValue() instanceof SqlValue.Known && binding.value() instanceof SqlValue.Known)
                && combination.map(value -> value.bindings().size() == statement.bindings().size()
                    && value.parameters().values().stream().allMatch(InferredParameterValue::complete)
                    && value.bindings().stream().allMatch(binding -> binding.keyValue().complete() && binding.value().complete())).orElse(true);
        boolean sources = source.issues().isEmpty() && statement.sourcesComplete();
        var assessment = new SqlAssessment(sources, statement.rawSql().complete(), values,
                variant.flatMap(RawSql.Variant::jointInference).map(JointSqlInferenceResult::coverageComplete));
        if (!sources || !statement.rawSql().complete()
                || feasibility == SqlSample.Feasibility.SATISFIABLE && (!assessment.textComplete() || !values))
            feasibility = SqlSample.Feasibility.UNKNOWN;
        return new SqlSample(source, statement.batchEntry(), rendered, bindings, feasibility, assessment,
                new ArrayList<>(issues), variant, combination);
    }

    private static List<SqlSample.Binding> unknownBindings(SqlExtraction.Statement statement, String reason) {
        return SqlBindingSnapshots.unknown(statement.bindings(), reason);
    }
}
