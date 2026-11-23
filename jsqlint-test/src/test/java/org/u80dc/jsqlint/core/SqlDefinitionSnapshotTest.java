package org.u80dc.jsqlint.core;

import org.junit.jupiter.api.Test;
import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.resource.AnalysisResourceSource;
import org.u80dc.jsqlint.core.spi.*;

import java.net.URI;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** 定义的参数化快照与执行诊断在分析阶段保存，预览只交付监听器，结果读取不重新求值。 @author u80dc */
class SqlDefinitionSnapshotTest {
    private static final SourceLocation LOCATION = new SourceLocation(URI.create("memory:/definition.sql"), "query");

    @Test void knownSeedParametersAreKeptAndReadingResultsDoesNotEvaluateAgain() {
        var evaluations = new AtomicInteger();
        SqlExpressionEvaluator evaluator = (expression, parameters) -> {
            evaluations.incrementAndGet();
            return SqlExpressionEvaluator.simple().evaluate(expression, parameters);
        };
        var definition = new SqlDefinition("query", LOCATION, SqlNodeBuilder.templateText("SELECT #{id}"), evaluator, Map.of(), List.of());
        var previews = new ArrayList<SqlNodeRenderer.RenderedSql>();
        var options = AnalysisOptions.defaults().withListener(new AnalysisListener() {
            @Override public void onSql(String moduleId, AnalysisSeed seed, SqlNode template, SqlNodeRenderer.RenderedSql sql) {
                previews.add(sql);
            }
        });
        var result = analyze(definition, Map.of("id", 7), options);
        assertEquals(AnalysisResult.Status.SUCCESS, result.status());
        var source = (SqlTemplateResult) result.sqlResults().getFirst();
        assertEquals("SELECT 7", previews.getFirst().sql().orElseThrow());
        assertEquals("SELECT ?", source.rendered().orElseThrow().sql().orElseThrow());
        assertEquals("SELECT ?", source.bound().orElseThrow().rendered().sql().orElseThrow());
        int savedEvaluations = evaluations.get();
        assertTrue(savedEvaluations > 0);
        for (int i = 0; i < 3; i++) {
            assertEquals(new SqlValue.Known(7), result.samples().getFirst().bindings().getFirst().value());
            assertEquals(List.of("SELECT ?"), result.rawSqls().stream().map(RawSql.Variant::text).toList());
            var raw = result.rawSqls().getFirst();
            assertEquals(result.samples().getFirst().bindings(), raw.bindingSnapshots().getFirst().bindings());
            assertTrue(raw.bindingSnapshots().getFirst().combination().isEmpty());
            assertTrue(raw.bindingSnapshots().getFirst().bindings().getFirst().trace().isEmpty());
            assertEquals(new SqlAssessment(true, true, true, Optional.empty()), source.assessment());
        }
        assertEquals(savedEvaluations, evaluations.get(), "默认读取只消费快照");
    }

    @Test void definitionBindingsKeepOrderTypesNullUnknownAndOptionsWithoutJavaEvidence() {
        var definition = new SqlDefinition("query", LOCATION,
                new MixedSqlNode(List.of(new StaticTextSqlNode("SELECT "),
                        ValueSqlNode.parameter("id", "javaType=int,jdbcType=INTEGER", "int"),
                        SqlNodeBuilder.templateText(",#{empty},#{id},#{missing}"))));
        Map<String,Object> parameters = new LinkedHashMap<>(); parameters.put("id",7); parameters.put("empty",null);
        var result = analyze(definition, parameters, AnalysisOptions.defaults());
        var raw = result.rawSqls().getFirst();
        var snapshot = raw.bindingSnapshots().getFirst();
        assertEquals("SELECT ?,?,?,?", raw.text());
        assertEquals(List.of("1","2","3","4"), snapshot.bindings().stream().map(SqlSample.Binding::key).toList());
        assertEquals("int", snapshot.bindings().getFirst().type());
        assertEquals("javaType=int,jdbcType=INTEGER", snapshot.bindings().getFirst().options());
        assertEquals(new SqlValue.Known(null), snapshot.bindings().get(1).value());
        assertEquals(new SqlValue.Known(7), snapshot.bindings().get(2).value());
        assertInstanceOf(SqlValue.Unknown.class, snapshot.bindings().getLast().value());
        assertTrue(snapshot.bindings().stream().allMatch(binding -> binding.location().equals(LOCATION) && binding.trace().isEmpty()));
        assertEquals(result.samples().getFirst().bindings(), snapshot.bindings());
        assertFalse(raw.inferenceComplete());
        assertThrows(UnsupportedOperationException.class, () -> raw.bindingSnapshots().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.bindings().clear());
    }

    @Test void previewBindsAreNotAppliedTwiceAndExplicitSamplesDoNotChangeTheSnapshot() {
        var definition = new SqlDefinition("query", LOCATION,
                SqlNodeBuilder.buildScript("<bind name='id' value='id + 1'/>SELECT #{id}"));
        var result = analyze(definition, Map.of("id", 7), AnalysisOptions.defaults());
        var source = (SqlTemplateResult) result.sqlResults().getFirst();
        assertEquals(new SqlValue.Known(new BigDecimal("8")), source.samples().getFirst().bindings().getFirst().value());
        assertEquals(new SqlValue.Known(new BigDecimal("21")), source.sample(Map.of("id", 20)).bindings().getFirst().value());
        assertEquals(new SqlValue.Known(new BigDecimal("8")), source.samples().getFirst().bindings().getFirst().value());
    }

    @Test void aDefinitionRenderLimitIsReportedBeforeTheAnalysisReturns() {
        var definition = new SqlDefinition("query", LOCATION,
                new MixedSqlNode(Collections.nCopies(10001, new StaticTextSqlNode("x"))));
        var result = analyze(definition, Map.of(), AnalysisOptions.defaults());
        assertEquals(AnalysisResult.Status.PARTIAL, result.status());
        var limits = result.diagnostics().stream().filter(diagnostic -> diagnostic.code().equals("SQL_RENDER_LIMIT")).toList();
        assertEquals(1, limits.size());
        assertEquals(Optional.of(LOCATION), limits.getFirst().location());
        var sample = result.samples().getFirst();
        assertTrue(sample.sql().isEmpty());
        assertEquals(SqlSample.Feasibility.UNKNOWN, sample.feasibility());
        assertTrue(sample.assessment().sourcesComplete()); assertTrue(sample.assessment().textComplete());
        assertFalse(sample.assessment().valuesComplete());
        assertTrue(result.rawSqls().isEmpty());
    }

    @Test void aLegacyDefinitionResultRequiresASnapshotOrExplicitParametersWithoutImplicitEvaluation() {
        var evaluations = new AtomicInteger();
        SqlExpressionEvaluator evaluator = (expression, parameters) -> {
            evaluations.incrementAndGet(); return SqlExpressionEvaluator.simple().evaluate(expression, parameters);
        };
        var definition = new SqlDefinition("query", LOCATION, SqlNodeBuilder.templateText("SELECT #{id}"), evaluator, Map.of(), List.of());
        var preview = SqlNodeRenderer.render(definition.template(), Map.of("id", 7), evaluator);
        var source = new SqlTemplateResult("definition-snapshot", LOCATION, definition.template(), Optional.of(preview),
                List.of(), Optional.of(definition));
        int savedEvaluations = evaluations.get();
        var sample = source.samples().getFirst();
        assertTrue(sample.sql().isEmpty()); assertTrue(source.rawSqls().isEmpty());
        assertFalse(source.assessment().valuesComplete());
        assertEquals(SqlSample.Feasibility.UNKNOWN, sample.feasibility());
        assertTrue(sample.issues().stream().anyMatch(issue -> issue.startsWith("RENDER_UNAVAILABLE:")));
        assertEquals(savedEvaluations, evaluations.get());
        assertEquals(new SqlValue.Known(9), source.sample(Map.of("id", 9)).bindings().getFirst().value());
        assertTrue(source.samples().getFirst().sql().isEmpty());
    }

    @Test void cancellationIsCheckedDuringDefinitionRendering() {
        var definition = new SqlDefinition("query", LOCATION,
                new MixedSqlNode(Collections.nCopies(1000, new StaticTextSqlNode("x"))));
        var checks = new AtomicInteger();
        var result = analyze(definition, Map.of(), AnalysisOptions.defaults().withCancelled(() -> checks.incrementAndGet() >= 25));
        assertEquals(AnalysisResult.Status.CANCELLED, result.status());
        assertTrue(result.sqlResults().isEmpty());
        assertTrue(checks.get() >= 25);
    }

    private static AnalysisResult analyze(SqlDefinition definition, Map<String, ?> parameters, AnalysisOptions options) {
        var module = new SqlAnalysisModule() {
            public String id() { return "definition-snapshot"; }
            public void register(DiscoveryRegistry registry) {
                registry.afterDiscovery(seeds -> seeds.accept(new AnalysisSeed() {
                    public SourceLocation location() { return definition.location(); }
                    public SqlInput input() { return new SqlInput.Definition(definition); }
                    public Map<String, ?> parameters() { return parameters; }
                }));
            }
        };
        AnalysisResourceSource resources = Stream::empty;
        return new Analyzer(List.of(module), List.of()).analyze(new AnalysisRequest(List.of(), List.of(resources), options));
    }
}
