package org.u80dc.jsqlint.core;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.LoggerFactory;
import org.u80dc.jsqlint.core.node.SqlNode;
import org.u80dc.jsqlint.core.node.SqlNodeRenderer;
import org.u80dc.jsqlint.core.resource.PathResourceSource;
import org.u80dc.jsqlint.core.spi.*;
import org.u80dc.jsqlint.jdbc.JdbcAnalysisModule;
import org.u80dc.jsqlint.mybatis.MybatisAnalysisModule;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.AnalysisResult.Status.*;

/** 验证真实字节码中的 SQL 入口定义、实际调用及 core 日志，不执行目标项目。 @author u80dc */
@Isolated("日志测试临时修改 AnalysisSession 的 logger")
class SqlCallSiteDiscoveryTest {
    @TempDir Path temporary;

    @Test
    void coreReportsAndLogsSitesWithoutExtractionSeedsAndUnionsOverlappingRules() throws Exception {
        Path classes = compile(Map.of("Example.java", """
                package sample;
                public class Example {
                    static void execute(String text) {}
                    public void run() { execute("first"); execute("second"); }
                }
                """));
        SqlAnalysisModule module = module("custom", registry -> {
            registry.sqlMethod((method, view) -> method.getName().equals("execute"));
            registry.sqlMethod((method, view) -> method.getName().equals("execute"));
            registry.sqlInvocation(site -> site.invocation().getMethodSignature().getName().equals("execute"));
            registry.sqlInvocation(site -> site.invocation().getMethodSignature().getName().equals("execute"));
        });
        Capture capture = new Capture();
        Logger logger = (Logger) LoggerFactory.getLogger(AnalysisSession.class);
        Level previous = logger.getLevel();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            // 同时覆盖 AnalysisInputLocation 列表输入：只有调用点规则也必须触发代码扫描。
            try (var location = new JavaClassPathAnalysisInputLocation(classes.toString(), SourceType.Application)) {
                var request = new AnalysisRequest(List.of(location), List.of(), options(capture));
                assertEquals(SUCCESS, new Analyzer(List.of(module), List.of()).analyze(request).status());
            }
            assertEquals(3, capture.sites.size());
            assertEquals(1, capture.declarations().size());
            assertEquals(2, capture.invocations().size());
            assertEquals(2, capture.invocations().stream().map(SqlCallSite::location).distinct().count());
            assertTrue(capture.invocations().stream().allMatch(site -> site.caller().orElseThrow().getName().equals("run")));
            assertEquals(0, capture.sqlCount, "调用点不是 SQL 提取结果，不应伪造 seed");
            var logs = appender.list.stream().filter(event -> event.getFormattedMessage().startsWith("SQL 调用点:")).toList();
            assertEquals(3, logs.size());
            assertTrue(logs.stream().allMatch(event -> event.getLevel() == Level.INFO
                    && event.getFormattedMessage().contains("class=sample.Example")
                    && event.getFormattedMessage().contains("method=<sample.Example: void execute(java.lang.String)>")));
            assertTrue(logs.stream().anyMatch(event -> event.getFormattedMessage().contains("caller=<sample.Example: void run()>")));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
            appender.stop();
        }
    }

    @Test
    void jdbcRecognizesStandardOverloadsPreparedExecutionsAndBatchButRejectsLookalikes() throws Exception {
        Path classes = compile(Map.of("Example.java", """
                package sample;
                import java.sql.*;
                interface CustomStatement extends Statement { void execute(String sql, boolean flag); }
                abstract class DriverStatement implements CustomStatement {}
                class Lookalike { void execute(String sql) {} void executeBatch() {} }
                public class Example {
                    public void run(Connection c, CustomStatement s, PreparedStatement p, CallableStatement call,
                                    DriverStatement driver, Lookalike other) throws Exception {
                        s.execute("SELECT 1");
                        s.execute("SELECT 2");
                        s.executeUpdate("UPDATE t SET x=1", new int[]{1});
                        s.executeLargeUpdate("UPDATE t SET x=2", new String[]{"id"});
                        c.prepareStatement("SELECT 3", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                        c.prepareCall("{call proc()}");
                        c.nativeSQL("SELECT 4");
                        p.execute(); p.executeQuery(); p.executeUpdate(); p.executeLargeUpdate(); p.addBatch();
                        call.execute(); s.executeBatch(); s.executeLargeBatch();
                        driver.executeQuery("SELECT 5");
                        s.execute("not a standard overload", true);
                        other.execute("not jdbc"); other.executeBatch();
                    }
                }
                """));
        Capture capture = new Capture();
        // 外部传入的 PreparedStatement 没有工厂来源，调用点仍全部报告，SQL 关联应标为部分完成。
        assertEquals(PARTIAL, new Analyzer(List.of(new JdbcAnalysisModule()), List.of())
                .analyze(classes, options(capture)).status(), capture.diagnostics.toString());
        assertTrue(capture.diagnostics.stream().allMatch(message -> message.startsWith("SQL_DATA_FLOW_INCOMPLETE:")));
        assertEquals(16, capture.invocations().size());
        assertEquals(8, capture.sqlCount);
        assertTrue(capture.declarations().isEmpty());
        assertTrue(capture.sites.stream().allMatch(site -> site.caller().orElseThrow().getDeclClassType().toString().equals("sample.Example")));
        assertTrue(capture.sites.stream().noneMatch(site -> site.target().getDeclClassType().toString().equals("sample.Lookalike")
                || site.target().getParameterTypes().stream().anyMatch(type -> type.toString().equals("boolean"))));
        assertEquals(2, capture.invocations().stream().filter(site -> site.target().getName().equals("execute")
                && site.target().getParameterTypes().size() == 1).count());
    }

    @Test
    void xmlBindsMapperDeclarationsOverloadsInheritedMethodsAndActualCalls() throws Exception {
        Path classes = compile(Map.of("Example.java", """
                package sample;
                interface Parent { Object inherited(int id); }
                interface Mapper extends Parent {
                    Object query(int id); Object query(String name); Object unused(); Object notMapped();
                    default Object helper() { return null; }
                    static Object staticHelper() { return null; }
                }
                interface Child extends Mapper {}
                interface Override extends Mapper { Object query(int id); }
                interface UnrelatedMapper { Object query(int id); }
                public class Example {
                    public void run(Mapper m, Child child, Override overridden, UnrelatedMapper other) {
                        m.query(1); m.query(2); m.query("name"); m.inherited(3); child.inherited(4);
                        m.helper(); Mapper.staticHelper(); m.notMapped(); other.query(5); overridden.query(6);
                    }
                }
                """));
        Files.writeString(classes.resolve("mapper.xml"), """
                <mapper namespace="sample.Mapper">
                    <select id="query">SELECT 1</select>
                    <select id="unused">SELECT 2</select>
                    <select id="inherited">SELECT 3</select>
                    <select id="helper">SELECT 4</select>
                    <select id="staticHelper">SELECT 5</select>
                </mapper>
                """);
        Capture capture = new Capture();
        var analyzer = new Analyzer(List.of(new MybatisAnalysisModule()), List.of());
        assertEquals(SUCCESS, analyzer.analyze(classes, options(capture)).status(), capture.diagnostics.toString());
        assertEquals(4, capture.declarations().size());
        assertEquals(Set.of("query", "unused", "inherited"), capture.declarations().stream()
                .map(site -> site.target().getName()).collect(Collectors.toSet()));
        assertEquals(5, capture.invocations().size());
        assertEquals(5, capture.sqlCount, "保留 XML 的 SQL 提取，即使没有可用的 Mapper 方法");
        assertTrue(capture.invocations().stream().allMatch(site -> site.caller().orElseThrow().getName().equals("run")));
        assertTrue(capture.declarations().stream().anyMatch(site -> site.target().getDeclClassType().toString().equals("sample.Parent")));

        // 同一分析器复用时，新会话没有 XML，不能继承上一项目的 Mapper 绑定。
        Capture next = new Capture();
        try (var location = new JavaClassPathAnalysisInputLocation(classes.toString(), SourceType.Application)) {
            assertEquals(SUCCESS, analyzer.analyze(new AnalysisRequest(List.of(location), List.of(), options(next))).status());
        }
        assertTrue(next.sites.isEmpty());
    }

    @Test
    void annotationsIdentifyUnusedAndInheritedMapperMethodsIncludingRepeatableAndProvider() throws Exception {
        Path classes = compile(Map.of(
                "Select.java", """
                        package org.apache.ibatis.annotations;
                        import java.lang.annotation.*;
                        @Retention(RetentionPolicy.RUNTIME) @Repeatable(Select.List.class)
                        public @interface Select {
                            String[] value();
                            @Retention(RetentionPolicy.RUNTIME) @interface List { Select[] value(); }
                        }
                        """,
                "SelectProvider.java", """
                        package org.apache.ibatis.annotations;
                        @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                        public @interface SelectProvider { Class<?> type(); String method(); }
                        """,
                "Example.java", """
                        package sample;
                        import org.apache.ibatis.annotations.*;
                        interface Parent { @Select("SELECT 1") Object inherited(); }
                        interface Mapper extends Parent {
                            @Select("SELECT 2") Object unused();
                            @Select("SELECT 3") @Select("SELECT 4") Object repeated();
                            @SelectProvider(type=Example.class, method="sql") Object provider();
                            default Object helper() { return null; }
                        }
                        public class Example {
                            public static String sql() { return "SELECT 5"; }
                            public void run(Mapper m) { m.inherited(); m.repeated(); m.provider(); m.helper(); }
                        }
                        """));
        Capture capture = new Capture();
        assertEquals(PARTIAL, new Analyzer(List.of(new MybatisAnalysisModule()), List.of())
                .analyze(classes, options(capture)).status());
        assertEquals(4, capture.declarations().size());
        assertEquals(3, capture.invocations().size());
        assertEquals(5, capture.sqlCount);
        assertTrue(capture.diagnostics.stream().anyMatch(message -> message.contains("Provider")));
        assertTrue(capture.events.indexOf("sql") > capture.events.lastIndexOf("site"),
                "Provider SQL 暂时无法提取，也必须先完整报告已确认的调用点");
    }

    @Test
    void xmlWithoutBytecodeDoesNotInventMethodSignatures() throws Exception {
        Path resources = Files.createDirectory(temporary.resolve("resources"));
        Files.writeString(resources.resolve("mapper.xml"),
                "<mapper namespace=\"missing.Mapper\"><select id=\"query\">SELECT 1</select></mapper>");
        Capture capture = new Capture();
        var request = new AnalysisRequest(List.of(), List.of(new PathResourceSource(resources)), options(capture));
        assertEquals(SUCCESS, new Analyzer(List.of(new MybatisAnalysisModule()), List.of()).analyze(request).status());
        assertTrue(capture.sites.isEmpty());
        assertEquals(1, capture.sqlCount);
    }

    @Test
    void brokenCallSiteRuleDoesNotHideOtherRulesAndListenerFailureStopsAnalysis() throws Exception {
        Path classes = compile(Map.of("Example.java", "public class Example { public void query() {} }"));
        var rules = module("custom", registry -> {
            registry.sqlMethod((method, view) -> { throw new IllegalStateException("broken rule"); });
            registry.sqlMethod((method, view) -> method.getName().equals("query"));
        });
        Capture capture = new Capture();
        assertEquals(PARTIAL, new Analyzer(List.of(rules), List.of()).analyze(classes, options(capture)).status());
        assertEquals(1, capture.declarations().size());
        assertTrue(capture.diagnostics.stream().anyMatch(message -> message.startsWith("SQL_CALL_SITE_DISCOVERY")));
        var working = module("working", registry -> registry.sqlMethod((method, view) -> method.getName().equals("query")));
        var brokenListener = new AnalysisListener() {
            @Override public void onSqlCallSite(String moduleId, SqlCallSite site) { throw new IllegalStateException("output failed"); }
        };
        assertEquals(FAILED, new Analyzer(List.of(working), List.of()).analyze(classes, options(brokenListener)).status());
    }

    private Path compile(Map<String, String> sources) throws Exception {
        Path root = Files.createDirectories(temporary.resolve(UUID.randomUUID().toString()));
        Path classes = Files.createDirectory(root.resolve("classes"));
        List<String> arguments = new ArrayList<>(List.of("-proc:none", "-g", "-d", classes.toString()));
        for (var entry : sources.entrySet()) arguments.add(Files.writeString(root.resolve(entry.getKey()), entry.getValue()).toString());
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)));
        return classes;
    }

    private static AnalysisOptions options(AnalysisListener listener) { return AnalysisOptions.defaults().withListener(listener); }

    private static SqlAnalysisModule module(String id, Consumer<DiscoveryRegistry> registration) {
        return new SqlAnalysisModule() {
            public String id() { return id; }
            public void register(DiscoveryRegistry registry) { registration.accept(registry); }
        };
    }

    private static class Capture implements AnalysisListener {
        final List<SqlCallSite> sites = new ArrayList<>();
        final List<String> diagnostics = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        int sqlCount;
        public void onSqlCallSite(String moduleId, SqlCallSite site) { sites.add(site); events.add("site"); }
        public void onDiagnostic(String code, String message) { diagnostics.add(code + ": " + message); }
        public void onSql(String moduleId, AnalysisSeed seed, SqlNode template, SqlNodeRenderer.RenderedSql sql) {
            sqlCount++;
            events.add("sql");
        }
        List<SqlCallSite> declarations() { return sites.stream().filter(site -> site.kind() == SqlCallSite.Kind.DECLARATION).toList(); }
        List<SqlCallSite> invocations() { return sites.stream().filter(site -> site.kind() == SqlCallSite.Kind.INVOCATION).toList(); }
    }
}
