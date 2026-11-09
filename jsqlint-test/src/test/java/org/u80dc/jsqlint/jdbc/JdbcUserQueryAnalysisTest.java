package org.u80dc.jsqlint.jdbc;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.parameter.ParameterValueInferenceResult;
import org.u80dc.jsqlint.core.parameter.JointSqlInferenceResult;
import org.u80dc.jsqlint.core.spi.AnalysisSeed;
import org.u80dc.jsqlint.core.spi.SqlCallSite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    void analyzerDiscoversCrudSqlFromMavenProject() throws IOException {
        Path project = sampleProject();
        String outputId = "jdbc-user-query-test-" + UUID.randomUUID();
        Path output = Path.of(System.getProperty("java.io.tmpdir"), ".jsqlint", outputId).toAbsolutePath().normalize();
        var capture = new Capture(output);

        // 直接分析项目目录，由 ServiceLoader 加载 commons 编译器及 JDBC 模块，不预编译样例。
        var analyzer = new Analyzer(AnalyzerConfig.defaults().withCleanupClassOutput(true));
        var result = analyzer.analyze(project, AnalysisOptions.defaults()
                .withClassOutputId(outputId).withListener(capture));
        var samples = printSqlReport(project, result);
        String diagnostics = String.join("\n", capture.diagnostics);

        assertTrue(result.status() == SUCCESS || result.status() == PARTIAL, diagnostics);
        // SQL 提取阶段仍不接受超时、预算耗尽或解析异常；样例的循环/异常边会使联合路径覆盖不完整。
        assertTrue(result.diagnostics().stream().allMatch(diagnostic -> Set.of("UNSUPPORTED_VALUE",
                "SQL_DATA_FLOW_INCOMPLETE", "JOINT_ANALYSIS_INCOMPLETE").contains(diagnostic.code())), diagnostics);
        var jointDiagnostics = result.diagnostics().stream().filter(d -> d.code().equals("JOINT_ANALYSIS_INCOMPLETE")).toList();
        long uncoveredExtractions = result.extractions().stream().filter(extraction -> extraction.rawSqls().stream()
                .anyMatch(sql -> !sql.jointInference().orElseThrow().coverageComplete())).count();
        assertEquals(uncoveredExtractions, jointDiagnostics.size(), "每个未完整覆盖的调用上下文都应保留诊断");
        for (var diagnostic : jointDiagnostics) {
            assertTrue(diagnostic.incomplete());
            assertTrue(diagnostic.location().isPresent());
            assertTrue(result.extractions().stream().filter(e -> e.location().equals(diagnostic.location().get()))
                    .flatMap(e -> e.rawSqls().stream()).flatMap(sql -> sql.jointInference().stream())
                    .anyMatch(joint -> !joint.coverageComplete() && joint.status() == JointSqlInferenceResult.Status.UNKNOWN),
                    diagnostic.toString());
        }
        if (result.status() == PARTIAL) assertFalse(capture.diagnostics.isEmpty(), "PARTIAL 应报告能力限制");
        assertTrue(capture.compiled, "应在 Analyzer 分配的目录中生成新的 class 文件");
        assertFalse(Files.exists(output), "分析结束后应清理本次编译输出");

        // 顶层结果在编译目录清理后仍能直接读取 SQL，保留不同 main 调用的相同文本。
        var rawSqls = result.rawSqls();
        assertEquals(13, rawSqls.stream().map(RawSql.Variant::text).distinct().count());
        assertEquals(3, rawSqls.stream().filter(sql -> sql.text().startsWith("INSERT INTO app_users")).count());
        assertTrue(rawSqls.stream().allMatch(sql -> sql.trace().isPresent()));
        assertTrue(rawSqls.stream().allMatch(sql -> sql.jointInference().isPresent()), "输入关闭后仍保留联合求解结果");
        assertTrue(rawSqls.stream().allMatch(sql -> sql.bindings().isEmpty()), "纯拼接样例不存在 JDBC setXxx 绑定");
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

        var inserts = result.rawSqls().stream().filter(sql -> sql.text().startsWith("INSERT INTO")).toList();
        Set<Object> names = inserts.stream().flatMap(sql -> sql.parameters().stream()).filter(p -> p.name().equals("username"))
                .flatMap(p -> p.possibleValues().stream()).map(v -> assertInstanceOf(SqlValue.Known.class, v.value()).value())
                .collect(Collectors.toSet());
        assertEquals(Set.of("alice", "bob", "O''Neil"), names, "推断值应重演 quote 中的单引号转义");
        assertTrue(inserts.stream().flatMap(sql -> sql.parameters().stream()).allMatch(p -> p.complete()));
        assertTrue(inserts.stream().flatMap(sql -> sql.parameters().stream()).flatMap(p -> p.possibleValues().stream())
                .allMatch(v -> !v.sampled() && !v.propagationPath().callChain().isEmpty()));
        assertTrue(inserts.stream().flatMap(sql -> sql.parameters().stream()).flatMap(p -> p.possibleValues().stream())
                .filter(v -> v.value().equals(new SqlValue.Known("O''Neil")))
                .allMatch(v -> v.initialValues().stream().anyMatch(input -> input.value().equals(new SqlValue.Known("O'Neil")))));

        // 模拟值来自同一个联合组合，直接代入转换后的槽位；不重新做笛卡尔积或二次转义。
        Set<String> simulatedSqls = samples.stream().flatMap(sample -> sample.rendered().sql().stream()).collect(Collectors.toSet());
        assertTrue(simulatedSqls.contains("INSERT INTO app_users (id, username, email, age, active) VALUES "
                + "(1, 'alice', 'alice@example.test', 24, true)"));
        assertTrue(simulatedSqls.contains("INSERT INTO app_users (id, username, email, age, active) VALUES "
                + "(2, 'bob', 'bob@example.test', 31, false)"));
        assertTrue(simulatedSqls.contains("INSERT INTO app_users (id, username, email, age, active) VALUES "
                + "(3, 'O''Neil', 'oneil@example.test', 37, true)"));
        assertTrue(simulatedSqls.contains("UPDATE app_users SET email = 'bob.updated@example.test', age = 33, active = true WHERE id = 2"));
        assertTrue(simulatedSqls.contains("DELETE FROM app_users WHERE id = 1"));
        assertTrue(simulatedSqls.stream().noneMatch(sql -> sql.contains("${")), "完整模拟 SQL 不应留下参数槽位");
        assertTrue(samples.stream().noneMatch(sample -> sample.status() == JointSqlInferenceResult.Status.UNSATISFIABLE));
        assertTrue(samples.stream().anyMatch(sample -> sample.status() == JointSqlInferenceResult.Status.UNKNOWN),
                "未知路径的样例仍可供人工核查，但不能标成已证明可行");

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

    /** 打印每条静态候选及对应参数；保留准备点、执行点和不同调用路径，便于核对分析结果。 */
    private static List<SampleSql> printSqlReport(Path project, AnalysisResult result) throws IOException {
        List<SampleSql> samples = new ArrayList<>();
        var report = new StringBuilder("jdbc-user-query SQL 与参数分析报告\n");
        report.append("分析状态: ").append(result.status())
                .append("\nSQL 候选记录: ").append(result.rawSqls().size())
                .append("\n不同 SQL 文本: ").append(result.rawSqls().stream().map(RawSql.Variant::text).distinct().count())
                .append("\n说明: 原始候选包含准备点/执行点及不同路径；联合可行性见各条求解状态，不代表实际执行次数。\n")
                .append("拼接参数属于 ${name} 槽位；JDBC 绑定参数属于 setXxx 设置的 ? 或命名参数。\n")
                .append("模拟 SQL 按同一联合组合生成；UNKNOWN 仅供核查，尚未证明路径可行；UNSATISFIABLE 不生成样例。\n");
        int number = 0;
        for (var extraction : result.extractions()) {
            for (var statement : extraction.statements()) {
                for (var sql : statement.rawSql().variants()) {
                    report.append("\n[SQL ").append(++number).append("]\n")
                            .append("所属方法: ").append(extraction.callSite().caller().orElseThrow())
                            .append("\n调用点: ").append(extraction.callSite().target())
                            .append("\n位置: ").append(extraction.callSite().location())
                            .append("\n入口调用链: ").append(extraction.callChain())
                            .append("\nraw SQL: ").append(sql.text())
                            .append("\n拼接参数列表:");
                    if (sql.parameters().isEmpty()) report.append(" []\n");
                    else {
                        report.append('\n');
                        for (var parameter : sql.parameters()) appendParameter(report, "", parameter);
                    }
                    report.append("JDBC 绑定参数列表:");
                    if (sql.bindings().isEmpty()) report.append(" []\n");
                    else {
                        report.append('\n');
                        for (var binding : sql.bindings())
                            appendParameter(report, "key=" + binding.key() + ", ", binding.inference());
                    }
                    if (!statement.rawSql().issues().isEmpty())
                        report.append("SQL 诊断: ").append(statement.rawSql().issues()).append('\n');
                    int sqlNumber = number;
                    sql.jointInference().ifPresentOrElse(joint -> {
                        report.append("联合路径: ").append(joint.status()).append(", combinations=").append(joint.combinations().size()).append('\n');
                        if (joint.status() == JointSqlInferenceResult.Status.UNSATISFIABLE) {
                            report.append("模拟样例 SQL: 不生成（已证明路径无解）\n");
                        } else if (joint.combinations().isEmpty()) {
                            report.append("模拟样例 SQL: 未生成（没有联合参数组合，保留原始 SQL 与诊断）\n");
                        }
                        int combinationNumber = 0;
                        for (var combination : joint.combinations()) {
                            report.append("  联合组合 ").append(++combinationNumber).append(": ").append(combination.status()).append(" slots=");
                            var values = new LinkedHashMap<String, SqlValue>();
                            combination.parameters().forEach((name, value) -> values.put(name, value.value()));
                            report.append(values).append(" bindings=").append(combination.bindings().stream()
                                    .map(binding -> binding.keyValue().value() + "=" + binding.value().value()).toList())
                                    .append(" conditions=").append(combination.conditions().size()).append('\n');
                            if (combination.status() == JointSqlInferenceResult.Status.UNSATISFIABLE) continue;
                            // 当前共享样例只有拼接槽位；若以后加入 setXxx，需单独按 SQL 方言处理绑定字面量。
                            assertTrue(combination.bindings().isEmpty(), "此样例的模拟渲染不应忽略 JDBC 绑定值");
                            var rendered = SqlNodeRenderer.render(sql.template(), values);
                            samples.add(new SampleSql(sqlNumber, combinationNumber, combination.status(), rendered));
                            String label = combination.status() == JointSqlInferenceResult.Status.SATISFIABLE
                                    ? "已满足建模条件" : "尚未证明路径可行";
                            report.append("    模拟样例 SQL（").append(label).append("）: ")
                                    .append(rendered.sql().orElse("未生成完整 SQL（存在未知参数）")).append('\n');
                            if (!rendered.diagnostics().isEmpty()) report.append("    渲染诊断: ").append(rendered.diagnostics()).append('\n');
                            if (!combination.issues().isEmpty()) report.append("    组合诊断: ").append(combination.issues()).append('\n');
                        }
                        if (!joint.issues().isEmpty()) report.append("联合诊断: ").append(joint.issues()).append('\n');
                    }, () -> report.append("模拟样例 SQL: 未生成（尚无联合求解结果）\n"));
                }
            }
        }
        long completeSamples = samples.stream().filter(sample -> sample.rendered().sql().isPresent()).count();
        long distinctSamples = samples.stream().flatMap(sample -> sample.rendered().sql().stream()).distinct().count();
        var statusCounts = result.rawSqls().stream().map(sql -> sql.jointInference().orElseThrow().status())
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        report.append("\n报告统计:\nSQL 联合状态: ").append(statusCounts)
                .append("\n完整模拟 SQL（按联合组合）: ").append(completeSamples)
                .append("\n不同模拟 SQL 文本: ").append(distinctSamples)
                .append("\n未完成渲染的联合组合: ").append(samples.size() - completeSamples).append('\n');
        // UTF-8 文件保留中文和值的原始内容，避免终端代码页影响阅读。
        Path file = project.getParent().getParent().resolve("jsqlint-test/target/jdbc-user-query-sql-report.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, report);
        System.out.print(report);
        System.out.println("SQL 与参数报告: " + file);
        // 另存精简 SQL 清单；同文不同路径的对应关系仍完整保留在上面的详细报告中。
        var examples = new LinkedHashMap<String, List<SampleSql>>();
        for (SampleSql sample : samples) sample.rendered().sql().ifPresent(text ->
                examples.computeIfAbsent(text, ignored -> new ArrayList<>()).add(sample));
        var script = new StringBuilder("-- jdbc-user-query 静态分析模拟 SQL（按文本去重）\n"
                + "-- UNKNOWN 尚未证明路径可行；仅供查看，未执行数据库操作；无解候选见详细报告。\n");
        int exampleNumber = 0;
        for (var entry : examples.entrySet()) {
            var statuses = entry.getValue().stream().map(SampleSql::status).collect(Collectors.toCollection(LinkedHashSet::new));
            var sourceNumbers = entry.getValue().stream().map(SampleSql::sqlNumber).distinct().toList();
            script.append("\n-- 样例 ").append(++exampleNumber).append(" | 联合状态: ").append(statuses)
                    .append(" | 来源 SQL 编号: ").append(sourceNumbers).append('\n').append(entry.getKey());
            if (!entry.getKey().stripTrailing().endsWith(";")) script.append(';');
            script.append('\n');
        }
        Path exampleFile = file.resolveSibling("jdbc-user-query-simulated.sql");
        Files.writeString(exampleFile, script);
        System.out.print(script);
        System.out.println("模拟 SQL 清单: " + exampleFile);
        return List.copyOf(samples);
    }

    private record SampleSql(int sqlNumber, int combinationNumber, JointSqlInferenceResult.Status status,
                             SqlNodeRenderer.RenderedSql rendered) {}

    private static void appendParameter(StringBuilder report, String prefix, ParameterValueInferenceResult parameter) {
        report.append("  - ").append(prefix).append("name=").append(parameter.name())
                .append(", type=").append(parameter.type()).append(", complete=").append(parameter.complete()).append('\n');
        for (int i = 0; i < parameter.possibleValues().size(); i++) {
            var value = parameter.possibleValues().get(i);
            report.append("    候选 ").append(i + 1).append(": ").append(value.value())
                    .append(", sampled=").append(value.sampled()).append('\n');
        }
        if (!parameter.issues().isEmpty()) report.append("    诊断: ").append(parameter.issues()).append('\n');
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
