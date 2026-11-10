package org.u80dc.jsqlint.mybatis;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Isolated;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.node.SqlValue;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 从真实 Maven 目标项目验证发现、自动编译和关闭输入后的 MyBatis 样例快照。 @author u80dc */
@Isolated("Maven Embedder 使用进程共享状态")
class MybatisUserQueryAnalysisTest {
    private static final String MAPPER = "org.u80dc.jsqlint.samples.mybatis.UserMapper";

    @Test @Tag("integration")
    void analyzesXmlAndAnnotationDefinitionsAndScalarCallsFromAProjectDirectory() throws Exception {
        Path project = sampleProject();
        String outputId = "mybatis-user-query-test-" + UUID.randomUUID();
        Path output = Path.of(System.getProperty("java.io.tmpdir"), ".jsqlint", outputId);
        var result = new Analyzer(AnalyzerConfig.defaults().withCleanupClassOutput(true)).analyze(project,
                AnalysisOptions.defaults().withClassOutputId(outputId));
        assertEquals(AnalysisResult.Status.PARTIAL, result.status(), result.diagnostics().toString());
        assertFalse(Files.exists(output), "输入关闭后清理本次编译输出");
        assertTrue(result.diagnostics().stream().allMatch(diagnostic -> diagnostic.code().equals("JOINT_ANALYSIS_INCOMPLETE")), result.diagnostics().toString());
        var definitions = result.sqlResults().stream().filter(SqlTemplateResult.class::isInstance).map(SqlTemplateResult.class::cast).toList();
        assertEquals(8, definitions.size());
        assertEquals(Set.of("createTable", "insert", "find", "rename", "search", "optional", "delete", "unusedCount"), definitions.stream()
                .map(definition -> definition.definition().orElseThrow().id().substring(MAPPER.length() + 1)).collect(java.util.stream.Collectors.toSet()));
        var calls = result.extractions().stream().filter(value -> value.moduleId().equals("mybatis")).toList();
        assertEquals(7, calls.size());
        assertTrue(calls.stream().allMatch(value -> value.callSite().target().getDeclClassType().toString().equals(MAPPER)));
        var samples = calls.stream().flatMap(value -> value.samples().stream()).toList();
        assertFalse(samples.isEmpty());
        assertTrue(samples.stream().allMatch(sample -> sample.definition().isPresent() && sample.sql().isPresent()), samples.toString());
        assertTrue(samples.stream().allMatch(sample -> sample.bindings().stream().allMatch(binding -> binding.value() instanceof SqlValue.Known)));
        var inserted = samples.stream().filter(sample -> ((SqlExtraction) sample.source()).callSite().target().getName().equals("insert")).findFirst().orElseThrow();
        assertEquals(List.of(new SqlValue.Known(7L), new SqlValue.Known("O'Neil")), inserted.bindings().stream().map(SqlSample.Binding::value).toList());
        var searched = samples.stream().filter(sample -> ((SqlExtraction) sample.source()).callSite().target().getName().equals("search")).findFirst().orElseThrow();
        assertEquals("SELECT username FROM app_users WHERE username=?", normalize(searched.sql().orElseThrow()));
        assertEquals(new SqlValue.Known("Alice"), searched.bindings().getFirst().value());
        var optional = samples.stream().filter(sample -> ((SqlExtraction) sample.source()).callSite().target().getName().equals("optional")).findFirst().orElseThrow();
        assertEquals(new SqlValue.Known("%Ali%"), optional.bindings().getFirst().value());
        assertEquals(Optional.of(false), optional.assessment().pathCoverageComplete());
        assertTrue(calls.stream().filter(call -> !call.callSite().target().getName().equals("createTable"))
                .allMatch(call -> call.callChain().stream().anyMatch(site -> site.target().getName().equals("exercise"))));
        assertEquals("SELECT COUNT(*) FROM app_users", definitions.stream().filter(value -> value.definition().orElseThrow().id().endsWith(".unusedCount"))
                .findFirst().orElseThrow().samples().getFirst().sql().orElseThrow());
        var report = new StringBuilder("MyBatis SQL analysis: " + result.status() + "\n");
        for (var sample : result.samples()) report.append(sample.definition().map(SqlDefinition::id).orElse("<unknown>"))
                .append("\nsource: ").append(sample.location()).append("\nSQL: ").append(sample.sql().orElse("<unresolved>"))
                .append("\nbindings: ").append(sample.bindings()).append("\nfeasibility: ").append(sample.feasibility())
                .append("\nassessment: ").append(sample.assessment()).append("\nissues: ").append(sample.issues()).append("\n\n");
        Path reports = Path.of("target"); Files.createDirectories(reports);
        Files.writeString(reports.resolve("mybatis-user-query-sql-report.txt"), report);
    }
    private static Path sampleProject() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null) {
            Path sample = root.resolve("shared-test-resources/mybatis-user-query");
            if (Files.isRegularFile(sample.resolve("pom.xml"))) return sample;
            root = root.getParent();
        }
        throw new IllegalStateException("MyBatis sample project is unavailable");
    }
    private static String normalize(String sql) { return sql.replaceAll("\\s+", " ").strip(); }
}
