package org.u80dc.jsqlint.core;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.resource.*;
import org.u80dc.jsqlint.core.spi.*;
import org.u80dc.jsqlint.jdbc.JdbcAnalysisModule;
import org.u80dc.jsqlint.mybatis.MybatisAnalysisModule;
import sootup.core.frontend.SootClassSource;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.interceptor.BodyInterceptor;
import sootup.core.model.SourceType;
import sootup.core.types.ClassType;
import sootup.core.views.View;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;

import javax.tools.ToolProvider;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.jar.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.AnalysisResult.Status.*;

/** 真实字节码、资源、SPI、所有权及长任务控制的集成测试。 @author u80dc */
class AnalyzerTest {
    @TempDir Path temporary;

    @Test
    void bytecodeDirectoryJarAndSingleClassWorkWithoutCompileResolver() throws Exception {
        Path classes = compile("Sample", """
                package example;
                public class Sample {
                    public void query(java.sql.Connection c) throws Exception {
                        c.prepareStatement("SELECT 1 -- comment\\nFROM sample");
                    }
                }
                """);
        Path jar = jar(classes, temporary.resolve("app with spaces.jar"));
        Analyzer analyzer = jdbc();
        for (Path input : List.of(classes, jar, classes.resolve("example/Sample.class"))) {
            Capture capture = new Capture();
            assertEquals(SUCCESS, analyzer.analyze(input, options(capture)).status());
            assertEquals(List.of("SELECT 1 -- comment\nFROM sample"), capture.completeSql());
        }
    }

    @Test
    void defaultSpiLoadsCompilerAndSqlModules() throws Exception {
        Path source = temporary.resolve("Source.java");
        Files.writeString(source, """
                public class Source {
                    public void query(java.sql.Connection c) throws Exception { c.prepareStatement("SELECT 42"); }
                }
                """);
        Capture capture = new Capture();
        assertEquals(SUCCESS, new Analyzer().analyze(source, options(capture)).status());
        assertEquals(List.of("SELECT 42"), capture.completeSql());
        assertEquals(List.of("jdbc"), capture.modules);
    }

    @Test
    void missingCompilerNeverUsesStaleBytecode() throws Exception {
        Path classes = compile("Sample", "public class Sample {}");
        Files.writeString(classes.resolve("Sample.java"), "invalid Java");
        Capture capture = new Capture();
        assertEquals(FAILED, jdbc().analyze(classes, options(capture)).status());
        assertTrue(capture.diagnostics.stream().anyMatch(text -> text.contains("jsqlint-commons")));
    }

    @Test
    void staticHelperCallsKeepTheirOwnArgumentsAndPreserveUnknownConcatenation() throws Exception {
        Path classes = compile("Sample", """
                public class Sample {
                    private static String sql(String table) { return "SELECT * FROM " + table; }
                    public void query(java.sql.Connection c, String external) throws Exception {
                        c.prepareStatement(sql("users"));
                        c.prepareStatement(sql("roles"));
                        c.prepareStatement("SELECT * FROM " + external);
                    }
                }
                """);
        Capture capture = new Capture();
        assertEquals(SUCCESS, jdbc().analyze(classes, options(capture)).status());
        assertEquals(List.of("SELECT * FROM users", "SELECT * FROM roles"), capture.completeSql());
        assertEquals(3, capture.sql.size());
        assertFalse(capture.sql.getLast().complete());
        assertTrue(capture.templates.getLast() instanceof MixedSqlNode);
    }

    @Test
    void bothControlFlowBranchesAreRetained() throws Exception {
        Path classes = compile("Sample", """
                public class Sample {
                    public void query(java.sql.Connection c, boolean flag) throws Exception {
                        String sql;
                        if (flag) sql = "SELECT 1"; else sql = "SELECT 2";
                        c.prepareStatement(sql);
                    }
                }
                """);
        Capture capture = new Capture();
        assertEquals(SUCCESS, jdbc().analyze(classes, options(capture)).status());
        var choice = assertInstanceOf(ChooseSqlNode.class, capture.templates.getFirst());
        assertEquals(Set.of(new StaticTextSqlNode("SELECT 1"), new StaticTextSqlNode("SELECT 2")),
                new HashSet<>(choice.whenContents().stream().map(IfSqlNode::contents).toList()));
        assertFalse(capture.sql.getFirst().complete());
    }

    @Test
    void traceBudgetMarksPartialAndRetainsCandidate() throws Exception {
        Path classes = compile("Sample", """
                public class Sample {
                    private static String sql(String table) { return "SELECT * FROM " + table; }
                    public void query(java.sql.Connection c) throws Exception { c.prepareStatement(sql("users")); }
                }
                """);
        Capture capture = new Capture();
        var config = AnalyzerConfig.defaults().withMaxTraceDepth(1).withMethodCacheSize(2);
        var analyzer = new Analyzer(config, List.of(new JdbcAnalysisModule()), List.of());
        assertEquals(PARTIAL, analyzer.analyze(classes, options(capture)).status());
        assertEquals(1, capture.sql.size());
        assertFalse(capture.sql.getFirst().complete());
        assertTrue(capture.diagnostics.stream().anyMatch(text -> text.startsWith("TRACE_LIMIT")));
    }

    @Test
    void indexesCallersAcrossAllMethodsIncludingNonControllerEntrypoints() throws Exception {
        Path classes = compile("Sample", """
                public class Sample {
                    private static void run(java.sql.Connection c, String sql) throws Exception {
                        c.prepareStatement(sql);
                    }
                    public void scheduled(java.sql.Connection c) throws Exception { run(c, "SELECT 7"); }
                }
                """);
        Capture capture = new Capture();
        assertEquals(SUCCESS, jdbc().analyze(classes, options(capture)).status());
        assertEquals(List.of("SELECT 7"), capture.completeSql());
    }

    @Test
    void mybatisReadsCrossResourceIncludesAndKeepsDynamicNodes() {
        Capture capture = new Capture();
        var resources = memory(Map.of(
                "mapper.xml", """
                        <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://unavailable.invalid/mapper.dtd">
                        <mapper namespace="app.Mapper">
                          <select id="all">SELECT <include refid="shared.Columns.columns"/> FROM users
                            <where><if test="id != null">id = #{id}</if></where>
                          </select>
                        </mapper>
                        """,
                "fragment.xml", """
                        <mapper namespace="shared.Columns"><sql id="columns">id, name</sql></mapper>
                        """,
                "ignore.sql", "SELECT ignored"));
        var analyzer = new Analyzer(List.of(new MybatisAnalysisModule()), List.of());
        assertEquals(SUCCESS, analyzer.analyze(new AnalysisRequest(List.of(), List.of(resources), options(capture))).status());
        assertEquals(1, capture.sql.size());
        assertFalse(capture.sql.getFirst().complete());
        assertTrue(capture.templates.getFirst().toString().contains("id, name"));
        assertTrue(capture.seeds.getFirst().location().element().contains("app.Mapper.all"));
    }

    @Test
    void unsupportedMybatisStatementIsRetainedAndOtherStatementsContinue() {
        Capture capture = new Capture();
        var resource = memory(Map.of("mapper.xml", """
                <mapper namespace="app.Mapper">
                  <select id="bad">SELECT <include refid="missing"/></select>
                  <select id="good">SELECT 8</select>
                </mapper>
                """));
        var analyzer = new Analyzer(List.of(new MybatisAnalysisModule()), List.of());
        assertEquals(PARTIAL, analyzer.analyze(new AnalysisRequest(List.of(), List.of(resource), options(capture))).status());
        assertEquals(2, capture.sql.size());
        assertEquals(List.of("SELECT 8"), capture.completeSql());
        assertFalse(capture.sql.getFirst().complete());
    }

    @Test
    void resourceSelectorsAreIndependentAndStreamsAreClosed() {
        AtomicBoolean streamClosed = new AtomicBoolean();
        AnalysisResourceSource source = () -> memory(Map.of("query.sql", "SELECT 9", "ignore.xml", "XML"))
                .resources().onClose(() -> streamClosed.set(true));
        SqlAnalysisModule first = sqlFiles("one"), second = sqlFiles("two");
        Capture capture = new Capture();
        Analyzer analyzer = new Analyzer(List.of(first, second), List.of());
        assertEquals(SUCCESS, analyzer.analyze(new AnalysisRequest(List.of(), List.of(source), options(capture))).status());
        assertEquals(List.of("SELECT 9", "SELECT 9"), capture.completeSql());
        assertEquals(List.of("one", "two"), capture.modules);
        assertTrue(streamClosed.get());
    }

    @Test
    void jarResourcesCanBeReopenedAfterEnumerationCloses() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("resources"));
        Files.writeString(root.resolve("query #1.sql"), "SELECT 10");
        Path jar = jar(root, temporary.resolve("resources with spaces.jar"));
        AnalysisResource resource;
        try (var stream = new PathResourceSource(jar).resources()) { resource = stream.findFirst().orElseThrow(); }
        assertTrue(resource.uri().toASCIIString().contains("%23"));
        for (int i = 0; i < 2; i++) {
            try (var stream = resource.openStream()) {
                assertEquals("SELECT 10", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    void callerOwnedLocationsRemainOpenAndLibraryMethodsAreExcluded() throws Exception {
        Path classes = compile("Sample", """
                public class Sample {
                    public void query(java.sql.Connection c) throws Exception { c.prepareStatement("SELECT 11"); }
                }
                """);
        TrackingLocation application = new TrackingLocation(new JavaClassPathAnalysisInputLocation(classes.toString(), SourceType.Application));
        TrackingLocation library = new TrackingLocation(new JavaClassPathAnalysisInputLocation(classes.toString(), SourceType.Library));
        Capture capture = new Capture();
        var request = new AnalysisRequest(List.of(application, library), List.of(), options(capture));
        assertEquals(SUCCESS, jdbc().analyze(request).status());
        assertEquals(List.of("SELECT 11"), capture.completeSql());
        assertFalse(application.closed);
        assertFalse(library.closed);
        application.close();
        library.close();
    }

    @Test
    void resolverOwnedLocationsCloseEvenWhenCancelledDuringPreparation() throws Exception {
        Path file = Files.writeString(temporary.resolve("project.input"), "fixture");
        TrackingLocation input = new TrackingLocation(null);
        AtomicBoolean cancelled = new AtomicBoolean();
        AnalysisInputResolver resolver = new AnalysisInputResolver() {
            public boolean supports(Path source) { return true; }
            public PreparedInput resolve(Path source, Runnable checkpoint) {
                cancelled.set(true);
                return new PreparedInput(List.of(input, input), List.of());
            }
        };
        var analyzer = new Analyzer(List.of(), List.of(resolver));
        var options = AnalysisOptions.defaults().withCancelled(cancelled::get).withListener(new Capture());
        assertEquals(CANCELLED, analyzer.analyze(file, options).status());
        assertEquals(1, input.closeCount);
    }

    @Test
    void cancellationStopsDiscoveryAndAlwaysReportsFinished() {
        AtomicBoolean cancelled = new AtomicBoolean();
        Capture capture = new Capture() {
            @Override public void onProgress(Progress progress) {
                super.onProgress(progress);
                if (progress.resources() == 1) cancelled.set(true);
            }
        };
        var options = AnalysisOptions.defaults().withCancelled(cancelled::get).withListener(capture);
        var request = new AnalysisRequest(List.of(), List.of(memory(Map.of("one.sql", "SELECT 1", "two.sql", "SELECT 2"))), options);
        assertEquals(CANCELLED, new Analyzer(List.of(sqlFiles("sql")), List.of()).analyze(request).status());
        assertEquals(AnalysisListener.Stage.FINISHED, capture.progress.getLast().stage());
        assertTrue(capture.sql.isEmpty());
    }

    @Test
    void deadlineAndListenerFailureHaveExplicitTerminalStates() {
        var resource = memory(Map.of("query.sql", "SELECT 1"));
        Capture capture = new Capture();
        var deadline = options(capture).withTimeout(Duration.ofNanos(1));
        var analyzer = new Analyzer(List.of(sqlFiles("sql")), List.of());
        assertEquals(PARTIAL, analyzer.analyze(new AnalysisRequest(List.of(), List.of(resource), deadline)).status());
        assertTrue(capture.diagnostics.stream().anyMatch(text -> text.startsWith("TIMEOUT")));
        AnalysisListener broken = new AnalysisListener() {
            @Override public void onSql(String module, AnalysisSeed seed, SqlNode template, SqlNodeRenderer.RenderedSql result) {
                throw new IllegalStateException("consumer failed");
            }
        };
        assertEquals(FAILED, analyzer.analyze(new AnalysisRequest(List.of(), List.of(resource), options(broken))).status());
    }

    @Test
    void pathEntryInheritsTimeoutAndUnlimitedOverrideDoesNotChangeDefaults() throws Exception {
        Path classes = compile("Sample", "public class Sample {}");
        var config = AnalyzerConfig.defaults().withTimeout(Duration.ofNanos(1));
        var analyzer = new Analyzer(config, List.of(), List.of());
        assertEquals(PARTIAL, analyzer.analyze(classes).status());

        Capture capture = new Capture();
        var unlimited = options(capture).withTimeout(Duration.ZERO);
        assertEquals(SUCCESS, analyzer.analyze(classes, unlimited).status());
        assertEquals(AnalysisListener.Stage.FINISHED, capture.progress.getLast().stage());
        // 单次覆盖不修改实例配置；null 明确恢复继承，而零明确取消超时限制。
        assertEquals(PARTIAL, analyzer.analyze(classes, unlimited.withTimeout(null)).status());
        assertTrue(capture.diagnostics.stream().anyMatch(text -> text.startsWith("TIMEOUT")));
        assertEquals(PARTIAL, analyzer.analyze(classes).status());
    }

    @Test
    void locationAndResourceRequestsInheritTheSameAnalyzerDefaults() throws Exception {
        var analyzer = new Analyzer(AnalyzerConfig.defaults().withTimeout(Duration.ofNanos(1)),
                List.of(sqlFiles("sql")), List.of());
        try (var location = new TrackingLocation(null)) {
            assertEquals(PARTIAL, analyzer.analyze(List.of(location)).status());
            assertEquals(SUCCESS, analyzer.analyze(new AnalysisRequest(List.of(location), List.of(),
                    AnalysisOptions.defaults().withTimeout(Duration.ZERO))).status());
            assertFalse(location.closed);
        }

        var resource = memory(Map.of("query.sql", "SELECT 16"));
        var request = new AnalysisRequest(List.of(), List.of(resource));
        assertEquals(PARTIAL, analyzer.analyze(request).status());
        Capture capture = new Capture();
        assertEquals(SUCCESS, analyzer.analyze(new AnalysisRequest(List.of(), List.of(resource),
                options(capture).withTimeout(Duration.ZERO))).status());
        assertEquals(List.of("SELECT 16"), capture.completeSql());
        assertEquals(PARTIAL, analyzer.analyze(request).status());
    }

    @Test
    void perCallCancellationAndListenerDoNotLeakIntoNextAnalysis() {
        var analyzer = new Analyzer(AnalyzerConfig.defaults(), List.of(sqlFiles("sql")), List.of());
        var resource = memory(Map.of("query.sql", "SELECT 17"));
        Capture cancelledCapture = new Capture();
        var cancelled = options(cancelledCapture).withCancelled(() -> true);
        assertEquals(CANCELLED, analyzer.analyze(new AnalysisRequest(List.of(), List.of(resource), cancelled)).status());
        var previousProgress = List.copyOf(cancelledCapture.progress);

        Capture next = new Capture();
        assertEquals(SUCCESS, analyzer.analyze(new AnalysisRequest(List.of(), List.of(resource), options(next))).status());
        assertEquals(List.of("SELECT 17"), next.completeSql());
        assertEquals(previousProgress, cancelledCapture.progress);
        assertTrue(cancelledCapture.sql.isEmpty());
        assertEquals(0, next.progress.getFirst().resources());
        assertEquals(1, next.progress.getLast().analyzed());
    }

    @Test
    void invalidBudgetsAreRejectedBeforeAnalysisStarts() {
        var defaults = AnalyzerConfig.defaults();
        assertThrows(NullPointerException.class, () -> defaults.withTimeout(null));
        assertThrows(IllegalArgumentException.class, () -> defaults.withTimeout(Duration.ofNanos(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> AnalysisOptions.defaults().withTimeout(Duration.ofNanos(-1)));
        assertThrows(ArithmeticException.class, () -> defaults.withTimeout(Duration.ofSeconds(Long.MAX_VALUE)));
        assertThrows(ArithmeticException.class,
                () -> AnalysisOptions.defaults().withTimeout(Duration.ofSeconds(Long.MAX_VALUE)));
        assertThrows(IllegalArgumentException.class, () -> defaults.withMaxTraceDepth(0));
        assertThrows(IllegalArgumentException.class, () -> defaults.withMethodCacheSize(0));
    }

    @Test
    void emptyDiscoverySucceedsAndInvalidContractsFailEarly() {
        var resource = memory(Map.of("ignore.txt", "not sql"));
        Analyzer analyzer = new Analyzer(List.of(sqlFiles("sql")), List.of());
        assertEquals(SUCCESS, analyzer.analyze(new AnalysisRequest(List.of(), List.of(resource))).status());
        assertThrows(IllegalArgumentException.class, () -> new AnalysisRequest(List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Analyzer(List.of(sqlFiles("same"), sqlFiles("same")), List.of()));
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(temporary.resolve("missing")));
        assertThrows(NullPointerException.class, () -> new AnalysisResult(null));
    }

    @Test
    void oneModuleFailureDoesNotPreventAnotherFromProducingSql() {
        SqlAnalysisModule broken = new SqlAnalysisModule() {
            public String id() { return "broken"; }
            public void register(DiscoveryRegistry registry) {
                registry.resource(resource -> true, (resource, seeds) -> { throw new IOException("broken adapter"); });
            }
        };
        Capture capture = new Capture();
        var analyzer = new Analyzer(List.of(broken, sqlFiles("sql")), List.of());
        assertEquals(PARTIAL, analyzer.analyze(new AnalysisRequest(List.of(),
                List.of(memory(Map.of("query.sql", "SELECT 12"))), options(capture))).status());
        assertEquals(List.of("SELECT 12"), capture.completeSql());
    }

    private Analyzer jdbc() { return new Analyzer(List.of(new JdbcAnalysisModule()), List.of()); }

    @Test
    void nullSqlIsNotTurnedIntoTheTextNull() throws Exception {
        Path classes = compile("Sample", """
                public class Sample {
                    public void query(java.sql.Connection c) throws Exception { c.prepareStatement((String) null); }
                }
                """);
        Capture capture = new Capture();
        assertEquals(PARTIAL, jdbc().analyze(classes, options(capture)).status());
        assertEquals(1, capture.sql.size());
        assertTrue(capture.completeSql().isEmpty());
    }

    @Test
    void deepMybatisTemplateRemainsAnUnresolvedCandidate() {
        String xml = "<mapper namespace=\"app\"><select id=\"query\">"
                + "<if test=\"flag\">".repeat(150) + "SELECT 1"
                + "</if>".repeat(150) + "</select></mapper>";
        Capture capture = new Capture();
        var analyzer = new Analyzer(List.of(new MybatisAnalysisModule()), List.of());
        assertEquals(PARTIAL, analyzer.analyze(new AnalysisRequest(List.of(),
                List.of(memory(Map.of("mapper.xml", xml))), options(capture))).status());
        assertEquals(1, capture.sql.size());
        assertFalse(capture.sql.getFirst().complete());
    }

    @Test
    void charAndBooleanConcatenationUsesJavaStringSemantics() throws Exception {
        Path classes = compile("Sample", """
                public class Sample {
                    private static String sql(char letter, boolean flag) { return "SELECT " + letter + flag; }
                    public void query(java.sql.Connection c) throws Exception { c.prepareStatement(sql('x', true)); }
                }
                """);
        Capture capture = new Capture();
        assertEquals(SUCCESS, jdbc().analyze(classes, options(capture)).status());
        assertEquals(List.of("SELECT xtrue"), capture.completeSql());
    }

    @Test
    void unsupportedMutableBuilderRetainsSqlCandidateAndReportsPartial() throws Exception {
        Path classes = compile("Sample", """
                public class Sample {
                    public void query(java.sql.Connection c, String table) throws Exception {
                        c.prepareStatement(new StringBuilder("SELECT * FROM ").append(table).toString());
                    }
                }
                """);
        Capture capture = new Capture();
        assertEquals(PARTIAL, jdbc().analyze(classes, options(capture)).status());
        assertEquals(1, capture.sql.size());
        assertFalse(capture.sql.getFirst().complete());
        assertTrue(capture.diagnostics.stream().anyMatch(text -> text.startsWith("UNSUPPORTED_VALUE")));
    }

    @Test
    void mybatisAnnotationsAreDiscoveredWithoutLoadingMethodBodies() throws Exception {
        Path classes = compile("Select", """
                package org.apache.ibatis.annotations;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface Select { String[] value(); }
                interface Mapper {
                    @Select({"SELECT", "13"}) Object query();
                    @Select("SELECT #{id}") Object find(int id);
                }
                """);
        Capture capture = new Capture();
        var analyzer = new Analyzer(List.of(new MybatisAnalysisModule()), List.of());
        assertEquals(SUCCESS, analyzer.analyze(classes, options(capture)).status());
        assertEquals(2, capture.sql.size());
        assertEquals(List.of("SELECT 13"), capture.completeSql());
    }

    @Test
    void reusingAnalyzerDoesNotLeakMapperFragmentsBetweenSessions() {
        var analyzer = new Analyzer(List.of(new MybatisAnalysisModule()), List.of());
        var complete = memory(Map.of("mapper.xml", """
                <mapper namespace="sample">
                    <sql id="columns">14</sql><select id="query">SELECT <include refid="columns"/></select>
                </mapper>
                """));
        assertEquals(SUCCESS, analyzer.analyze(new AnalysisRequest(List.of(), List.of(complete))).status());
        Capture capture = new Capture();
        var missing = memory(Map.of("mapper.xml", """
                <mapper namespace="sample"><select id="query">SELECT <include refid="columns"/></select></mapper>
                """));
        assertEquals(PARTIAL, analyzer.analyze(new AnalysisRequest(List.of(), List.of(missing), options(capture))).status());
        assertFalse(capture.sql.getFirst().complete());
    }

    @Test
    void modulesShareTheSameMethodScan() throws Exception {
        Path classes = compile("Sample", """
                public class Sample {
                    public void query(java.sql.Connection c) throws Exception { c.prepareStatement("SELECT 15"); }
                }
                """);
        SqlAnalysisModule second = new SqlAnalysisModule() {
            public String id() { return "second"; }
            public void register(DiscoveryRegistry registry) { new JdbcAnalysisModule().register(registry); }
        };
        Capture one = new Capture(), two = new Capture();
        assertEquals(SUCCESS, jdbc().analyze(classes, options(one)).status());
        assertEquals(SUCCESS, new Analyzer(List.of(new JdbcAnalysisModule(), second), List.of())
                .analyze(classes, options(two)).status());
        assertEquals(2, two.sql.size());
        assertEquals(one.progress.getLast().methods(), two.progress.getLast().methods());
    }

    @Test
    void ownedInputsCloseAfterListenerFailure() throws Exception {
        Path source = Files.writeString(temporary.resolve("input"), "fixture");
        TrackingLocation location = new TrackingLocation(null);
        AnalysisInputResolver resolver = new AnalysisInputResolver() {
            public boolean supports(Path path) { return true; }
            public PreparedInput resolve(Path path, Runnable checkpoint) {
                return new PreparedInput(List.of(location), List.of(memory(Map.of("query.sql", "SELECT 1"))));
            }
        };
        AnalysisListener broken = new AnalysisListener() {
            public void onSql(String id, AnalysisSeed seed, SqlNode node, SqlNodeRenderer.RenderedSql result) {
                throw new IllegalStateException("output failed");
            }
        };
        assertEquals(FAILED, new Analyzer(List.of(sqlFiles("sql")), List.of(resolver))
                .analyze(source, options(broken)).status());
        assertEquals(1, location.closeCount);
    }

    private AnalysisOptions options(AnalysisListener listener) {
        return AnalysisOptions.defaults().withListener(listener);
    }

    private Path compile(String name, String source) throws Exception {
        Path root = Files.createDirectories(temporary.resolve(UUID.randomUUID().toString()));
        Path java = Files.writeString(root.resolve(name + ".java"), source);
        Path classes = Files.createDirectories(root.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-proc:none", "-g", "-d", classes.toString(), java.toString()));
        return classes;
    }

    private Path jar(Path root, Path target) throws Exception {
        try (var output = new JarOutputStream(Files.newOutputStream(target)); var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                output.putNextEntry(new JarEntry(root.relativize(path).toString().replace('\\', '/')));
                Files.copy(path, output);
                output.closeEntry();
            }
        }
        return target;
    }

    private AnalysisResourceSource memory(Map<String, String> files) {
        return () -> files.entrySet().stream().map(entry -> new AnalysisResource() {
            public URI uri() { return URI.create("memory:/" + entry.getKey()); }
            public String name() { return entry.getKey(); }
            public InputStream openStream() { return new ByteArrayInputStream(entry.getValue().getBytes(StandardCharsets.UTF_8)); }
        });
    }

    private SqlAnalysisModule sqlFiles(String id) {
        return new SqlAnalysisModule() {
            public String id() { return id; }
            public void register(DiscoveryRegistry registry) {
                registry.resource(resource -> resource.name().endsWith(".sql"), (resource, seeds) -> {
                    String text;
                    try (var stream = resource.openStream()) { text = new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
                    var input = new SqlInput.Template(SqlNodeBuilder.text(text));
                    seeds.accept(new AnalysisSeed() {
                        public SourceLocation location() { return new SourceLocation(resource.uri(), "sql"); }
                        public SqlInput input() { return input; }
                    });
                });
            }
        };
    }

    private static class Capture implements AnalysisListener {
        final List<SqlNodeRenderer.RenderedSql> sql = new ArrayList<>();
        final List<SqlNode> templates = new ArrayList<>();
        final List<AnalysisSeed> seeds = new ArrayList<>();
        final List<String> modules = new ArrayList<>();
        final List<String> diagnostics = new ArrayList<>();
        final List<Progress> progress = new ArrayList<>();
        public void onProgress(Progress value) { progress.add(value); }
        public void onDiagnostic(String code, String message) { diagnostics.add(code + ": " + message); }
        public void onSql(String module, AnalysisSeed seed, SqlNode template, SqlNodeRenderer.RenderedSql result) {
            modules.add(module); seeds.add(seed); templates.add(template); sql.add(result);
        }
        List<String> completeSql() { return sql.stream().flatMap(result -> result.sql().stream()).toList(); }
    }

    private static class TrackingLocation implements AnalysisInputLocation {
        private final AnalysisInputLocation delegate;
        boolean closed;
        int closeCount;
        TrackingLocation(AnalysisInputLocation delegate) { this.delegate = delegate; }
        public @NonNull SourceType getSourceType() { return delegate == null ? SourceType.Application : delegate.getSourceType(); }
        public @NonNull List<BodyInterceptor> getBodyInterceptors() { return delegate == null ? List.of() : delegate.getBodyInterceptors(); }
        public @NonNull Optional<? extends SootClassSource> getClassSource(@NonNull ClassType type, @NonNull View view) {
            return delegate == null ? Optional.empty() : delegate.getClassSource(type, view);
        }
        public @NonNull Stream<? extends SootClassSource> getClassSources(@NonNull View view) {
            return delegate == null ? Stream.empty() : delegate.getClassSources(view);
        }
        public void close() throws Exception { closed = true; closeCount++; if (delegate != null) delegate.close(); }
    }
}
