package org.u80dc.jsqlint.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.jdbc.JdbcAnalysisModule;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.DefaultRuntimeAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 真实字节码/SQL链路的计时统计；不执行目标方法、不连接数据库，也不对速度设阈值。 */
class AnalysisStatisticsIntegrationTest {
    private static final String SIGNATURE = "<statistics.Fixture: void query(java.sql.Connection,long)>";
    @TempDir Path temporary;

    @Test
    void enablingStatisticsPreservesSqlResultsAndSnapshotsAreRequestLocal() throws Exception {
        Path classes = compile();
        var analyzer = analyzer(InferenceOptions.Mode.JOINT);
        try (var input = new JavaClassPathAnalysisInputLocation(classes.toString(), SourceType.Application);
             var runtime = new DefaultRuntimeAnalysisInputLocation()) {
            List<AnalysisInputLocation> locations = List.of(input, runtime);
            var disabled = analyzer.analyze(new AnalysisRequest(locations, List.of()), SIGNATURE);
            assertTrue(disabled.statistics().isEmpty());
            assertTrue(disabled.status() == AnalysisResult.Status.SUCCESS || disabled.status() == AnalysisResult.Status.PARTIAL,
                    disabled.diagnostics().toString());
            assertEquals(2, disabled.calls().size(), disabled.diagnostics().toString());
            assertEquals(java.util.Set.of("prepareStatement", "executeQuery"),
                    disabled.calls().stream().map(call -> call.callSite().target().getName()).collect(java.util.stream.Collectors.toSet()));
            assertFalse(disabled.rawSqls().isEmpty());
            assertFalse(disabled.samples().isEmpty());
            assertTrue(disabled.samples().stream().allMatch(sample -> sample.sql().isPresent()),
                    "JOINT实际调用需恢复可比较的SQL样例");
            var options = AnalysisOptions.defaults().withStatistics(true);
            var first = analyzer.analyze(new AnalysisRequest(locations, List.of(), options), SIGNATURE);
            assertSemantics(disabled, first);
            var saved = assertAccounting(first);
            var savedPhases = new EnumMap<AnalysisStatistics.Phase, AnalysisStatistics.PhaseCost>(AnalysisStatistics.Phase.class);
            savedPhases.putAll(saved.phases());
            long savedElapsed = saved.elapsedNanos();
            var second = analyzer.analyze(new AnalysisRequest(locations, List.of(), options), SIGNATURE);
            assertSemantics(first, second);
            var other = assertAccounting(second);
            assertNotSame(saved, other, "不同分析请求不能共享可变计时状态");
            assertEquals(savedElapsed, saved.elapsedNanos());
            assertEquals(savedPhases, saved.phases());
            assertNoCompilation(saved);
            for (var phase : List.of(AnalysisStatistics.Phase.INDEXING, AnalysisStatistics.Phase.TRACING,
                    AnalysisStatistics.Phase.INFERENCE, AnalysisStatistics.Phase.RENDERING)) assertPhaseRecorded(saved, phase);
        }
    }

    @Test
    void extractModeOnABytecodePathRecordsRecoveryWithoutACompilationPhase() throws Exception {
        Path classes = compile();
        var analyzer = analyzer(InferenceOptions.Mode.EXTRACT);
        var disabled = analyzer.analyze(classes, AnalysisOptions.defaults(), SIGNATURE);
        var enabled = analyzer.analyze(classes, AnalysisOptions.defaults()
                .withStatisticsOptions(AnalysisStatisticsOptions.timings()), SIGNATURE);
        assertSemantics(disabled, enabled);
        var statistics = assertAccounting(enabled);
        assertNoCompilation(statistics);
        assertPhaseRecorded(statistics, AnalysisStatistics.Phase.INDEXING);
        assertPhaseRecorded(statistics, AnalysisStatistics.Phase.TRACING);
        assertFalse(enabled.rawSqls().isEmpty(), "EXTRACT应保存实际SQL与拼接参数来源");
    }

    @Test
    void listenerFailureStillFailsAndEnabledStatisticsRemainAvailable() throws Exception {
        Path classes = compile();
        var analyzer = analyzer(InferenceOptions.Mode.JOINT);
        AnalysisListener failing = new AnalysisListener() {
            @Override public void onProgress(Progress progress) {
                if (progress.stage() == Stage.CODE) throw new IllegalStateException("fixture listener failure");
            }
        };
        var options = AnalysisOptions.defaults().withListener(failing);
        try (var input = new JavaClassPathAnalysisInputLocation(classes.toString(), SourceType.Application);
             var runtime = new DefaultRuntimeAnalysisInputLocation()) {
            List<AnalysisInputLocation> locations = List.of(input, runtime);
            var disabled = analyzer.analyze(new AnalysisRequest(locations, List.of(), options), SIGNATURE);
            var enabled = analyzer.analyze(new AnalysisRequest(locations, List.of(), options.withStatistics(true)), SIGNATURE);
            assertEquals(AnalysisResult.Status.FAILED, disabled.status());
            assertEquals(disabled.status(), enabled.status());
            assertEquals(disabled.diagnostics(), enabled.diagnostics());
            assertTrue(disabled.statistics().isEmpty());
            assertAccounting(enabled);
        }
    }

    @Test
    void cancellationAndDeadlineKeepTheirStatusesAndProduceFinalAccounting() throws Exception {
        Path classes = compile();
        var analyzer = analyzer(InferenceOptions.Mode.JOINT);
        try (var input = new JavaClassPathAnalysisInputLocation(classes.toString(), SourceType.Application);
             var runtime = new DefaultRuntimeAnalysisInputLocation()) {
            List<AnalysisInputLocation> locations = List.of(input, runtime);
            for (var options : List.of(AnalysisOptions.defaults().withCancelled(() -> true),
                    AnalysisOptions.defaults().withTimeout(Duration.ofNanos(1)))) {
                var disabled = analyzer.analyze(new AnalysisRequest(locations, List.of(), options), SIGNATURE);
                var enabled = analyzer.analyze(new AnalysisRequest(locations, List.of(), options.withStatistics(true)), SIGNATURE);
                assertEquals(options.timeout() == null ? AnalysisResult.Status.CANCELLED : AnalysisResult.Status.PARTIAL, disabled.status());
                assertEquals(disabled.status(), enabled.status());
                assertEquals(disabled.diagnostics().stream().map(AnalysisDiagnostic::code).toList(),
                        enabled.diagnostics().stream().map(AnalysisDiagnostic::code).toList());
                assertTrue(disabled.statistics().isEmpty());
                assertAccounting(enabled);
            }
        }
    }

    @Test
    void cancellationAfterTheFirstDeliveredSqlKeepsResultsAndFinishesAccounting() throws Exception {
        Path classes = compile();
        var analyzer = analyzer(InferenceOptions.Mode.JOINT);
        try (var input = new JavaClassPathAnalysisInputLocation(classes.toString(), SourceType.Application);
             var runtime = new DefaultRuntimeAnalysisInputLocation()) {
            List<AnalysisInputLocation> locations = List.of(input, runtime);
            var runs = new ArrayList<AnalysisResult>();
            for (boolean enabled : List.of(false, true)) {
                var cancelled = new AtomicBoolean();
                var finished = new AtomicInteger();
                var delivered = new ArrayList<SqlResult>();
                var listener = new AnalysisListener() {
                    @Override public void onSqlResult(SqlResult result) {
                        delivered.add(result);
                        cancelled.set(true);
                    }
                    @Override public void onProgress(Progress progress) {
                        if (progress.stage() == Stage.FINISHED) finished.incrementAndGet();
                    }
                };
                var options = AnalysisOptions.defaults().withStatistics(enabled)
                        .withCancelled(cancelled::get).withListener(listener);
                var result = analyzer.analyze(new AnalysisRequest(locations, List.of(), options), SIGNATURE);
                assertEquals(AnalysisResult.Status.CANCELLED, result.status());
                assertEquals(1, delivered.size(), "首个真实SQL结果交付后停止，不应丢弃已交付结果");
                assertEquals(delivered, result.sqlResults());
                assertEquals(1, finished.get(), "取消后仍须交付FINISHED事件");
                assertFalse(result.rawSqls().isEmpty());
                if (enabled) assertAccounting(result);
                else assertTrue(result.statistics().isEmpty());
                runs.add(result);
            }
            assertSemantics(runs.getFirst(), runs.getLast());
        }
    }

    private static AnalysisStatistics assertAccounting(AnalysisResult result) {
        var statistics = result.statistics().orElseThrow();
        assertTrue(statistics.elapsedNanos() >= 0);
        long exclusive = 0;
        for (var cost : statistics.phases().values()) {
            assertTrue(cost.invocations() >= 0);
            assertTrue(cost.totalNanos() >= cost.selfNanos());
            assertTrue(cost.selfNanos() >= 0);
            assertTrue(cost.maxNanos() >= 0 && cost.maxNanos() <= cost.totalNanos());
            exclusive = Math.addExact(exclusive, cost.selfNanos());
        }
        assertEquals(statistics.elapsedNanos(), exclusive, "各阶段exclusive时间含OTHER应恰好覆盖请求总时间");
        return statistics;
    }

    private static void assertPhaseRecorded(AnalysisStatistics statistics, AnalysisStatistics.Phase phase) {
        var cost = statistics.phases().get(phase);
        assertNotNull(cost, "实际SQL链路应经过" + phase);
        assertTrue(cost.invocations() > 0, "阶段调用次数必须来自实际执行：" + phase);
    }

    private static void assertNoCompilation(AnalysisStatistics statistics) {
        var compilation = statistics.phases().get(AnalysisStatistics.Phase.COMPILATION);
        if (compilation != null) {
            assertEquals(0, compilation.invocations());
            assertEquals(0, compilation.totalNanos());
            assertEquals(0, compilation.selfNanos());
        }
    }

    private static void assertSemantics(AnalysisResult expected, AnalysisResult actual) {
        assertEquals(expected.status(), actual.status());
        assertEquals(expected.diagnostics(), actual.diagnostics());
        assertEquals(expected.rawSqls().stream().map(RawSql.Variant::text).toList(),
                actual.rawSqls().stream().map(RawSql.Variant::text).toList());
        assertEquals(expected.samples().stream().map(SqlSample::sql).toList(), actual.samples().stream().map(SqlSample::sql).toList());
        assertEquals(expected.samples().stream().map(sample -> sample.bindings().stream().map(SqlSample.Binding::value).toList()).toList(),
                actual.samples().stream().map(sample -> sample.bindings().stream().map(SqlSample.Binding::value).toList()).toList());
    }

    private static Analyzer analyzer(InferenceOptions.Mode mode) {
        var config = AnalyzerConfig.defaults().withInferenceOptions(InferenceOptions.defaults().withMode(mode).withRandomSeed(17L));
        return new Analyzer(config, List.of(new JdbcAnalysisModule()), List.of());
    }

    private Path compile() throws Exception {
        Path source = Files.writeString(temporary.resolve("Fixture.java"), """
                package statistics;
                public class Fixture {
                    public static void query(java.sql.Connection connection, long nodeId) throws Exception {
                        String sql = "SELECT name FROM statistics_users WHERE id=" + nodeId + " AND status=?";
                        java.sql.PreparedStatement statement = connection.prepareStatement(sql);
                        statement.setInt(1, 1);
                        statement.executeQuery();
                    }
                }
                """);
        Path classes = Files.createDirectories(temporary.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-parameters",
                "-d", classes.toString(), source.toString()));
        return classes;
    }
}
