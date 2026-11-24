package org.u80dc.jsqlint.mybatis.plus;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.mybatis.MybatisAnalysisModule;
import org.u80dc.jsqlint.mybatis.MybatisOptions;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.DefaultRuntimeAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;

import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** 区分 BaseMapper 泛型入口、具体继承定义和实际 Wrapper 调用，并以真实框架校验 SQL。 */
class BaseMapperSelectListAnalysisTest {
    private static final String BASE_MAPPER = "com.baomidou.mybatisplus.core.mapper.BaseMapper";
    private static final String MAPPER = "sample.NodeConnConfigMapper";
    private static final String SELECT_LIST = ": java.util.List selectList(com.baomidou.mybatisplus.core.conditions.Wrapper)>";
    private static final String SQL = "SELECT id,node_id,coll_type AS type,name,val FROM snc_node_conn_config";
    private static final String SOURCE = """
            package sample;
            import com.baomidou.mybatisplus.annotation.*;
            import com.baomidou.mybatisplus.core.mapper.BaseMapper;
            import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
            import com.baomidou.mybatisplus.core.toolkit.Wrappers;
            import java.util.List;

            @TableName("snc_node_conn_config")
            class NodeConnConfig {
                @TableId(value="id", type=IdType.AUTO) Long id;
                @TableField("node_id") Long nodeId;
                @TableField("coll_type") Integer type;
                String name;
                String val;
                public Long getNodeId() { return nodeId; }
            }
            interface NodeConnConfigMapper extends BaseMapper<NodeConnConfig> {}
            public class Fixture {
                public static List<NodeConnConfig> nullWrapper(NodeConnConfigMapper mapper) {
                    return mapper.selectList(null);
                }
                public static List<NodeConnConfig> emptyWrapper(NodeConnConfigMapper mapper) {
                    return mapper.selectList(new QueryWrapper<NodeConnConfig>());
                }
                public static List<NodeConnConfig> nodeIdCondition(NodeConnConfigMapper mapper) {
                    return mapper.selectList(Wrappers.<NodeConnConfig>lambdaQuery().eq(NodeConnConfig::getNodeId, 42L));
                }
                public static LambdaQueryWrapper<NodeConnConfig> wrapperForOracle() {
                    return Wrappers.<NodeConnConfig>lambdaQuery().eq(NodeConnConfig::getNodeId, 42L);
                }
            }
            """;

    @TempDir Path temporary;

    @Test
    void directBaseMapperSelectListKeepsUnboundEntityUnknown() throws Exception {
        String signature = "<" + BASE_MAPPER + SELECT_LIST;
        var result = analyze(compile(), signature);
        writeReport("base-mapper-select-list-generic.txt", signature, result);

        assertEquals(AnalysisResult.Status.PARTIAL, result.status(), result.diagnostics().toString());
        assertTrue(result.calls().isEmpty());
        assertEquals(1, result.definitions().size());
        var definition = result.definitions().getFirst().definition().orElseThrow();
        assertEquals(BASE_MAPPER + ".selectList", definition.id());
        assertEquals("3.5.16", definition.metadata().get("frameworkVersion"));
        assertEquals(BASE_MAPPER, definition.metadata().get("mapperType"));
        assertEquals("", definition.metadata().get("entityType"));
        assertEquals("", definition.metadata().get("table"));
        assertEquals(List.of("MyBatis-Plus Mapper entity type is unresolved: " + BASE_MAPPER), definition.issues());
        assertEquals(List.of("SQL_DEFINITION"), result.diagnostics().stream().map(AnalysisDiagnostic::code).toList());
        assertFalse(result.samples().isEmpty());
        assertTrue(result.samples().stream().allMatch(sample -> sample.sql().isEmpty()));
        assertTrue(result.rawSqls().isEmpty(), "不能从项目中的 Mapper 猜测 BaseMapper<T> 的表");
    }

    @Test
    void concreteInheritedSelectListKeepsUnknownWrapperAsDynamicDefinition() throws Exception {
        String signature = "<" + MAPPER + SELECT_LIST;
        var result = analyze(compile(), signature);
        writeReport("base-mapper-select-list-definition.txt", signature, result);

        assertEquals(AnalysisResult.Status.SUCCESS, result.status(), result.diagnostics().toString());
        assertTrue(result.diagnostics().isEmpty());
        assertTrue(result.calls().isEmpty());
        assertEquals(1, result.definitions().size());
        var definition = result.definitions().getFirst().definition().orElseThrow();
        assertMapping(definition);
        assertTrue(definition.complete(), definition.issues().toString());
        assertTrue(definition.template().isDynamic());
        assertFalse(result.samples().isEmpty());
        assertTrue(result.samples().stream().allMatch(sample -> sample.sql().isEmpty()),
                "直接定义没有 Wrapper 输入，不能将未知条件当作空条件");
        assertTrue(result.rawSqls().isEmpty());
    }

    @Test
    void selectListCallsMatchFrameworkForNullEmptyAndLambdaWrappers() throws Exception {
        Path classes = compile();
        var result = analyze(classes, null);
        writeReport("base-mapper-select-list-sql.txt", "sample.Fixture 的 selectList 调用", result);

        assertEquals(AnalysisResult.Status.SUCCESS, result.status(), result.diagnostics().toString());
        assertTrue(result.diagnostics().isEmpty());
        assertTrue(result.definitions().isEmpty());
        assertEquals(3, result.calls().size());
        assertEquals(Set.of("nullWrapper", "emptyWrapper", "nodeIdCondition"), Set.copyOf(result.calls().stream()
                .map(call -> call.callSite().caller().orElseThrow().getName()).toList()));

        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            var configuration = new MybatisConfiguration();
            GlobalConfigUtils.setGlobalConfig(configuration, new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()));
            configuration.addMapper(loader.loadClass(MAPPER));
            Object lambda = loader.loadClass("sample.Fixture").getMethod("wrapperForOracle").invoke(null);
            Map<String, Object> wrappers = new HashMap<>();
            wrappers.put("nullWrapper", null);
            wrappers.put("emptyWrapper", new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>());
            wrappers.put("nodeIdCondition", lambda);

            for (var call : result.calls()) {
                assertEquals("mybatis", call.moduleId());
                assertEquals("<" + MAPPER + SELECT_LIST, call.callSite().target().toString());
                assertTrue(call.complete(), call.issues().toString());
                assertEquals(1, call.statements().size());
                assertMapping(call.statements().getFirst().definition().orElseThrow());
                assertEquals(1, call.samples().size());
                var sample = call.samples().getFirst();
                String caller = call.callSite().caller().orElseThrow().getName();
                String expected = SQL + (caller.equals("nodeIdCondition") ? " WHERE (node_id = ?)" : "");
                assertEquals(normalize(expected), normalize(sample.sql().orElseThrow()), sample.issues().toString());
                assertEquals(caller.equals("nodeIdCondition") ? List.of(new SqlValue.Known(42L)) : List.of(),
                        sample.bindings().stream().map(SqlSample.Binding::value).toList());

                var parameters = new HashMap<String, Object>();
                parameters.put("ew", wrappers.get(caller));
                var bound = configuration.getMappedStatement(MAPPER + ".selectList").getBoundSql(parameters);
                assertEquals(normalize(bound.getSql()), normalize(sample.sql().orElseThrow()));
                var bindings = bound.getParameterMappings().stream().map(mapping -> new SqlValue.Known(
                        bound.hasAdditionalParameter(mapping.getProperty()) ? bound.getAdditionalParameter(mapping.getProperty())
                                : configuration.newMetaObject(parameters).getValue(mapping.getProperty()))).toList();
                assertEquals(bindings, sample.bindings().stream().map(SqlSample.Binding::value).toList());
            }
        }
    }

    private static void assertMapping(SqlDefinition definition) {
        assertEquals(MAPPER + ".selectList", definition.id());
        assertEquals("mybatis-plus", definition.metadata().get("framework"));
        assertEquals("3.5.16", definition.metadata().get("frameworkVersion"));
        assertEquals(MAPPER, definition.metadata().get("mapperType"));
        assertEquals("sample.NodeConnConfig", definition.metadata().get("entityType"));
        assertEquals("snc_node_conn_config", definition.metadata().get("table"));
        assertEquals("select", definition.metadata().get("command"));
    }

    private Path compile() throws Exception {
        Path source = Files.writeString(temporary.resolve("Fixture.java"), SOURCE);
        Path classes = Files.createDirectories(temporary.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-parameters",
                "-classpath", System.getProperty("java.class.path"), "-d", classes.toString(), source.toString()));
        return classes;
    }

    private static AnalysisResult analyze(Path classes, String signature) throws Exception {
        String library = Path.of(BaseMapper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        try (var code = new JavaClassPathAnalysisInputLocation(classes.toString(), SourceType.Application);
             var dependency = new JavaClassPathAnalysisInputLocation(library, SourceType.Library);
             var runtime = new DefaultRuntimeAnalysisInputLocation()) {
            var analyzer = new Analyzer(List.of(new MybatisAnalysisModule(MybatisOptions.defaults(),
                    List.of(new MybatisPlusExtension()))), List.of());
            var request = new AnalysisRequest(List.of(code, dependency, runtime), List.of());
            return signature == null ? analyzer.analyze(request) : analyzer.analyze(request, signature);
        }
    }

    private static void writeReport(String name, String entry, AnalysisResult result) throws Exception {
        var report = new StringBuilder("entry: ").append(entry).append("\nstatus: ").append(result.status())
                .append("\ndiagnostics: ").append(result.diagnostics()).append('\n');
        for (var definition : result.definitions()) appendDefinition(report, definition.definition().orElseThrow());
        for (var call : result.calls()) {
            report.append("call: ").append(call.callSite()).append('\n');
            for (var statement : call.statements()) statement.definition().ifPresent(definition -> appendDefinition(report, definition));
        }
        for (var sample : result.samples()) {
            report.append("source: ").append(sample.location()).append("\nSQL:\n").append(sample.sql().orElse("<unknown>"))
                    .append("\nbindings: ").append(sample.bindings()).append("\nassessment: ").append(sample.assessment())
                    .append("\nrendering diagnostics: ").append(sample.rendered().diagnostics())
                    .append("\nissues: ").append(sample.issues()).append('\n');
        }
        Path reports = Files.createDirectories(Path.of("target"));
        Files.writeString(reports.resolve(name), report);
        System.out.println(report);
    }

    private static void appendDefinition(StringBuilder report, SqlDefinition definition) {
        report.append("definition: ").append(definition.id()).append("\nmetadata: ").append(new TreeMap<>(definition.metadata()))
                .append("\ndefinition issues: ").append(definition.issues()).append('\n');
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").replaceAll("\\s*([(),=])\\s*", "$1").strip();
    }
}
