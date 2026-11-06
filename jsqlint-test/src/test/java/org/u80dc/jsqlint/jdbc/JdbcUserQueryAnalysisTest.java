package org.u80dc.jsqlint.jdbc;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.spi.AnalysisSeed;
import org.u80dc.jsqlint.core.spi.SqlCallSite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.AnalysisResult.Status.*;

/** 使用真实共享 Maven 项目验证自动编译、JDBC SQL 发现和动态文本保留。 @author u80dc */
@Isolated("Maven Embedder 使用进程共享状态，避免与其他编译测试并发")
class JdbcUserQueryAnalysisTest {
    private static final String USER_REPOSITORY = "org.u80dc.jsqlint.samples.jdbc.UserRepository";
    private static final String SELECT = "SELECT id, username, email, age, active FROM app_users";

    @Test
    @Tag("integration")
    void analyzerDiscoversCrudSqlFromMavenProject() {
        Path project = sampleProject();
        String outputId = "jdbc-user-query-test-" + UUID.randomUUID();
        Path output = Path.of(System.getProperty("java.io.tmpdir"), ".jsqlint", outputId).toAbsolutePath().normalize();
        var capture = new Capture(output);

        // 直接分析项目目录，由 ServiceLoader 加载 commons 编译器及 JDBC 模块，不预编译样例。
        var analyzer = new Analyzer(AnalyzerConfig.defaults().withCleanupClassOutput(true));
        var result = analyzer.analyze(project, AnalysisOptions.defaults()
                .withClassOutputId(outputId).withListener(capture));
        String diagnostics = String.join("\n", capture.diagnostics);

        assertTrue(result.status() == SUCCESS || result.status() == PARTIAL, diagnostics);
        // 允许尚未支持的表达式导致 PARTIAL，不接受超时、预算耗尽、类解析或 SQL 提取异常。
        assertTrue(capture.diagnostics.stream().allMatch(message -> message.startsWith("UNSUPPORTED_VALUE: ")
                || message.startsWith("SQL_DATA_FLOW_INCOMPLETE: ")), diagnostics);
        if (result.status() == PARTIAL) assertFalse(capture.diagnostics.isEmpty(), "PARTIAL 应报告能力限制");
        assertTrue(capture.compiled, "应在 Analyzer 分配的目录中生成新的 class 文件");
        assertFalse(Files.exists(output), "分析结束后应清理本次编译输出");

        // 顶层结果在编译目录清理后仍能直接读取 SQL，保留不同 main 调用的相同文本。
        var rawSqls = result.rawSqls();
        assertEquals(13, rawSqls.stream().map(RawSql.Variant::text).distinct().count());
        assertEquals(3, rawSqls.stream().filter(sql -> sql.text().startsWith("INSERT INTO app_users")).count());
        assertTrue(rawSqls.stream().allMatch(sql -> sql.trace().isPresent()));
        assertThrows(UnsupportedOperationException.class, rawSqls::clear);

        assertEquals(6, capture.sql.size(), diagnostics);
        assertEquals(8, capture.callSites.size(), "6 个 SQL 参数入口及 2 个无参 PreparedStatement 执行调用");
        assertTrue(capture.callSites.stream().allMatch(site -> site.kind() == SqlCallSite.Kind.INVOCATION
                && site.caller().orElseThrow().getDeclClassType().toString().equals(USER_REPOSITORY)));
        assertEquals(2, capture.callSites.stream().filter(site -> site.target().getParameterTypes().isEmpty()).count());
        assertEquals(8, result.extractions().stream().map(SqlExtraction::callSite).distinct().count());
        assertTrue(result.extractions().stream().allMatch(extraction -> !extraction.statements().isEmpty()
                && extraction.statements().stream().allMatch(statement -> statement.sql().isPresent())));
        assertTrue(result.extractions().stream().allMatch(extraction -> !extraction.callChain().isEmpty()
                && extraction.callChain().getFirst().caller().orElseThrow().getName().equals("main")));
        assertTrue(capture.sql.stream().allMatch(sql -> sql.module().equals("jdbc") && sql.owner().equals(USER_REPOSITORY)));
        Map<String, ExtractedSql> byMethod = capture.sql.stream()
                .collect(Collectors.toMap(ExtractedSql::method, Function.identity()));
        assertEquals(Set.of("createTable", "insert", "findById", "findUsers", "update", "deleteById"), byMethod.keySet());

        assertEquals("CREATE TABLE app_users (id BIGINT PRIMARY KEY, username VARCHAR(100) NOT NULL, "
                        + "email VARCHAR(200) NOT NULL, age INTEGER NOT NULL, active BOOLEAN NOT NULL)",
                byMethod.get("createTable").rendered().sql().orElseThrow());
        assertTextContains(byMethod.get("insert"), "INSERT INTO app_users (id, username, email, age, active) VALUES (");
        assertTextContains(byMethod.get("findById"), SELECT + " WHERE id = ");
        assertTextContains(byMethod.get("findUsers"), SELECT + " WHERE 1 = 1", " AND username LIKE ",
                " AND age >= ", " AND active = TRUE", " ORDER BY id");
        assertTextContains(byMethod.get("update"), "UPDATE app_users", " SET email = ", ", age = ", ", active = ", " WHERE id = ");
        assertTextContains(byMethod.get("deleteById"), "DELETE FROM app_users", " WHERE id = ");

        // raw SQL 来自过程间传播链，保留业务参数名；不受旧模板求值器的支持范围影响。
        assertTrue(result.extractions().stream().flatMap(e -> e.statements().stream()).allMatch(s -> s.rawSql().complete()),
                () -> result.extractions().stream().flatMap(e -> e.statements().stream())
                        .flatMap(s -> s.rawSql().issues().stream()).toList().toString());
        Map<String, Set<String>> rawByMethod = result.extractions().stream().collect(Collectors.groupingBy(
                e -> e.callSite().caller().orElseThrow().getName(), Collectors.flatMapping(
                        e -> e.statements().stream().flatMap(s -> s.rawSql().variants().stream()).map(RawSql.Variant::text),
                        Collectors.toSet())));
        assertEquals(Set.of("INSERT INTO app_users (id, username, email, age, active) VALUES "
                + "(${id}, '${username}', '${email}', ${age}, ${active})"), rawByMethod.get("insert"));
        assertEquals(Set.of(SELECT + " WHERE id = ${id}"), rawByMethod.get("findById"));
        assertEquals(Set.of("UPDATE app_users SET email = '${email}', age = ${age}, active = ${active} WHERE id = ${id}"),
                rawByMethod.get("update"));
        assertEquals(Set.of("DELETE FROM app_users WHERE id = ${id}"), rawByMethod.get("deleteById"));
        assertEquals(8, rawByMethod.get("findUsers").size(), "三个可选条件的文本组合应全部保留");
        assertTrue(rawByMethod.get("findUsers").contains(SELECT + " WHERE 1 = 1 ORDER BY id"));
        assertTrue(rawByMethod.get("findUsers").contains(SELECT + " WHERE 1 = 1 AND username LIKE '%${keyword}%'"
                + " AND age >= ${minimumAge} AND active = TRUE ORDER BY id"));

        // public 方法仍允许来自样例 main 以外的调用，不能用已知实参覆盖掉所有未知参数。
        for (String method : List.of("insert", "findById", "findUsers", "update", "deleteById")) {
            var sql = byMethod.get(method);
            assertTrue(sql.template().isDynamic(), method + " 应保留参数或条件节点");
            assertTrue(sql.rendered().sql().isEmpty(), method + " 不应伪造完整 SQL");
        }
        var finished = capture.progress.getLast();
        assertEquals(AnalysisListener.Stage.FINISHED, finished.stage());
        assertEquals(6, finished.discovered());
        assertEquals(6, finished.analyzed());
    }

    /** 从 Maven 模块或 IDE 的工作目录向上定位仓库，避免写死机器路径或依赖预编译产物。 */
    private static Path sampleProject() {
        for (Path directory = Path.of("").toAbsolutePath().normalize(); directory != null; directory = directory.getParent()) {
            Path project = directory.resolve("shared-test-resources/jdbc-user-query");
            if (Files.isRegularFile(project.resolve("pom.xml"))) return project;
        }
        throw new AssertionError("Cannot locate shared-test-resources/jdbc-user-query from " + Path.of("").toAbsolutePath());
    }

    private static void assertTextContains(ExtractedSql sql, String... expected) {
        List<String> fragments = textFragments(sql.template()).toList();
        for (String text : expected) {
            assertTrue(fragments.stream().anyMatch(fragment -> fragment.contains(text)),
                    () -> sql.method() + " 缺少 SQL 文本: " + text + "，实际片段: " + fragments);
        }
    }

    /** 查看字符串追踪产生的文本、混合节点和分支，不依赖 record 的 toString 格式。 */
    private static Stream<String> textFragments(SqlNode node) {
        return switch (node) {
            case StaticTextSqlNode text -> Stream.of(text.text());
            case MixedSqlNode mixed -> mixed.contents().stream().flatMap(JdbcUserQueryAnalysisTest::textFragments);
            case IfSqlNode conditional -> textFragments(conditional.contents());
            case ChooseSqlNode choose -> Stream.concat(
                    choose.whenContents().stream().flatMap(JdbcUserQueryAnalysisTest::textFragments),
                    choose.otherwiseContents() == null ? Stream.empty() : textFragments(choose.otherwiseContents()));
            default -> Stream.empty();
        };
    }

    private record ExtractedSql(String module, String owner, String method, SqlNode template,
                                SqlNodeRenderer.RenderedSql rendered) {}

    private static final class Capture implements AnalysisListener {
        private final Path output;
        private final List<ExtractedSql> sql = new ArrayList<>();
        private final List<SqlCallSite> callSites = new ArrayList<>();
        private final List<String> diagnostics = new ArrayList<>();
        private final List<Progress> progress = new ArrayList<>();
        private boolean compiled;

        private Capture(Path output) { this.output = output; }

        @Override
        public void onProgress(Progress value) {
            progress.add(value);
            if (!compiled && value.stage() == Stage.RESOURCES) {
                Path classes = output.resolve("org/u80dc/jsqlint/samples/jdbc");
                assertTrue(Files.isRegularFile(classes.resolve("UserRepository.class")));
                assertTrue(Files.isRegularFile(classes.resolve("JdbcUserApplication.class")));
                compiled = true;
            }
        }

        @Override
        public void onDiagnostic(String code, String message) { diagnostics.add(code + ": " + message); }

        @Override
        public void onSqlCallSite(String moduleId, SqlCallSite site) {
            assertEquals("jdbc", moduleId);
            callSites.add(site);
        }

        @Override
        public void onSql(String moduleId, AnalysisSeed seed, SqlNode template, SqlNodeRenderer.RenderedSql rendered) {
            var jdbc = assertInstanceOf(JdbcSqlSeed.class, seed);
            sql.add(new ExtractedSql(moduleId, jdbc.site().method().getDeclClassType().toString(),
                    jdbc.site().method().getName(), template, rendered));
        }
    }
}
