package org.u80dc.jsqlint.mybatis;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Isolated;
import org.u80dc.jsqlint.commons.compiler.MavenRemoteRepository;
import org.u80dc.jsqlint.commons.compiler.MavenRepositoryOptions;
import org.u80dc.jsqlint.commons.location.AutoCompileInputResolver;
import org.u80dc.jsqlint.commons.location.InputLocationOptions;
import org.u80dc.jsqlint.commons.location.InputMode;
import org.u80dc.jsqlint.core.AnalysisOptions;
import org.u80dc.jsqlint.core.AnalysisResult;
import org.u80dc.jsqlint.core.Analyzer;
import org.u80dc.jsqlint.core.AnalyzerConfig;
import org.u80dc.jsqlint.core.InferenceOptions;
import org.u80dc.jsqlint.core.node.SqlValue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 从父 reactor 构建 snc-dpm-biz 及其源码依赖，分析真实 Mapper 方法；以 -Djsqlint.dpm.test=true 显式启用。
 * 默认项目路径可通过 -Djsqlint.dpm.project=... 覆盖。
 * 项目构建需要其 Maven 私服；仓库地址可通过 -Djsqlint.dpm.repository=... 覆盖。
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "jsqlint.dpm.test", matches = "true")
@Isolated("Maven Embedder 使用进程共享状态")
class MySQLDetailMapperProjectAnalysisTest {
    private static final String PROJECT = "D:/workspaces/SHSNC/snc-dpm-rebuild/snc-dpm-biz";
    private static final String MAPPER = "com.shsnc.dpm.modules.database.mysql.mapper.MySQLDetailMapper";
    private static final String METHOD = "findBusynessWithNodeId";
    private static final String SIGNATURE = "<" + MAPPER + ": java.util.Map " + METHOD + "(java.util.Map)>";

    @Test
    void analyzesFindBusynessWithNodeIdFromTheBoundProject() throws Exception {
        Path project = Path.of(System.getProperty("jsqlint.dpm.project", PROJECT));
        assertTrue(Files.isRegularFile(project.resolve("pom.xml")), "目标 Maven 项目不存在：" + project);
        Path reactor = project.toAbsolutePath().normalize().getParent();
        assertNotNull(reactor);
        assertTrue(Files.isRegularFile(reactor.resolve("pom.xml")), "目标项目的父 reactor 不存在：" + reactor);

        // 私服在构建前配置，供父 POM、内部模块和插件解析使用；不会读取宿主 settings.xml。
        var repository = new MavenRemoteRepository("dpm-public", URI.create(System.getProperty(
                "jsqlint.dpm.repository", "http://maven.one/content/groups/public/")))
                .withSnapshotsEnabled(true).withMirrorOf("*");
        // 使用同一工作树的兄弟模块源码，避免单独编译 biz 时引用不匹配的已发布 API 快照。
        var inputs = new InputLocationOptions(InputMode.MAVEN_PROJECT, List.of(),
                List.of("-pl", project.getFileName().toString(), "-am"), null).withMavenRepositories(
                MavenRepositoryOptions.defaults().withRemoteRepositories(List.of(repository)));
        var config = AnalyzerConfig.defaults().withCleanupClassOutput(true)
                .withInferenceOptions(InferenceOptions.defaults().withMode(InferenceOptions.Mode.EXTRACT));
        var analyzer = new Analyzer(config, List.of(new MybatisAnalysisModule()), List.of(new AutoCompileInputResolver(inputs)));
        var bound = analyzer.bind(reactor);

        // 也可替换为 MAPPER.replace('.', '/') + "." + METHOD + ":(Ljava/util/Map;)Ljava/util/Map;"。
        AnalysisResult result = bound.analyze(AnalysisOptions.defaults(), SIGNATURE);
        System.out.println("status: " + result.status());
        result.diagnostics().forEach(issue -> System.out.println(issue.code() + ": " + issue.message()));
        assertTrue(result.status() == AnalysisResult.Status.SUCCESS || result.status() == AnalysisResult.Status.PARTIAL,
                () -> "分析未完成：" + result.diagnostics());
        assertTrue(result.calls().isEmpty(), "直接指定 Mapper 接口方法时应产生定义结果");
        assertEquals(1, result.definitions().size(), "应只提取指定方法对应的 SQL 定义：" + result.diagnostics());

        var definitionResult = result.definitions().getFirst();
        var definition = definitionResult.definition().orElseThrow();
        assertEquals(MAPPER + "." + METHOD, definition.id());
        assertEquals(SIGNATURE, definition.metadata().get("method"));
        assertTrue(definition.complete(), definition.issues().toString());

        // 指定参数只用于渲染样例；不会调用 Mapper、启动目标应用或执行数据库 SQL。
        var sample = definitionResult.sample(Map.of("nodeId", "test-node"));
        String sql = sample.sql().orElseThrow(() -> new AssertionError(sample.issues().toString()));
        assertTrue(sql.contains("snc_biz_history_my_active"));
        assertTrue(sql.contains("snc_biz_latest_my_active"));
        assertTrue(sql.contains("snc_node_attr"));
        assertFalse(sql.contains("#{nodeId}"));
        assertEquals(4, sql.chars().filter(character -> character == '?').count());
        assertEquals(4, sample.bindings().size());
        assertTrue(sample.bindings().stream().allMatch(binding -> binding.value().equals(new SqlValue.Known("test-node"))));

        String report = "project: " + project.toAbsolutePath().normalize() + "\nreactor: " + reactor + "\nmethod: " + SIGNATURE
                + "\nstatus: " + result.status() + "\ndiagnostics: " + result.diagnostics()
                + "\nsource: " + definition.location() + "\nSQL:\n" + sql
                + "\nbindings: " + sample.bindings() + "\n";
        Path reports = Path.of("target");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("mysql-detail-mapper-find-busyness-sql.txt"), report);
        System.out.println(report);
    }
}
