package org.u80dc.jsqlint.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.core.spi.DiscoveryRegistry;
import org.u80dc.jsqlint.core.spi.SqlAnalysisModule;
import org.u80dc.jsqlint.jdbc.JdbcAnalysisModule;
import org.u80dc.jsqlint.mybatis.MybatisAnalysisModule;
import sootup.java.core.JavaIdentifierFactory;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.AnalysisResult.Status.*;

/** 验证固定路径、依赖和配置复用，以及连续调用的范围与执行状态隔离。 */
class BoundAnalyzerTest {
    @TempDir Path temporary;
    private static final String SOOT_ENTRY = "<sample.Sample: void entry(java.sql.Connection)>";
    private static final String JVM_ENTRY = "sample/Sample.entry:(Ljava/sql/Connection;)V";

    @Test void bindsBeforeOutputExistsAndReadsCurrentBytecodeWithoutChangingOtherBindings() throws Exception {
        var analyzer = jdbc(AnalyzerConfig.defaults());
        var classes = temporary.resolve("first/classes");
        var dependencies = temporary.resolve("dependencies");
        var first = analyzer.bind(classes.resolve("unused/.."), dependencies);
        var otherClasses = temporary.resolve("other/classes");
        var other = analyzer.bind(otherClasses);
        assertFalse(Files.exists(classes)); assertFalse(Files.exists(dependencies));

        Files.createDirectory(dependencies);
        compile(classes, "Sample", jdbcSource(1));
        compile(otherClasses, "Sample", jdbcSource(9));
        var initial = first.analyze();
        assertSql(initial, Set.of("SELECT 1"));
        compile(classes, "Sample", jdbcSource(2));
        assertSql(first.analyze(), Set.of("SELECT 2"));
        assertSql(other.analyze(), Set.of("SELECT 9"));
        assertSql(analyzer.analyze(otherClasses), Set.of("SELECT 9"));
        assertSql(initial, Set.of("SELECT 1"));
    }

    @Test void allFourMethodsRetainDependenciesExplicitModulesAndRequestedScope() throws Exception {
        var classes = compile(temporary.resolve("application/classes"), "Sample", """
                package sample;
                public class Sample {
                    public static void entry(java.sql.Connection c) throws Exception { c.prepareStatement("SELECT 6"); }
                    public static void other(java.sql.Connection c) throws Exception { c.prepareStatement("SELECT 9"); }
                }
                """);
        var dependencies = compile(temporary.resolve("library/classes"), "Library",
                "package dependency; public class Library {}");
        var inspected = new AtomicInteger();
        var checking = new SqlAnalysisModule() {
            public String id() { return "checking"; }
            public void register(DiscoveryRegistry registry) {
                registry.sqlMethod((method, view) -> {
                    assertTrue(view.getClass(JavaIdentifierFactory.getInstance().getClassType("dependency.Library")).isPresent());
                    inspected.incrementAndGet();
                    return false;
                });
            }
        };
        var analyzer = new Analyzer(List.of(new JdbcAnalysisModule(), checking), List.of());
        var bound = analyzer.bind(classes, dependencies.resolve("unused/.."));
        var received = new AtomicInteger();
        var options = AnalysisOptions.defaults().withListener(new AnalysisListener() {
            public void onSqlResult(SqlResult result) { received.incrementAndGet(); }
        });

        assertSql(bound.analyze(), Set.of("SELECT 6", "SELECT 9"));
        assertTrue(inspected.get() > 0);
        int previous = inspected.get();
        assertSql(bound.analyze(options), Set.of("SELECT 6", "SELECT 9"));
        assertTrue(inspected.get() > previous); assertEquals(2, received.get());
        previous = inspected.get();
        assertSql(bound.analyze(SOOT_ENTRY), Set.of("SELECT 6"));
        assertTrue(inspected.get() > previous); assertEquals(2, received.get());
        previous = inspected.get();
        assertSql(bound.analyze(options, JVM_ENTRY), Set.of("SELECT 6"));
        assertTrue(inspected.get() > previous); assertEquals(3, received.get());
    }

    @Test void inheritsOriginalDefaultsAndKeepsOverridesWithinEachInvocation() throws Exception {
        var classes = compile(temporary.resolve("application/classes"), "Sample", jdbcSource(3));
        var bound = jdbc(AnalyzerConfig.defaults().withTimeout(Duration.ofNanos(1))).bind(classes);
        var unlimited = AnalysisOptions.defaults().withTimeout(Duration.ZERO);
        assertEquals(PARTIAL, bound.analyze().status());
        assertSql(bound.analyze(unlimited), Set.of("SELECT 3"));
        assertEquals(PARTIAL, bound.analyze(SOOT_ENTRY).status());
        assertSql(bound.analyze(unlimited, JVM_ENTRY), Set.of("SELECT 3"));
        assertEquals(PARTIAL, bound.analyze().status());
    }

    @Test void cancellationAndListenersDoNotLeakIntoFollowingInvocations() throws Exception {
        var classes = compile(temporary.resolve("application/classes"), "Sample", jdbcSource(4));
        var bound = jdbc(AnalyzerConfig.defaults()).bind(classes);
        var progress = new AtomicInteger();
        var options = AnalysisOptions.defaults().withCancelled(() -> true).withListener(new AnalysisListener() {
            public void onProgress(Progress event) { progress.incrementAndGet(); }
        });
        assertEquals(CANCELLED, bound.analyze(options, JVM_ENTRY).status());
        int finishedProgress = progress.get();
        assertSql(bound.analyze(JVM_ENTRY), Set.of("SELECT 4"));
        assertSql(bound.analyze(), Set.of("SELECT 4"));
        assertEquals(finishedProgress, progress.get());
    }

    @Test void bothSignatureFormatsReuseXmlMapperResourcesWithoutMethodBodies() throws Exception {
        var classes = compile(temporary.resolve("application/classes"), "Sample", """
                package sample;
                interface Mapper { Object query(); Object other(); }
                public class Sample {}
                """);
        Files.writeString(classes.resolve("mapper.xml"), """
                <mapper namespace='sample.Mapper'>
                  <select id='query'>SELECT 5</select>
                  <select id='other'>SELECT 9</select>
                </mapper>
                """);
        var bound = new Analyzer(List.of(new MybatisAnalysisModule()), List.of()).bind(classes);
        for (var result : List.of(bound.analyze("<sample.Mapper: java.lang.Object query()>"),
                bound.analyze(AnalysisOptions.defaults(), "sample/Mapper.query:()Ljava/lang/Object;"))) {
            assertSql(result, Set.of("SELECT 5"));
            assertTrue(result.calls().isEmpty()); assertEquals(1, result.definitions().size());
            assertEquals("sample.Mapper.query", result.definitions().getFirst().definition().orElseThrow().id());
        }
    }

    @Test void preservesValidationContractsWhileDeferringFileChecksUntilAnalysis() {
        var analyzer = jdbc(AnalyzerConfig.defaults());
        assertThrows(NullPointerException.class, () -> analyzer.bind(null));
        assertThrows(NullPointerException.class, () -> analyzer.bind(null, temporary));
        var missing = assertDoesNotThrow(() -> analyzer.bind(temporary.resolve("missing")));
        assertThrows(IllegalArgumentException.class, missing::analyze);
        var missingDependency = assertDoesNotThrow(() -> analyzer.bind(temporary, temporary.resolve("missing")));
        assertThrows(IllegalArgumentException.class, missingDependency::analyze);
        var bound = analyzer.bind(temporary, null);
        assertThrows(IllegalArgumentException.class, () -> bound.analyze("entry:()V"));
        assertThrows(IllegalArgumentException.class, () -> bound.analyze(AnalysisOptions.defaults(), "Sample.entry"));
        assertThrows(NullPointerException.class, () -> bound.analyze((AnalysisOptions) null));
    }

    private Path compile(Path classes, String name, String source) throws Exception {
        Files.createDirectories(classes);
        var java = Files.writeString(classes.getParent().resolve(name + ".java"), source);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-g", "-parameters",
                "-d", classes.toString(), java.toString()));
        return classes;
    }
    private static String jdbcSource(int value) {
        return "package sample; public class Sample { public static void entry(java.sql.Connection c) throws Exception {"
                + " c.prepareStatement(\"SELECT " + value + "\"); } }";
    }
    private static Analyzer jdbc(AnalyzerConfig config) {
        return new Analyzer(config, List.of(new JdbcAnalysisModule()), List.of());
    }
    private static void assertSql(AnalysisResult result, Set<String> expected) {
        assertEquals(SUCCESS, result.status(), result.diagnostics().toString());
        assertEquals(expected, result.samples().stream().flatMap(sample -> sample.sql().stream())
                .map(String::strip).collect(java.util.stream.Collectors.toSet()));
    }
}
