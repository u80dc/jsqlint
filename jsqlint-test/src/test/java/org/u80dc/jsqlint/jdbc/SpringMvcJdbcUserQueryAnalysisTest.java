package org.u80dc.jsqlint.jdbc;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.u80dc.jsqlint.core.AnalysisListener;
import org.u80dc.jsqlint.core.AnalysisOptions;
import org.u80dc.jsqlint.core.AnalysisResult;
import org.u80dc.jsqlint.core.Analyzer;
import org.u80dc.jsqlint.core.AnalyzerConfig;
import org.u80dc.jsqlint.core.RawSql;
import org.u80dc.jsqlint.core.spi.SqlCallSite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分析真实 Spring MVC 分层项目，打印所有 raw SQL 候选及其调用位置。
 *
 * @author u80dc
 */
@Isolated("Maven Embedder 使用进程共享状态，避免与其他编译测试并发")
class SpringMvcJdbcUserQueryAnalysisTest {
    private static final String REPOSITORY = "org.u80dc.jsqlint.samples.springmvcjdbc.repository.JdbcUserRepository";
    private static final String SELECT = "SELECT id, username, email, age, active FROM app_users";

    @Test
    @Tag("integration")
    void analyzerPrintsSqlFromSpringMvcMavenProject() throws IOException {
        Path project = sampleProject();
        String outputId = "springmvc-jdbc-user-query-test-" + UUID.randomUUID();
        Path output = Path.of(System.getProperty("java.io.tmpdir"), ".jsqlint", outputId);
        var capture = new Capture();

        // 使用项目路径入口，自动加载 commons 编译器和 JDBC 模块，无需启动 Web 应用。
        var analyzer = new Analyzer(AnalyzerConfig.defaults().withCleanupClassOutput(true));
        var result = analyzer.analyze(project, AnalysisOptions.defaults()
                .withClassOutputId(outputId).withListener(capture));
        writeReport(project, result, capture);
        String diagnostics = String.join("\n", capture.diagnostics);

        assertTrue(result.status() == AnalysisResult.Status.SUCCESS || result.status() == AnalysisResult.Status.PARTIAL,
                diagnostics);
        assertFalse(Files.exists(output), "结束时应清理本次编译输出");
        assertEquals(3, capture.callSites.size(), "一个 Statement 执行点、一个准备点、一个 PreparedStatement 执行点");
        assertTrue(capture.callSites.stream().allMatch(site -> site.caller().orElseThrow()
                .getDeclClassType().toString().equals(REPOSITORY)));
        assertEquals(Set.of("findById", "findUsers"), result.extractions().stream()
                .map(extraction -> extraction.callSite().caller().orElseThrow().getName()).collect(Collectors.toSet()));

        // 核对 SQL 结构，不用准备点/执行点及传播路径的重复候选数量代替结构覆盖率。
        Set<String> expected = new LinkedHashSet<>();
        expected.add(SELECT + " WHERE id = ${id}");
        for (String active : List.of("", " AND active = ?")) {
            for (String orderBy : List.of("id ASC", "age ASC, id ASC")) {
                // LIMIT 溯源到 Service 校正后的局部变量，而不是原始 HTTP limit。
                expected.add(SELECT + " WHERE username LIKE ? ESCAPE '!' AND age >= ?" + active
                        + " ORDER BY " + orderBy + " LIMIT ${boundedLimit}");
            }
        }
        assertEquals(expected, result.rawSqls().stream().map(RawSql.Variant::text).collect(Collectors.toSet()), diagnostics);
        assertTrue(result.rawSqls().stream().allMatch(sql -> sql.trace().isPresent()), "候选应保留传播来源");
        if (result.status() == AnalysisResult.Status.PARTIAL) {
            assertFalse(capture.diagnostics.isEmpty(), "未完成的分析应说明原因");
        }
    }

    /** 详细报告不去重；精简清单只按原始文本去重，不改变 AnalysisResult。 */
    private static void writeReport(Path project, AnalysisResult result, Capture capture) throws IOException {
        var texts = result.rawSqls().stream().map(RawSql.Variant::text)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        var report = new StringBuilder("springmvc-jdbc-user-query SQL 分析报告\n")
                .append("分析状态: ").append(result.status())
                .append("\nSQL 调用点: ").append(capture.callSites.size())
                .append("\n提取结果: ").append(result.extractions().size())
                .append("\nSQL 候选记录: ").append(result.rawSqls().size())
                .append("\n不同 SQL 文本: ").append(texts.size())
                .append("\n联合状态统计: ").append(result.rawSqls().stream()
                        .collect(Collectors.groupingBy(sql -> sql.jointInference()
                                .map(joint -> joint.status().name()).orElse("NOT_SOLVED"), Collectors.counting())))
                .append("\n说明: 候选保留准备点/执行点及不同路径，不代表数据库实际执行次数。\n")
                .append("${name} 为拼接变量占位符，? 为 JDBC 绑定参数；此清单未执行 SQL。\n");
        int index = 0;
        for (var extraction : result.extractions()) {
            report.append("\n所属方法: ").append(extraction.callSite().caller().orElseThrow())
                    .append("\n调用点: ").append(extraction.callSite().target())
                    .append("\n位置: ").append(extraction.callSite().location())
                    .append("\n入口调用链: ").append(extraction.callChain()).append('\n');
            if (!extraction.issues().isEmpty()) report.append("提取诊断: ").append(extraction.issues()).append('\n');
            for (var statement : extraction.statements()) {
                for (var sql : statement.rawSql().variants()) {
                    report.append("[SQL ").append(++index).append("] ").append(sql.text()).append('\n');
                    report.append("拼接参数: ").append(sql.parameters().stream()
                            .map(parameter -> parameter.name() + ":" + parameter.type()).toList()).append('\n');
                    report.append("绑定参数: ").append(sql.bindings().stream()
                            .map(binding -> binding.key() + "=" + binding.inference().name() + ":" + binding.inference().type())
                            .toList()).append('\n');
                    sql.jointInference().ifPresent(joint -> report.append("联合状态: ").append(joint.status())
                            .append("，组合数: ").append(joint.combinations().size())
                            .append("，诊断: ").append(joint.issues()).append('\n'));
                }
                if (!statement.issues().isEmpty()) report.append("语句诊断: ").append(statement.issues()).append('\n');
                if (!statement.rawSql().issues().isEmpty()) report.append("raw SQL 诊断: ")
                        .append(statement.rawSql().issues()).append('\n');
            }
        }
        report.append("\n分析诊断:\n");
        capture.diagnostics.forEach(message -> report.append(message).append('\n'));
        Path target = project.getParent().getParent().resolve("jsqlint-test/target");
        Files.createDirectories(target);
        Path reportFile = target.resolve("springmvc-jdbc-user-query-sql-report.txt");
        Files.writeString(reportFile, report);

        var script = new StringBuilder("-- springmvc-jdbc-user-query raw SQL（按文本去重）\n")
                .append("-- 分析状态: ").append(result.status())
                .append("；联合路径状态及诊断见详细报告，候选不等于已证明可执行的 SQL。\n");
        int number = 0;
        for (String sql : texts) script.append("\n-- SQL ").append(++number).append('\n').append(sql).append(";\n");
        Path sqlFile = target.resolve("springmvc-jdbc-user-query-raw.sql");
        Files.writeString(sqlFile, script);
        System.out.print(report);
        System.out.print(script);
        System.out.println("完整报告: " + reportFile);
        System.out.println("SQL 清单: " + sqlFile);
    }

    private static Path sampleProject() {
        for (Path directory = Path.of("").toAbsolutePath().normalize(); directory != null; directory = directory.getParent()) {
            Path project = directory.resolve("shared-test-resources/springmvc-jdbc-user-query");
            if (Files.isRegularFile(project.resolve("pom.xml"))) return project;
        }
        throw new AssertionError("Cannot locate shared-test-resources/springmvc-jdbc-user-query");
    }

    private static final class Capture implements AnalysisListener {
        private final List<SqlCallSite> callSites = new ArrayList<>();
        private final List<String> diagnostics = new ArrayList<>();

        @Override
        public void onSqlCallSite(String moduleId, SqlCallSite site) { callSites.add(site); }

        @Override
        public void onDiagnostic(String code, String message) { diagnostics.add(code + ": " + message); }
    }
}
