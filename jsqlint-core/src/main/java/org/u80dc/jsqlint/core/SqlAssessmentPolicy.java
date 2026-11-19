package org.u80dc.jsqlint.core;

import org.u80dc.jsqlint.core.node.SqlNodeRenderer.RenderedSql;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.core.parameter.*;

import java.util.*;

/** 汇总保存的阶段事实；不追踪来源、重演参数或执行渲染。 */
final class SqlAssessmentPolicy {
    private SqlAssessmentPolicy() {}

    static boolean bindingsInferred(List<SqlExtraction.Binding> bindings) {
        return bindings.stream().allMatch(binding -> binding.inference().complete());
    }

    static boolean rawValues(RawSql.Variant variant) {
        return variant.parameters().stream().allMatch(ParameterValueInferenceResult::complete)
                && bindingsInferred(variant.bindings())
                && (!variant.bindings().isEmpty() || variant.bindingSnapshots().stream()
                    .allMatch(snapshot -> finalBindingsKnown(snapshot.bindings())));
    }

    static boolean modelValues(TemplateInferenceResult.BoundModel model, boolean noParameters) {
        return model.bound().valuesComplete() && bindingsInferred(model.bindings())
                && model.combination().map(SqlAssessmentPolicy::parametersInferred).orElse(noParameters);
    }

    static boolean templateValues(TemplateInferenceResult saved) {
        return !saved.models().isEmpty() && saved.models().stream()
                .allMatch(model -> modelValues(model, saved.parameters().isEmpty()));
    }

    static boolean statementValues(SqlExtraction.Statement statement) {
        return statement.templateInference().map(SqlAssessmentPolicy::templateValues).orElseGet(() ->
                bindingsInferred(statement.bindings()) && statement.rawSql().variants().stream()
                    .allMatch(SqlAssessmentPolicy::rawValues));
    }

    static SqlAssessment statement(SqlExtraction.Statement statement) {
        if (statement.definition().isPresent()) return new SqlAssessment(statement.sourcesComplete(), statement.templateComplete(),
                statementValues(statement), statement.templateInference().flatMap(TemplateInferenceResult::jointInference)
                    .map(JointSqlInferenceResult::coverageComplete));
        return new SqlAssessment(statement.sourcesComplete(), statement.rawSql().complete(), statementValues(statement),
                SqlAssessment.pathCoverage(statement.rawSql().variants()));
    }

    static SqlAssessment extraction(SqlExtraction source) {
        var statements = source.statements().stream().map(SqlAssessmentPolicy::statement).toList();
        var coverage = statements.stream().map(SqlAssessment::pathCoverageComplete).toList();
        return new SqlAssessment(source.issues().isEmpty() && statements.stream().allMatch(SqlAssessment::sourcesComplete),
                !statements.isEmpty() && statements.stream().allMatch(SqlAssessment::textComplete),
                statements.stream().allMatch(SqlAssessment::valuesComplete),
                coverage.isEmpty() || coverage.stream().anyMatch(Optional::isEmpty) ? Optional.empty()
                        : Optional.of(coverage.stream().allMatch(value -> value.orElseThrow())));
    }

    static SqlAssessment template(SqlTemplateResult source) {
        boolean definitionComplete = source.definition().map(SqlDefinition::complete).orElse(true);
        boolean sources = definitionComplete && unaffected(source, SqlAssessment.Dimension.SOURCES);
        boolean text = definitionComplete && unaffected(source, SqlAssessment.Dimension.TEXT);
        boolean values = source.definition().isPresent()
                ? source.bound().map(bound -> bound.valuesComplete()).orElse(false)
                : source.rendered().map(RenderedSql::complete).orElse(false);
        return new SqlAssessment(sources, text, values && unaffected(source, SqlAssessment.Dimension.VALUES), Optional.empty());
    }

    private static boolean unaffected(SqlTemplateResult source, SqlAssessment.Dimension dimension) {
        return source.diagnostics().stream().noneMatch(diagnostic -> diagnostic.affects(dimension));
    }

    static SqlAssessment templateSample(SqlTemplateResult source, boolean valuesComplete) {
        var saved = template(source);
        return new SqlAssessment(saved.sourcesComplete(), saved.textComplete(), valuesComplete, Optional.empty());
    }

    static SqlAssessment modelSample(SqlExtraction source, SqlExtraction.Statement statement,
                                     TemplateInferenceResult.BoundModel model) {
        var saved = statement.templateInference().orElseThrow();
        return new SqlAssessment(source.issues().isEmpty() && statement.sourcesComplete(), statement.templateComplete(),
                modelValues(model, saved.parameters().isEmpty()), saved.jointInference().map(JointSqlInferenceResult::coverageComplete));
    }

    static SqlAssessment rawSample(SqlExtraction source, SqlExtraction.Statement statement,
                                   Optional<RawSql.Variant> variant, RenderedSql rendered,
                                   List<SqlSample.Binding> bindings, Optional<JointSqlInferenceResult.Combination> combination) {
        boolean values = rendered.complete() && finalBindingsKnown(bindings)
                && combination.map(value -> value.bindings().size() == statement.bindings().size()
                    && parametersInferred(value) && value.bindings().stream()
                        .allMatch(binding -> binding.keyValue().complete() && binding.value().complete())).orElse(true);
        return new SqlAssessment(source.issues().isEmpty() && statement.sourcesComplete(), statement.rawSql().complete(), values,
                variant.flatMap(RawSql.Variant::jointInference).map(JointSqlInferenceResult::coverageComplete));
    }

    private static boolean parametersInferred(JointSqlInferenceResult.Combination combination) {
        return combination.parameters().values().stream().allMatch(InferredParameterValue::complete);
    }

    private static boolean finalBindingsKnown(List<SqlSample.Binding> bindings) {
        return bindings.stream().allMatch(binding -> binding.keyValue() instanceof SqlValue.Known && binding.value() instanceof SqlValue.Known);
    }

    static SqlSample.Feasibility feasibility(SqlSample.Feasibility requested, SqlAssessment assessment) {
        return !assessment.sourcesComplete() || !assessment.textComplete()
                || requested == SqlSample.Feasibility.SATISFIABLE && !assessment.valuesComplete()
                ? SqlSample.Feasibility.UNKNOWN : requested;
    }
}
