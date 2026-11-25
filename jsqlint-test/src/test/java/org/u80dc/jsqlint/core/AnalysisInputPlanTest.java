package org.u80dc.jsqlint.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.u80dc.jsqlint.core.spi.*;
import org.u80dc.jsqlint.jdbc.JdbcAnalysisModule;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;

import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Isolated("临时目录系统属性决定 Analyzer 输出分配")
class AnalysisInputPlanTest {
    @TempDir Path temporary;

    @Test void borrowedPlanKeepsArtifactsAndDoesNotPreclearThePerCallOutput() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Path classes = Files.createDirectories(temporary.resolve("compiled"));
        Path source = Files.writeString(project.resolve("Fixture.java"), """
                public class Fixture {
                    public void query(java.sql.Connection connection) throws Exception {
                        connection.prepareStatement("SELECT 1");
                    }
                }
                """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-proc:none", "-d", classes.toString(), source.toString()));
        String previous = System.getProperty("java.io.tmpdir");
        try {
            Path system = Files.createDirectories(temporary.resolve("system"));
            System.setProperty("java.io.tmpdir", system.toString());
            Path reserved = Files.createDirectories(system.resolve(".jsqlint/stable"));
            Path sentinel = Files.writeString(reserved.resolve("sentinel.txt"), "keep");
            var resolver = new AnalysisInputResolver() {
                public int priority() { return 200; }
                public boolean supports(Path path) { return path.equals(project); }
                public boolean requiresCompilation(Path path) { fail("计划覆盖后不应再次请求旧编译布尔值");return true; }
                public PreparedInput resolve(Path path, Runnable checkpoint) { throw new AssertionError("Use the selected plan"); }
                public AnalysisInputPlan plan(Path path, AnalysisInputOptions options, AnalysisInputContext context) {
                    return new AnalysisInputPlan() {
                        public boolean requiresManagedClassOutputDirectory() { return false; }
                        public PreparedInput resolve(Path output, AnalysisInputContext execution) {
                            execution.checkpoint().run();
                            return new PreparedInput(List.of(new JavaClassPathAnalysisInputLocation(classes.toString(), SourceType.Application)), List.of());
                        }
                    };
                }
            };
            var analyzer = new Analyzer(AnalyzerConfig.defaults().withCleanupClassOutput(true),
                    List.of(new JdbcAnalysisModule()), List.of(resolver));
            var result = analyzer.analyze(project, AnalysisOptions.defaults().withClassOutputId("stable").withStatistics(true));
            assertEquals(AnalysisResult.Status.SUCCESS, result.status(), result.diagnostics().toString());
            assertEquals("SELECT 1", result.rawSqls().getFirst().text());
            assertEquals("keep", Files.readString(sentinel));
            assertTrue(Files.isRegularFile(classes.resolve("Fixture.class")));
            assertFalse(result.statistics().orElseThrow().phases().containsKey(AnalysisStatistics.Phase.COMPILATION));
        } finally {
            if (previous == null) System.clearProperty("java.io.tmpdir");else System.setProperty("java.io.tmpdir", previous);
        }
    }
}
