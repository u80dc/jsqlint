package org.u80dc.jsqlint.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.spi.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;

class SqlCompatibilityProjectionTest {
    @TempDir Path temporary;

    @Test void projectionLimitKeepsCompleteExtractionsAndDeliversAnUnresolvedLegacyView() throws Exception {
        var capture = new Capture();
        var result = analyze(config(), AnalysisOptions.defaults().withListener(capture));
        assertEquals(AnalysisResult.Status.PARTIAL, result.status(), result.diagnostics().toString());
        assertEquals(1, result.calls().size());
        assertEquals(3000, result.calls().getFirst().statements().size());
        assertTrue(result.calls().getFirst().complete());
        assertEquals(1, capture.delivered);
        assertEquals(1, capture.legacy.size());
        assertFalse(capture.legacy.getFirst().complete());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("SQL_COMPATIBILITY_PROJECTION")));
        assertTrue(result.diagnostics().stream().noneMatch(d -> d.code().equals("ANALYSIS_FAILED")));
    }

    @Test void raisingRecoveryCapacityAlsoRaisesCompatibilityProjectionCapacity() throws Exception {
        var capture = new Capture();
        var result = analyze(config().withSqlRecoveryBudgets(SqlRecoveryBudgets.defaults().withSteps(100000)),
                AnalysisOptions.defaults().withListener(capture));
        assertEquals(AnalysisResult.Status.SUCCESS, result.status(), result.diagnostics().toString());
        assertEquals(List.of("SELECT 1"), capture.legacy.stream().flatMap(sql -> sql.sql().stream()).toList());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test void cancellationInsideProjectionPropagatesWithoutBecomingAProjectionFailure() throws Exception {
        var projected = new AtomicBoolean();
        var checks = new AtomicInteger();
        var capture = new Capture() {
            @Override public void onSqlResult(SqlResult result) { super.onSqlResult(result); projected.set(true); }
        };
        var options = AnalysisOptions.defaults().withListener(capture)
                .withCancelled(() -> projected.get() && checks.incrementAndGet() > 20);
        var result = analyze(config(), options);
        assertEquals(AnalysisResult.Status.CANCELLED, result.status());
        assertEquals(1, result.calls().size());
        assertTrue(result.calls().getFirst().complete());
        assertTrue(capture.legacy.isEmpty());
        assertTrue(result.diagnostics().isEmpty());
    }

    private AnalyzerConfig config() {
        return AnalyzerConfig.defaults().withInferenceOptions(InferenceOptions.defaults().withMode(InferenceOptions.Mode.EXTRACT));
    }

    private AnalysisResult analyze(AnalyzerConfig config, AnalysisOptions options) throws Exception {
        var source = Files.writeString(temporary.resolve("Sample.java"),
                "public class Sample { static void send(String sql) {} public static void run() { send(\"SELECT 1\"); } }");
        var classes = Files.createDirectory(temporary.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-proc:none", "-g", "-d", classes.toString(), source.toString()));
        var module = new SqlAnalysisModule() {
            public String id() { return "compatibility-projection"; }
            public void register(DiscoveryRegistry registry) {
                registry.invocation(signature -> signature.getName().equals("send"), (site, seeds) -> seeds.accept(new AnalysisSeed() {
                    public SourceLocation location() { return site.location(); }
                    public SqlInput input() { return new SqlInput.ValueAt(site.method(), site.statement(), site.invocation().getArg(0)); }
                }));
                registry.extraction(site -> site.invocation().getMethodSignature().getName().equals("send"), (point, flow) ->
                        Collections.nCopies(3000, SqlTraceSource.sql(point.value(
                                point.statement().asInvokableStmt().getInvokeExpr().orElseThrow().getArg(0)))));
            }
        };
        return new Analyzer(config, List.of(module), List.of()).analyze(classes, options);
    }

    private static class Capture implements AnalysisListener {
        int delivered;
        final List<SqlNodeRenderer.RenderedSql> legacy = new ArrayList<>();
        public void onSqlResult(SqlResult result) { delivered++; }
        public void onSql(String module, AnalysisSeed seed, SqlNode template, SqlNodeRenderer.RenderedSql rendered) { legacy.add(rendered); }
    }
}
