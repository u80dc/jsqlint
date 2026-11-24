package org.u80dc.jsqlint.mybatis.plus;

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
import org.u80dc.jsqlint.core.node.SqlNode;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.core.node.ValueSqlNode;
import org.u80dc.jsqlint.mybatis.MybatisAnalysisModule;
import org.u80dc.jsqlint.mybatis.MybatisOptions;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** 分析真实 DPM 的 Lambda Wrapper 调用；保留目标框架版本，不执行 Service、Mapper 或数据库 SQL。 */
@Tag("integration")
@EnabledIfSystemProperty(named = "jsqlint.dpm.test", matches = "true")
@Isolated("Maven Embedder 使用进程共享状态")
class NodeConnConfigServiceProjectAnalysisTest {
    private static final String PROJECT = "D:/workspaces/SHSNC/snc-dpm-rebuild/snc-dpm-biz";
    private static final String SERVICE = "com.shsnc.dpm.modules.devconfig.service.impl.NodeConnConfigServiceImpl";
    private static final String MAPPER = "com.shsnc.dpm.modules.devconfig.mapper.NodeConnConfigMapper";
    private static final String ENTITY = "com.shsnc.dpm.common.domain.entity.NodeConnConfig";
    private static final String SIGNATURE = "<" + SERVICE + ": java.util.List get(long)>";
    private static final String SQL = "SELECT id,node_id,coll_type AS type,name,val FROM snc_node_conn_config WHERE (node_id = ?)";
    private static final Set<String> UNRESOLVED_CALLS = Set.of(
            "<com.baomidou.mybatisplus.core.toolkit.Wrappers: com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper lambdaQuery()>",
            "<sootup.dummy.InvokeDynamic: com.baomidou.mybatisplus.core.toolkit.support.SFunction apply()>",
            "<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper: java.lang.Object eq(java.lang.Object,java.lang.Object)>");

    @Test
    void analyzesGetLongWithTheTargetsThreeXFramework() throws Exception {
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
        // 无参扩展读取实际项目配置；不以分析器运行时的 3.5.16 替换目标依赖。
        var mybatis = new MybatisAnalysisModule(MybatisOptions.defaults(), List.of(new MybatisPlusExtension()));
        var analyzer = new Analyzer(config, List.of(mybatis), List.of(new AutoCompileInputResolver(inputs)));
        AnalysisResult result = analyzer.bind(reactor).analyze(AnalysisOptions.defaults(), SIGNATURE);
        writeReport(project, reactor, result);

        assertEquals(AnalysisResult.Status.PARTIAL, result.status(), result.diagnostics().toString());
        assertTrue(result.diagnostics().stream().allMatch(diagnostic -> diagnostic.code().equals("METHOD_SCOPE_UNRESOLVED")),
                () -> "不允许编译、解析、超时或预算错误被 PARTIAL 掩盖：" + result.diagnostics());
        // 指定业务方法时，发现阶段还保留这三个未进入项目调用范围的框架/Lambda 调用。
        var scopeDiagnostics = result.diagnostics().stream()
                .filter(diagnostic -> diagnostic.code().equals("METHOD_SCOPE_UNRESOLVED")).toList();
        assertEquals(UNRESOLVED_CALLS.size(), scopeDiagnostics.size());
        assertTrue(UNRESOLVED_CALLS.stream().allMatch(target -> scopeDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().equals("Unresolved downstream call from " + SIGNATURE + ": " + target))),
                scopeDiagnostics.toString());
        assertTrue(result.definitions().isEmpty(), "业务方法入口应返回实际 Mapper 调用");
        assertEquals(1, result.calls().size(), "应只提取 get(long) 的 selectList 调用：" + result.diagnostics());
        var call = result.calls().getFirst();
        assertEquals("mybatis", call.moduleId());
        assertEquals(SIGNATURE, call.callSite().caller().orElseThrow().toString());
        assertEquals(MAPPER, call.callSite().target().getDeclClassType().toString());
        assertEquals("selectList", call.callSite().target().getName());
        assertEquals(List.of("com.baomidou.mybatisplus.core.conditions.Wrapper"),
                call.callSite().target().getParameterTypes().stream().map(Object::toString).toList());
        assertTrue(call.issues().isEmpty(), call.issues().toString());
        assertTrue(call.complete(), call.issues().toString());
        assertEquals(1, call.statements().size());

        var definition = call.statements().getFirst().definition().orElseThrow();
        assertEquals(MAPPER + ".selectList", definition.id());
        assertEquals(URI.create("java:" + MAPPER), definition.location().resource());
        assertEquals("mybatis-plus", definition.metadata().get("framework"));
        assertEquals("3.3.2", definition.metadata().get("frameworkVersion"));
        assertEquals("3.x", definition.metadata().get("frameworkCompatibility"));
        assertEquals(MAPPER, definition.metadata().get("mapperType"));
        assertEquals(ENTITY, definition.metadata().get("entityType"));
        assertEquals("snc_node_conn_config", definition.metadata().get("table"));
        assertEquals("select", definition.metadata().get("command"));
        assertTrue(definition.metadata().get("configurationSource.idType")
                .endsWith("application.properties#mybatis-plus.global-config.db-config.id-type"));
        assertTrue(definition.complete(), definition.issues().toString());

        assertFalse(call.samples().isEmpty(), "应恢复 Lambda Wrapper 条件 SQL");
        for (var sample : call.samples()) {
            assertEquals(normalize(SQL), normalize(sample.sql().orElseThrow()), sample.issues().toString());
            assertTrue(sample.assessment().sourcesComplete());
            assertTrue(sample.assessment().textComplete());
            assertEquals(1, sample.bindings().size());
            assertEquals(new SqlValue.Known(1), sample.bindings().getFirst().keyValue());
            assertTrue(sample.bindings().getFirst().value() instanceof SqlValue.Unknown,
                    "EXTRACT 模式应保留入口 nodeId 的符号绑定，不能编造运行时值");
            assertFalse(sample.assessment().valuesComplete());
        }
        assertFalse(result.rawSqls().isEmpty());
        assertTrue(result.rawSqls().stream().allMatch(sql -> normalize(sql.text()).equals(normalize(SQL))));
    }

    private static void writeReport(Path project, Path reactor, AnalysisResult result) throws Exception {
        var report = new StringBuilder("project: ").append(project).append("\nreactor: ").append(reactor)
                .append("\nmethod: ").append(SIGNATURE).append("\nstatus: ").append(result.status())
                .append("\ndiagnostics: ").append(result.diagnostics()).append('\n');
        for (var call : result.calls()) {
            report.append("call: ").append(call.callSite()).append("\nchain: ").append(call.callChain())
                    .append("\ncall issues: ").append(call.issues()).append('\n');
            for (var statement : call.statements()) {
                statement.definition().ifPresent(definition -> report.append("definition: ").append(definition.id())
                        .append("\nsource: ").append(definition.location())
                        .append("\nmetadata: ").append(new TreeMap<>(definition.metadata()))
                        .append("\ntemplate: ").append(describeNode(definition.template()))
                        .append("\ndefinition issues: ").append(definition.issues()).append('\n'));
            }
            for (var sample : call.samples()) {
                report.append("SQL:\n").append(sample.sql().orElse("<unknown>"))
                        .append("\nresidual: ").append(describeNode(sample.rendered().residual()))
                        .append("\nrendering diagnostics: ").append(sample.rendered().diagnostics())
                        .append("\nbindings: ").append(sample.bindings())
                        .append("\nassessment: ").append(sample.assessment())
                        .append("\nissues: ").append(sample.issues()).append('\n');
            }
        }
        Path reports = Files.createDirectories(Path.of("target"));
        Files.writeString(reports.resolve("node-conn-config-service-get-long-sql.txt"), report);
        System.out.println(report);
    }

    private static String describeNode(SqlNode node) {
        if (node == null) return "<none>";
        if (node instanceof ValueSqlNode value) return value.getKind() + "(" + value.getExpression() + ")";
        return node.getType().toString();
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").replaceAll("\\s*([(),=])\\s*", "$1").strip();
    }
}
