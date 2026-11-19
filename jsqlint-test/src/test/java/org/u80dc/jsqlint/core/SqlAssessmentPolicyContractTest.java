package org.u80dc.jsqlint.core;

import org.junit.jupiter.api.Test;
import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.parameter.*;
import org.u80dc.jsqlint.core.spi.*;
import sootup.java.core.JavaIdentifierFactory;

import java.net.URI;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 同一组阶段事实应在结果和样例中使用同一策略，诊断代码不决定完整性。 */
class SqlAssessmentPolicyContractTest {
    private static final SourceLocation LOCATION = new SourceLocation(URI.create("memory:/query.sql"), "query");

    @Test void extensionRenderingDiagnosticAffectsValuesWithoutInvalidatingItsSavedSource() {
        var result = definitionResult(new AnalysisDiagnostic("CUSTOM_EXTENSION_FAILURE", AnalysisListener.Stage.ANALYZING,
                Optional.of(LOCATION), true, "sample projection failed", Set.of(SqlAssessment.Dimension.VALUES)));
        assertEquals(new SqlAssessment(true, true, false, Optional.empty()), result.assessment());
    }

    @Test void identicalDiagnosticCodesCanDescribeDifferentAffectedDimensions() {
        var valueFailure = definitionResult(new AnalysisDiagnostic("SAME_CODE", AnalysisListener.Stage.ANALYZING,
                Optional.of(LOCATION), true, "value failure", Set.of(SqlAssessment.Dimension.VALUES)));
        var sourceFailure = definitionResult(new AnalysisDiagnostic("SAME_CODE", AnalysisListener.Stage.ANALYZING,
                Optional.of(LOCATION), true, "source failure", Set.of(SqlAssessment.Dimension.SOURCES)));
        assertEquals(new SqlAssessment(true, true, false, Optional.empty()), valueFailure.assessment());
        assertEquals(new SqlAssessment(false, true, true, Optional.empty()), sourceFailure.assessment());
        assertEquals(SqlSample.Feasibility.UNKNOWN, sourceFailure.samples().getFirst().feasibility());
    }

    @Test void oldDiagnosticConstructorUsesConservativeFactsInsteadOfSpecialCodeNames() {
        var result = definitionResult(new AnalysisDiagnostic("SQL_RENDER_LIMIT", AnalysisListener.Stage.ANALYZING,
                Optional.of(LOCATION), true, "unspecified stage impact"));
        assertEquals(new SqlAssessment(false, false, true, Optional.empty()), result.assessment());
        var diagnostic = new AnalysisDiagnostic("NOTE", AnalysisListener.Stage.ANALYZING, Optional.empty(), false, "note");
        assertTrue(diagnostic.affectedDimensions().isEmpty());
    }

    @Test void diagnosticDimensionsAreFrozenAtThePublicBoundary() {
        var dimensions = EnumSet.of(SqlAssessment.Dimension.VALUES);
        var diagnostic = new AnalysisDiagnostic("FAILURE", AnalysisListener.Stage.ANALYZING, Optional.empty(), true, "failed", dimensions);
        dimensions.add(SqlAssessment.Dimension.SOURCES);
        assertEquals(Set.of(SqlAssessment.Dimension.VALUES), diagnostic.affectedDimensions());
        assertThrows(UnsupportedOperationException.class, () -> diagnostic.affectedDimensions().clear());
    }

    @Test void sampleFeasibilityUsesDiagnosticEffectsInsteadOfCodeNames() {
        var definition = new SqlDefinition("query", LOCATION, SqlNodeBuilder.templateText("SELECT #{id}"));
        for (var effect : SqlDiagnostic.Effect.values()) {
            var rendered = new SqlNodeRenderer.RenderedSql(Optional.empty(), definition.template(), Map.of(),
                    List.of(new SqlDiagnostic("SAME_CODE", "rendering incomplete", effect)),
                    SqlNodeRenderer.RenderedSql.RecoveryMode.BOUND_REPLAY);
            var source = new SqlTemplateResult("test", LOCATION, definition.template(), Optional.empty(), List.of(),
                    Optional.of(definition), Optional.of(new SqlNodeRenderer.BoundSql(rendered, List.of())));
            assertEquals(effect == SqlDiagnostic.Effect.UNRESOLVED ? SqlSample.Feasibility.UNVERIFIED : SqlSample.Feasibility.UNKNOWN,
                    source.samples().getFirst().feasibility(), effect.toString());
        }
        assertFalse(new SqlDiagnostic("RENDER_LIMIT", "ordinary unresolved value").limited());
    }

    @Test void savedModelAndItsSampleShareTheSameValueCompletenessRule() {
        var signature = JavaIdentifierFactory.getInstance().getMethodSignature("example.Query", "run", "void", List.of());
        var variable = new ValueTrace.Variable(signature, 0, "id", "int");
        var trace = new ValueTrace(variable, List.of(), new ValueTrace.Node(variable, ValueTrace.Kind.CONSTANT, "7", List.of(),
                new ValueTrace.Computation(ValueTrace.Operation.LITERAL, "7")));
        var inferred = new InferredParameterValue(new SqlValue.Known(7), trace, List.of(), List.of());
        var parameter = new ParameterValueInferenceResult("id", "int", List.of(inferred), List.of());
        var definition = new SqlDefinition("query", LOCATION, SqlNodeBuilder.templateText("SELECT #{id}"));
        var model = new TemplateInferenceResult.BoundModel(definition.render(Map.of("id", 7)), List.of(), Optional.empty(), List.of());
        var saved = new TemplateInferenceResult(Map.of("id", trace), Map.of("id", parameter), Map.of("id", "id"),
                Optional.empty(), Optional.empty(), Map.of(), Optional.empty(), List.of(model), List.of());
        var statement = new SqlExtraction.Statement(Optional.empty(), definition.template(), List.of(), Optional.empty(), List.of(),
                List.of(), new RawSql(List.of(), List.of()), Optional.of(definition), Optional.of(saved));
        var source = new SqlExtraction("test", new SqlCallSite(signature, Optional.empty(), LOCATION), List.of(), List.of(statement), List.of());
        assertFalse(saved.valuesComplete(), "known rendering alone must not manufacture a missing parameter combination");
        assertEquals(saved.valuesComplete(), statement.inferenceComplete());
        assertEquals(saved.valuesComplete(), source.assessment().valuesComplete());
        assertEquals(saved.valuesComplete(), source.samples().getFirst().assessment().valuesComplete());
        assertTrue(source.samples().getFirst().assessment().sourcesComplete());
        assertTrue(source.samples().getFirst().assessment().textComplete());
    }

    private static SqlTemplateResult definitionResult(AnalysisDiagnostic diagnostic) {
        var definition = new SqlDefinition("query", LOCATION, SqlNodeBuilder.templateText("SELECT #{id}"));
        return new SqlTemplateResult("test", LOCATION, definition.template(), Optional.empty(), List.of(diagnostic),
                Optional.of(definition), Optional.of(definition.render(Map.of("id", 7))));
    }
}
