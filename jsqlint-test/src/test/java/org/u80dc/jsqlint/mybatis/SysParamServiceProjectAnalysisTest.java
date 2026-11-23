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
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.node.SqlValue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** 分析真实 DPM Service 到 XML Mapper 的调用，不执行 Service、Mapper 或数据库 SQL。 */
@Tag("integration")
@EnabledIfSystemProperty(named = "jsqlint.dpm.test", matches = "true")
@Isolated("Maven Embedder 使用进程共享状态")
class SysParamServiceProjectAnalysisTest {
    private static final String PROJECT = "D:/workspaces/SHSNC/snc-dpm-rebuild/snc-dpm-biz";
    private static final String SERVICE = "com.shsnc.dpm.modules.database.mysql.service.impl.SysParamServiceImpl";
    private static final String MAPPER = "com.shsnc.dpm.modules.database.mysql.mapper.SysParamMapper";
    private static final String SIGNATURE = "<" + SERVICE + ": java.lang.String getCurrentVersion(java.lang.Long)>";
    private static final String SQL = "SELECT * FROM snc_node_attr a WHERE a.na_itemcode = 'VERSION' AND a.na_nodeid = ?";

    @Test
    void analyzesGetCurrentVersionFromTheBoundService() throws Exception {
        Path project = Path.of(System.getProperty("jsqlint.dpm.project", PROJECT)).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(project.resolve("pom.xml")), "目标 Maven 项目不存在：" + project);
        Path reactor = project.getParent();
        assertNotNull(reactor);
        assertTrue(Files.isRegularFile(reactor.resolve("pom.xml")), "目标项目的父 reactor 不存在：" + reactor);

        var repository = new MavenRemoteRepository("dpm-public", URI.create(System.getProperty(
                "jsqlint.dpm.repository", "http://maven.one/content/groups/public/")))
                .withSnapshotsEnabled(true).withMirrorOf("*");
        var inputs = new InputLocationOptions(InputMode.MAVEN_PROJECT, List.of(),
                List.of("-pl", project.getFileName().toString(), "-am"), null).withMavenRepositories(
                MavenRepositoryOptions.defaults().withRemoteRepositories(List.of(repository)));
        var config = AnalyzerConfig.defaults().withCleanupClassOutput(true)
                .withInferenceOptions(InferenceOptions.defaults().withMode(InferenceOptions.Mode.EXTRACT));
        // 此入口使用普通 MyBatis XML；不对目标项目的 3.3.2 Plus 依赖套用 3.5.16 生成模型。
        var analyzer = new Analyzer(config, List.of(new MybatisAnalysisModule(MybatisOptions.defaults(), List.of())),
                List.of(new AutoCompileInputResolver(inputs)));
        AnalysisResult result = analyzer.bind(reactor).analyze(AnalysisOptions.defaults(), SIGNATURE);

        var report = new StringBuilder("project: ").append(project).append("\nreactor: ").append(reactor)
                .append("\nmethod: ").append(SIGNATURE).append("\nstatus: ").append(result.status())
                .append("\ndiagnostics: ").append(result.diagnostics()).append('\n');
        for (var call : result.calls()) {
            report.append("call: ").append(call.callSite()).append("\nchain: ").append(call.callChain()).append('\n');
            for (var sample : call.samples()) {
                report.append("source: ").append(sample.definition().map(definition -> definition.location().toString()).orElse("unavailable"))
                        .append("\nSQL:\n").append(sample.sql().orElse("<unknown>"))
                        .append("\nbindings: ").append(sample.bindings()).append("\nassessment: ").append(sample.assessment())
                        .append("\nissues: ").append(sample.issues()).append('\n');
            }
        }
        Path reports = Files.createDirectories(Path.of("target"));
        Files.writeString(reports.resolve("sys-param-service-get-current-version-sql.txt"), report);
        System.out.println(report);

        assertTrue(result.status() == AnalysisResult.Status.SUCCESS || result.status() == AnalysisResult.Status.PARTIAL,
                () -> "分析未完成：" + result.diagnostics());
        assertTrue(result.definitions().isEmpty(), "业务方法入口应返回实际 Mapper 调用，不应返回独立定义");
        assertEquals(1, result.calls().size(), "应只提取 getCurrentVersion 的 Mapper 调用：" + result.diagnostics());
        var call = result.calls().getFirst();
        assertEquals("mybatis", call.moduleId());
        assertEquals(MAPPER, call.callSite().target().getDeclClassType().toString());
        assertEquals("getCurrentVersion", call.callSite().target().getName());
        assertEquals(SIGNATURE, call.callSite().caller().orElseThrow().toString());
        assertEquals(1, call.statements().size());
        var definition = call.statements().getFirst().definition().orElseThrow();
        assertEquals(MAPPER + ".getCurrentVersion", definition.id());
        assertEquals(project.resolve("src/main/resources/mapper/database/mysql/SysParamsMapper.xml").toUri(),
                definition.location().resource());
        assertTrue(definition.complete(), definition.issues().toString());
        assertFalse(call.samples().isEmpty(), "静态 SQL 必须保留样例，即使 Map 参数尚未恢复");
        for (var sample : call.samples()) {
            assertEquals(SQL, normalize(sample.sql().orElseThrow(() -> new AssertionError(sample.issues().toString()))));
            assertEquals(1, sample.bindings().size());
            assertEquals(new SqlValue.Known(1), sample.bindings().getFirst().keyValue());
            assertTrue(sample.bindings().getFirst().value() instanceof SqlValue.Unknown,
                    "EXTRACT 模式不得编造入口 nodeid 的运行时值");
            assertFalse(sample.assessment().valuesComplete());
        }

        // 单独验证 XML 参数契约；这份手工输入的样例不代表已追踪 Service 中 HashMap.put 的值。
        var rendered = definition.render(Map.of("nodeid", 42L));
        assertEquals(SQL, normalize(rendered.rendered().sql().orElseThrow()));
        assertEquals(List.of(new SqlValue.Known(42L)), rendered.bindings().stream().map(binding -> binding.value()).toList());

        System.out.println("---------------------------");
        List<SqlResult> sqlResults = result.sqlResults();
        for (SqlResult sqlResult : sqlResults) {
            List<RawSql.Variant> variants = sqlResult.rawSqls();
            for (RawSql.Variant variant : variants) {
                System.out.println(variant.text());
            }
        }
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").strip();
    }
}
