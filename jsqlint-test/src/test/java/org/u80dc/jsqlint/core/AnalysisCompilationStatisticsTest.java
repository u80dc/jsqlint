package org.u80dc.jsqlint.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.commons.location.AutoCompileInputResolver;
import org.u80dc.jsqlint.jdbc.JdbcAnalysisModule;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 编译统计来自 Analyzer 实际输入准备；测试自身创建源码的开销不混入统计。 */
class AnalysisCompilationStatisticsTest {
    @TempDir Path temporary;

    @Test void sourceAnalysisReportsCompilationAndOwnedInputCleanup() throws Exception {
        Path source = Files.writeString(temporary.resolve("Fixture.java"), """
                public class Fixture {
                    public static void query(java.sql.Connection connection) throws Exception {
                        connection.prepareStatement("SELECT 1").executeQuery();
                    }
                }
                """);
        var analyzer = new Analyzer(List.of(new JdbcAnalysisModule()), List.of(new AutoCompileInputResolver()));
        var result = analyzer.analyze(source, AnalysisOptions.defaults().withStatistics(true).withCleanupClassOutput(true),
                "<Fixture: void query(java.sql.Connection)>");
        assertTrue(result.status() == AnalysisResult.Status.SUCCESS || result.status() == AnalysisResult.Status.PARTIAL,
                result.diagnostics().toString());
        assertTrue(result.rawSqls().stream().anyMatch(sql -> sql.text().equals("SELECT 1")));
        var statistics = result.statistics().orElseThrow();
        assertTrue(statistics.phases().get(AnalysisStatistics.Phase.COMPILATION).invocations() > 0);
        assertTrue(statistics.phases().get(AnalysisStatistics.Phase.CLEANUP).invocations() > 0);
        assertEquals(statistics.elapsedNanos(), statistics.phases().values().stream().mapToLong(AnalysisStatistics.PhaseCost::selfNanos).sum());
    }

    @Test void failedCompilerStillReportsCompilationAndRetainsFailedStatus() throws Exception {
        Path source = Files.writeString(temporary.resolve("Invalid.java"), "public class Invalid { syntax error }");
        var analyzer = new Analyzer(List.of(new JdbcAnalysisModule()), List.of(new AutoCompileInputResolver()));
        var result = analyzer.analyze(source, AnalysisOptions.defaults().withStatistics(true).withCleanupClassOutput(true));
        assertEquals(AnalysisResult.Status.FAILED, result.status());
        var statistics = result.statistics().orElseThrow();
        assertTrue(statistics.phases().get(AnalysisStatistics.Phase.COMPILATION).invocations() > 0);
        assertEquals(statistics.elapsedNanos(), statistics.phases().values().stream().mapToLong(AnalysisStatistics.PhaseCost::selfNanos).sum());
    }
}
