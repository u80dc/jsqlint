package org.u80dc.jsqlint.core;

import org.junit.jupiter.api.Test;
import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.parameter.JointSqlInferenceResult;
import org.u80dc.jsqlint.core.spi.*;
import sootup.java.core.JavaIdentifierFactory;

import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SqlModeledStatementTest {
    private static final SourceLocation LOCATION = new SourceLocation(URI.create("memory:/query.xml"), "query");

    @Test void candidatesUseModelPositionsAcrossUnknownSqlAndIdenticalTextWithoutReplay() {
        var evaluations = new AtomicInteger();
        var definition = new SqlDefinition("query", LOCATION, SqlNodeBuilder.templateText("SELECT #{id} FROM ${table}"),
                (expression, values) -> { evaluations.incrementAndGet(); return SqlExpressionEvaluator.simple().evaluate(expression, values); }, Map.of(), List.of());
        var models = List.of(model(definition, Map.of("id", 1)), model(definition, Map.of("id", 2, "table", "users")),
                model(definition, Map.of("id", 3, "table", "users")));
        var content = new SqlExtraction.Statement.Content.Modeled(definition, state(Optional.empty(), models));
        int beforeRead = evaluations.get();
        var statement = new SqlExtraction.Statement(Optional.empty(), Optional.empty(), List.of(), content);
        var samples = source(statement).samples();
        assertEquals(3, samples.size()); assertTrue(samples.getFirst().sql().isEmpty());
        assertTrue(content.candidate(0).isEmpty());
        assertSame(content.candidate(1).orElseThrow(), samples.get(1).evidence().rawSql().orElseThrow());
        assertSame(content.candidate(2).orElseThrow(), samples.get(2).evidence().rawSql().orElseThrow());
        assertEquals(List.of(new SqlValue.Known(2), new SqlValue.Known(3)), samples.subList(1, 3).stream()
                .map(sample -> sample.bindings().getFirst().value()).toList());
        assertSame(statement.rawSql(), statement.rawSql()); assertEquals(2, statement.rawSql().variants().size());
        assertEquals(beforeRead, evaluations.get());
    }

    @Test void noModelsWithUnsatisfiableProofStaysEmptyWhileUnknownModelKeepsDiagnostics() {
        var definition = new SqlDefinition("query", LOCATION, SqlNodeBuilder.templateText("SELECT ${table}"));
        var unsat = new JointSqlInferenceResult(JointSqlInferenceResult.Status.UNSATISFIABLE, List.of(), List.of(), true);
        var impossible = new SqlExtraction.Statement(Optional.empty(), Optional.empty(), List.of(),
                new SqlExtraction.Statement.Content.Modeled(definition, state(Optional.of(unsat), List.of())));
        assertTrue(source(impossible).samples().isEmpty()); assertTrue(impossible.rawSql().variants().isEmpty());
        assertEquals(JointSqlInferenceResult.Status.UNSATISFIABLE, impossible.jointInferences().getFirst().status());
        var unknown = new JointSqlInferenceResult(JointSqlInferenceResult.Status.UNKNOWN, List.of(), List.of("Unknown input"), false);
        var partial = new SqlExtraction.Statement(Optional.empty(), Optional.empty(), List.of("Unmodeled input"),
                new SqlExtraction.Statement.Content.Modeled(definition, state(Optional.of(unknown), List.of(model(definition, Map.of())))));
        var sample = source(partial).samples().getFirst();
        assertTrue(sample.sql().isEmpty()); assertTrue(sample.issues().contains("Unmodeled input"));
        assertTrue(sample.issues().stream().anyMatch(issue -> issue.startsWith("UNKNOWN_VALUE:")));
    }

    @Test void contentRetainsValueEqualityAndRejectsAnotherStatementSourceEvenWithoutCandidates() {
        var definition = new SqlDefinition("query", LOCATION, SqlNodeBuilder.templateText("SELECT ${table}"));
        var saved = state(Optional.empty(), List.of(model(definition, Map.of())));
        assertEquals(new SqlExtraction.Statement.Content.Modeled(definition, saved), new SqlExtraction.Statement.Content.Modeled(definition, saved));
        var signature = JavaIdentifierFactory.getInstance().getMethodSignature("example.Query", "query", "void", List.of());
        var variable = new ValueTrace.Variable(signature, 0, "sql", "java.lang.String");
        var sql = new ValueTrace(variable, List.of(), new ValueTrace.Node(variable, ValueTrace.Kind.UNRESOLVED, "Unknown SQL", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SqlExtraction.Statement(Optional.of(sql), Optional.empty(), List.of(),
                new SqlExtraction.Statement.Content.Modeled(definition, saved)));
    }

    @Test void explicitAggregateProjectionOwnsCapacityWithoutChangingTheSavedResult() {
        var statement = new SqlExtraction.Statement(Optional.empty(), Optional.empty(), List.of(),
                new SqlExtraction.Statement.Content.Code(SqlNodeBuilder.text("SELECT 123"), List.of(), List.of(),
                        new RawSql(List.of(new RawSql.Variant("SELECT 123", List.of())), List.of())));
        var result = new AnalysisResult(AnalysisResult.Status.SUCCESS, List.of(source(statement)), List.of());
        var context = new SqlEvaluationContext(ReplayBudgets.defaults(), SqlRenderBudgets.defaults().withLength(5), () -> {});
        assertTrue(SqlSampleRenderer.render(result, context).getFirst().sql().isEmpty());
        assertEquals("SELECT 123", result.samples().getFirst().sql().orElseThrow());
    }

    @Test void publishedModeledStatementCannotTreatNoModelsAsNoSqlWithoutAProof() {
        var definition = new SqlDefinition("query", LOCATION, SqlNodeBuilder.text("SELECT 1"));
        var layoutOnly = state(Optional.empty(), List.of());
        assertThrows(IllegalArgumentException.class, () -> new SqlExtraction.Statement.Content.Modeled(definition, layoutOnly));
    }

    @Test void migrationFromAnEmptyCandidateListExportsModelsButCannotDiscardRawDiagnostics() {
        var definition = new SqlDefinition("query", LOCATION, SqlNodeBuilder.text("SELECT 1"));
        var saved = state(Optional.empty(), List.of(model(definition, Map.of())));
        var migrated = new SqlExtraction.Statement(Optional.empty(), definition.template(), List.of(), Optional.empty(), List.of(),
                List.of(), new RawSql(List.of(), List.of()), Optional.of(definition), Optional.of(saved));
        assertEquals("SELECT 1", migrated.rawSql().variants().getFirst().text());
        assertThrows(IllegalArgumentException.class, () -> new SqlExtraction.Statement(Optional.empty(), definition.template(),
                List.of(), Optional.empty(), List.of(), List.of(), new RawSql(List.of(), List.of("Raw recovery failed")),
                Optional.of(definition), Optional.of(saved)));
    }

    private static TemplateInferenceResult.BoundModel model(SqlDefinition definition, Map<String, ?> parameters) {
        return new TemplateInferenceResult.BoundModel(definition.render(parameters), List.of(), Optional.empty(), List.of());
    }
    private static TemplateInferenceResult state(Optional<JointSqlInferenceResult> joint, List<TemplateInferenceResult.BoundModel> models) {
        return new TemplateInferenceResult(Map.of(), Map.of(), TemplateInputLayout.empty(), joint, models, joint.map(JointSqlInferenceResult::issues).orElse(List.of()));
    }
    private static SqlExtraction source(SqlExtraction.Statement statement) {
        var signature = JavaIdentifierFactory.getInstance().getMethodSignature("example.Query", "query", "void", List.of());
        return new SqlExtraction("test", new SqlCallSite(signature, Optional.empty(), LOCATION), List.of(), List.of(statement), List.of());
    }
}
