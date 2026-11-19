package org.u80dc.jsqlint.core;

import org.junit.jupiter.api.Test;
import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.parameter.*;
import org.u80dc.jsqlint.core.resource.AnalysisResourceSource;
import org.u80dc.jsqlint.core.spi.*;
import org.u80dc.jsqlint.core.template.SqlTemplateProjection;
import sootup.java.core.JavaIdentifierFactory;
import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class SqlSampleProjectionBudgetTest {
    private static final SourceLocation LOCATION = new SourceLocation(URI.create("memory:/saved.sql"), "query");

    @Test void savedCompleteSqlDoesNotEncounterAnotherDefaultLengthLimitWhenRead() {
        String text = "SELECT " + "x".repeat(1_000_010);
        var source = extraction(new RawSql.Variant(text, List.of()));
        var sample = source.samples().getFirst();
        assertEquals(text, sample.sql().orElseThrow());
        assertTrue(sample.assessment().valuesComplete());
        assertTrue(sample.issues().stream().noneMatch(issue -> issue.startsWith("RENDER_LIMIT")));
        assertEquals(text, source.samples().getFirst().sql().orElseThrow());
    }

    @Test void explicitSampleCapacityCanLimitSavedSqlWithoutChangingItsResult() {
        var source = extraction(new RawSql.Variant("SELECT 123", List.of()));
        var limits = new ArrayList<String>();
        var context = new SqlEvaluationContext(ReplayBudgets.defaults(), SqlRenderBudgets.defaults().withLength(5), () -> {}, limits::add);
        var sample = source.samples(context).getFirst();
        assertTrue(sample.sql().isEmpty());
        assertFalse(sample.assessment().valuesComplete());
        assertSame(source.statements().getFirst().template(), sample.rendered().residual(), "容量耗尽直接复用保存的模板，不重新遍历槽位");
        assertEquals(1, limits.size());
        assertEquals("SELECT 123", source.samples().getFirst().sql().orElseThrow());
        assertTrue(source.assessment().textComplete());
    }

    @Test void savedSlotsUsePositionsAndDoNotInterpretSqlOrParameterTextAsExpressions() {
        String text = "SELECT '${literal}', ${value}, '${literal}'";
        int start = text.indexOf("${value}");
        var trace = trace("value", "ignored");
        var inferred = new InferredParameterValue(new SqlValue.Known("${literal} #{id}"), trace, List.of(), List.of());
        var parameter = new ParameterValueInferenceResult("value", "java.lang.String", List.of(inferred), List.of());
        var combination = new JointSqlInferenceResult.Combination(JointSqlInferenceResult.Status.SATISFIABLE,
                Map.of("value", inferred), List.of(), List.of(), Map.of(), List.of());
        var joint = new JointSqlInferenceResult(JointSqlInferenceResult.Status.SATISFIABLE, List.of(combination), List.of(), true);
        var raw = new RawSql.Variant(text, List.of(new RawSql.Placeholder("value", start, start + 8, trace.root(), parameter)),
                Optional.empty(), List.of(), Optional.of(joint));
        var sample = extraction(raw).samples().getFirst();
        assertEquals("SELECT '${literal}', ${literal} #{id}, '${literal}'", sample.sql().orElseThrow());
        assertEquals(SqlSample.Feasibility.SATISFIABLE, sample.feasibility());
        assertEquals(Optional.of(combination), sample.combination());
    }

    @Test void explicitSampleCancellationIsNotConvertedIntoUnknownSql() {
        var source = extraction(new RawSql.Variant("SELECT 1", List.of()));
        var checks = new AtomicInteger();
        var stopped = new IllegalStateException("sample cancelled");
        var context = new SqlEvaluationContext(ReplayBudgets.defaults(), SqlRenderBudgets.defaults(), () -> {
            if (checks.incrementAndGet() >= 3) throw stopped;
        });
        assertSame(stopped, assertThrows(IllegalStateException.class, () -> source.samples(context)));
        assertEquals("SELECT 1", source.samples().getFirst().sql().orElseThrow());
    }

    @Test void unknownSavedSlotsKeepTheirExplanationWithoutExpressionReplay() {
        var trace = trace("value", "ignored");
        var inferred = new InferredParameterValue(new SqlValue.Unknown("missing model value"), trace, List.of(), List.of());
        var parameter = new ParameterValueInferenceResult("value", "java.lang.String", List.of(inferred), List.of());
        var combination = new JointSqlInferenceResult.Combination(JointSqlInferenceResult.Status.UNKNOWN,
                Map.of("value", inferred), List.of(), List.of(), Map.of(), List.of());
        var joint = new JointSqlInferenceResult(JointSqlInferenceResult.Status.UNKNOWN, List.of(combination), List.of(), false);
        var variant = new RawSql.Variant("SELECT ${value}", List.of(new RawSql.Placeholder("value", 7, 15, trace.root(), parameter)),
                Optional.empty(), List.of(), Optional.of(joint));
        var sample = extraction(variant).samples().getFirst();
        assertTrue(sample.sql().isEmpty());
        assertTrue(sample.rendered().diagnostics().stream().anyMatch(d -> d.code().equals("UNKNOWN_VALUE") && d.message().contains("missing model value")));
        assertTrue(sample.issues().stream().anyMatch(issue -> issue.contains("missing model value")));
    }

    @Test void recoveryProjectionCapacityIsIndependentOfParameterReplayLength() {
        String text = "SELECT " + "x".repeat(1_000_010);
        var raw = new RawSql(List.of(new RawSql.Variant(text, List.of())), List.of());
        var projected = SqlTemplateProjection.template(raw, ReplayBudgets.defaults().withLength(3),
                SqlRecoveryBudgets.defaults().withLength(text.length()), () -> {});
        assertEquals(text, assertInstanceOf(StaticTextSqlNode.class, projected).text());
    }

    @Test void staticSqlExpansionUsesRenderWorkCapacityInsteadOfReplayCapacity() {
        var template = new MixedSqlNode(List.of(new StaticTextSqlNode("SELECT"),
                new StaticTextSqlNode("1"), new StaticTextSqlNode("FROM dual")), " ");
        var context = new SqlEvaluationContext(ReplayBudgets.defaults().withSteps(1),
                SqlRenderBudgets.defaults().withSteps(32), () -> {});
        var rendered = SqlNodeRenderer.render(template, Map.of(), SqlExpressionEvaluator.simple(), context);
        assertEquals("SELECT 1 FROM dual", rendered.sql().orElseThrow(), rendered.diagnostics().toString());
    }

    @Test void analyzerRenderCapacityIsIndependentAndSurvivesInvocationOverrides() {
        var definition = new SqlDefinition("query", LOCATION, new StaticTextSqlNode("SELECT 123"));
        var module = new SqlAnalysisModule() {
            public String id() { return "render-budget"; }
            public void register(DiscoveryRegistry registry) { registry.afterDiscovery(seeds -> seeds.accept(new AnalysisSeed() {
                public SourceLocation location() { return LOCATION; }
                public SqlInput input() { return new SqlInput.Definition(definition); }
            })); }
        };
        var config = AnalyzerConfig.defaults().withReplayBudgets(ReplayBudgets.defaults().withLength(3))
                .withSqlRenderBudgets(SqlRenderBudgets.defaults().withLength(5));
        var request = new AnalysisRequest(List.of(), List.of((AnalysisResourceSource) Stream::empty),
                AnalysisOptions.defaults().withCleanupClassOutput(true));
        var limited = new Analyzer(config, List.of(module), List.of()).analyze(request);
        assertEquals(AnalysisResult.Status.PARTIAL, limited.status());
        assertTrue(limited.definitions().getFirst().assessment().sourcesComplete());
        assertTrue(limited.diagnostics().stream().anyMatch(d -> d.affects(SqlAssessment.Dimension.VALUES)));
        var complete = new Analyzer(config.withSqlRenderBudgets(SqlRenderBudgets.defaults().withLength(10)), List.of(module), List.of()).analyze(request);
        assertEquals(AnalysisResult.Status.SUCCESS, complete.status(), complete.diagnostics().toString());
        assertEquals("SELECT 123", complete.samples().getFirst().sql().orElseThrow());
    }

    private static SqlExtraction extraction(RawSql.Variant variant) {
        var trace = trace("sql", variant.text());
        var statement = new SqlExtraction.Statement(Optional.of(trace), variant.template(), List.of(), Optional.empty(),
                List.of(), List.of(), new RawSql(List.of(variant), List.of()));
        return new SqlExtraction("saved", new SqlCallSite(trace.variable().method(), Optional.empty(), LOCATION),
                List.of(), List.of(statement), List.of());
    }
    private static ValueTrace trace(String name, String value) {
        var signature = JavaIdentifierFactory.getInstance().getMethodSignature("example.Query", "run", "void", List.of());
        var variable = new ValueTrace.Variable(signature, 0, name, "java.lang.String");
        return new ValueTrace(variable, List.of(), new ValueTrace.Node(variable, ValueTrace.Kind.CONSTANT, value, List.of(),
                new ValueTrace.Computation(ValueTrace.Operation.LITERAL, '"' + value + '"')));
    }
}
