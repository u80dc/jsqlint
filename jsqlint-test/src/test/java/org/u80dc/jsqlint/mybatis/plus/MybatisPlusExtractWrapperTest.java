package org.u80dc.jsqlint.mybatis.plus;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.core.resource.PathResourceSource;
import org.u80dc.jsqlint.mybatis.MybatisAnalysisModule;
import org.u80dc.jsqlint.mybatis.MybatisOptions;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.DefaultRuntimeAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;

import javax.tools.ToolProvider;
import java.io.ByteArrayInputStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 列名属于已恢复的SQL结构，入口参数在EXTRACT中仍是未知值；真实框架只用于独立SQL oracle。 */
class MybatisPlusExtractWrapperTest {
    private static final String MAPPER = "sample.UserMapper";
    private static final String SQL = "SELECT id,node_id,name FROM users WHERE (node_id = ?)";
    @TempDir Path temporary;

    @Test void extractLambdaColumnProducesSqlAndUnknownEntryBinding() throws Exception {
        Path classes = compile();
        var result = analyze(classes, "lambda", InferenceOptions.Mode.EXTRACT, null);
        var sample = assertExtracted(result, SQL, 1);
        var inference = result.calls().getFirst().statements().getFirst().templateInference().orElseThrow();
        assertTrue(inference.constants().containsValue("node_id"));
        assertTrue(inference.sources().values().stream().anyMatch(trace -> trace.root().detail().startsWith("Lambda column:")));
        assertTrue(inference.parameters().values().stream().allMatch(parameter -> parameter.possibleValues().isEmpty()));
        // 42仅用于框架SQL结构对照，不输入分析器，也不把它当作EXTRACT已恢复的值。
        assertEquals(normalize(oracle(classes, 42L)), normalize(sample.sql().orElseThrow()));
    }

    @Test void extractLiteralColumnDoesNotRequireValueInference() throws Exception {
        var result = analyze(compile(), "literal", InferenceOptions.Mode.EXTRACT, null);
        assertExtracted(result, SQL, 1);
        assertTrue(result.calls().getFirst().statements().getFirst().templateInference().orElseThrow().constants().containsValue("node_id"));
    }

    @Test void inferKeepsJavaColumnTraceAndInferredColumnValue() throws Exception {
        Path classes = compile();
        for (String method : List.of("lambda", "literal")) {
            var result = analyze(classes, method, InferenceOptions.Mode.JOINT, null);
            var call = result.calls().getFirst();
            var inference = call.statements().getFirst().templateInference().orElseThrow();
            String alias = inference.constants().entrySet().stream().filter(entry -> "node_id".equals(entry.getValue()))
                    .map(Map.Entry::getKey).findFirst().orElseThrow();
            String key = inference.aliases().get(alias);
            assertNotNull(key);
            assertTrue(inference.sources().get(key).complete());
            assertTrue(inference.parameters().get(key).possibleValues().stream()
                    .anyMatch(value -> value.value().equals(new SqlValue.Known("node_id"))));
            var sample = call.samples().getFirst();
            assertEquals(normalize(SQL), normalize(sample.sql().orElseThrow()), sample.issues().toString());
            var value = assertInstanceOf(SqlValue.Known.class, sample.bindings().getFirst().value());
            assertEquals(normalize(oracle(classes, ((Number)value.value()).longValue())), normalize(sample.sql().orElseThrow()));
        }
    }

    @Test void explicitXmlStructuralSlotsDoNotShadowBusinessAliasesOrBindNames() throws Exception {
        Path classes = compile();
        Path resources = Files.createDirectories(temporary.resolve("xml"));
        Files.writeString(resources.resolve("mapper.xml"), "<mapper namespace='" + MAPPER + "'><select id='custom'>"
                + "<bind name='w1' value=\"'kept'\"/>SELECT #{w0} AS marker,id,node_id,name FROM explicit_users ${ew.customSqlSegment}"
                + "</select></mapper>");
        var result = analyze(classes, "xml", InferenceOptions.Mode.EXTRACT, resources);
        assertExtracted(result, "SELECT ? AS marker,id,node_id,name FROM explicit_users WHERE (node_id = ?)", 2);
        var inference = result.calls().getFirst().statements().getFirst().templateInference().orElseThrow();
        assertTrue(inference.aliases().containsKey("w0"));
        assertFalse(inference.constants().containsKey("w0"));
        assertFalse(inference.constants().containsKey("w1"));
        assertFalse(inference.renderAliases().containsKey("w0"));
        assertFalse(inference.renderAliases().containsKey("w1"));
        var environment = inference.environment(Map.of());
        String parameterMap = inference.parameterMapName().orElseThrow();
        var business = (Map<?,?>)environment.get(parameterMap);
        assertTrue(business.containsKey("w0"));
        for (String alias : inference.constants().keySet())
            if (inference.renderAliases().containsKey(alias)) assertFalse(business.containsKey(alias));
    }

    @Test void extractDoesNotInventAnUnknownColumnName() throws Exception {
        var result = analyze(compile(), "unknownColumn", InferenceOptions.Mode.EXTRACT, null);
        assertEquals(1, result.calls().size());
        assertTrue(result.calls().getFirst().samples().stream().allMatch(sample -> sample.sql().isEmpty()));
        assertTrue(result.calls().getFirst().statements().getFirst().templateInference().orElseThrow().constants().isEmpty());
    }

    @Test void inferXmlKeepsStructuralColumnTraceOutsideBusinessParameters() throws Exception {
        Path classes = compile();
        Path resources = Files.createDirectories(temporary.resolve("xml-infer"));
        Files.writeString(resources.resolve("mapper.xml"), "<mapper namespace='" + MAPPER + "'><select id='custom'>"
                + "SELECT #{w0} AS marker,id,node_id,name FROM explicit_users ${ew.customSqlSegment}</select></mapper>");
        var result = analyze(classes, "xml", InferenceOptions.Mode.JOINT, resources);
        var call = result.calls().getFirst();
        var inference = call.statements().getFirst().templateInference().orElseThrow();
        String alias = inference.constants().entrySet().stream().filter(entry -> "node_id".equals(entry.getValue()))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
        String key = inference.renderAliases().get(alias);
        assertNotNull(key);
        assertTrue(inference.sources().get(key).root().detail().startsWith("Lambda column:"));
        assertTrue(inference.parameters().get(key).possibleValues().stream()
                .anyMatch(value -> value.value().equals(new SqlValue.Known("node_id"))));
        assertFalse(inference.aliases().containsKey(alias));
        var sample = call.samples().getFirst();
        assertEquals(normalize("SELECT ? AS marker,id,node_id,name FROM explicit_users WHERE (node_id = ?)"),
                normalize(sample.sql().orElseThrow()), sample.issues().toString());
        assertTrue(sample.bindings().stream().allMatch(binding -> binding.value() instanceof SqlValue.Known));
    }

    @ParameterizedTest
    @EnumSource(InferenceOptions.Mode.class)
    void foreachItemAndIndexKeepTheirXmlScopeWithoutShadowingWrapperSlots(InferenceOptions.Mode mode) throws Exception {
        Path classes = compile();
        Path resources = Files.createDirectories(temporary.resolve("xml-foreach"));
        String xml = "<mapper namespace='" + MAPPER + "'><select id='custom'>"
                + "<foreach collection='{13}' item='w1' index='w2'>"
                + "SELECT ${w1} AS loop_value,${w2} AS loop_index,#{w0} AS marker,id,node_id,name FROM explicit_users "
                + "${ew.customSqlSegment}</foreach></select></mapper>";
        Files.writeString(resources.resolve("mapper.xml"), xml);
        var result = analyze(classes, "xml", mode, resources);
        assertEquals(1, result.calls().size(), result.diagnostics().toString());
        var call = result.calls().getFirst();
        assertEquals(1, call.samples().size());
        assertTrue(call.statements().getFirst().definition().orElseThrow().complete());
        var sample = call.samples().getFirst();
        var inference = call.statements().getFirst().templateInference().orElseThrow();
        for (String local : List.of("w1", "w2")) {
            assertFalse(inference.constants().containsKey(local));
            assertFalse(inference.renderAliases().containsKey(local));
        }
        // 未选择联合模型时，42仅用于框架SQL结构对照，不向分析器提供该入口值。
        long runtimeValue = mode == InferenceOptions.Mode.JOINT
                ? ((Number) assertInstanceOf(SqlValue.Known.class, sample.bindings().getFirst().value()).value()).longValue() : 42L;
        var expected = xmlOracle(classes, xml, runtimeValue);
        assertEquals(2, expected.bindings().size(), "Oracle必须保留业务标记和Wrapper条件的两个绑定");
        assertEquals(normalize(expected.sql()), normalize(sample.sql().orElseThrow()), sample.issues().toString());
        assertEquals(expected.bindings().size(), sample.bindings().size());
        if (mode == InferenceOptions.Mode.JOINT) {
            assertEquals(expected.bindings(), sample.bindings().stream().map(SqlSample.Binding::value).toList());
            assertTrue(sample.assessment().valuesComplete());
        } else {
            assertExtracted(result, expected.sql(), expected.bindings().size());
            if (mode == InferenceOptions.Mode.EXTRACT) {
                assertTrue(inference.parameters().values().stream().allMatch(parameter -> parameter.possibleValues().isEmpty()));
            } else {
                // PARAMETERS保存独立候选值，JOINT才选择用于渲染的参数组合。
                assertFalse(inference.parameters().get(inference.aliases().get("w0")).possibleValues().isEmpty());
            }
        }
    }

    private static SqlSample assertExtracted(AnalysisResult result, String expected, int bindings) {
        assertTrue(result.status() == AnalysisResult.Status.SUCCESS || result.status() == AnalysisResult.Status.PARTIAL, result.diagnostics().toString());
        assertEquals(1, result.calls().size(), result.diagnostics().toString());
        var call = result.calls().getFirst();
        assertTrue(call.statements().getFirst().definition().orElseThrow().complete());
        assertEquals(1, call.samples().size());
        var sample = call.samples().getFirst();
        assertEquals(normalize(expected), normalize(sample.sql().orElseThrow()), sample.issues().toString());
        assertEquals(bindings, sample.bindings().size());
        assertTrue(sample.bindings().stream().allMatch(binding -> binding.value() instanceof SqlValue.Unknown));
        assertTrue(sample.assessment().textComplete());assertFalse(sample.assessment().valuesComplete());
        assertTrue(sample.bindings().stream().allMatch(binding -> binding.trace().isPresent()));
        return sample;
    }

    private Path compile() throws Exception {
        Path source = Files.writeString(temporary.resolve("Fixture.java"), """
                package sample;
                import com.baomidou.mybatisplus.annotation.*;
                import com.baomidou.mybatisplus.core.mapper.BaseMapper;
                import com.baomidou.mybatisplus.core.conditions.Wrapper;
                import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
                import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
                import com.baomidou.mybatisplus.core.toolkit.Wrappers;
                import java.util.List;
                @TableName("users") class User {
                    @TableId(type=IdType.INPUT) Long id; @TableField("node_id") Long nodeId; String name;
                    public Long getNodeId() { return nodeId; }
                }
                interface UserMapper extends BaseMapper<User> {
                    List<User> custom(@org.apache.ibatis.annotations.Param("ew") Wrapper<User> wrapper,
                                      @org.apache.ibatis.annotations.Param("w0") long marker);
                }
                public class Fixture {
                    public static List<User> lambda(UserMapper mapper, long nodeId) {
                        return mapper.selectList(Wrappers.<User>lambdaQuery().eq(User::getNodeId,nodeId));
                    }
                    public static List<User> literal(UserMapper mapper, long nodeId) {
                        String column="node_id"; return mapper.selectList(new QueryWrapper<User>().eq(column,nodeId));
                    }
                    public static List<User> xml(UserMapper mapper, long nodeId) {
                        return mapper.custom(Wrappers.<User>lambdaQuery().eq(User::getNodeId,nodeId),nodeId);
                    }
                    public static List<User> unknownColumn(UserMapper mapper, String column, long nodeId) {
                        return mapper.selectList(new QueryWrapper<User>().eq(column,nodeId));
                    }
                    public static LambdaQueryWrapper<User> oracle(long nodeId) {
                        return Wrappers.<User>lambdaQuery().eq(User::getNodeId,nodeId);
                    }
                }
                """);
        Path classes = Files.createDirectories(temporary.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-parameters",
                "-classpath", System.getProperty("java.class.path"), "-d", classes.toString(), source.toString()));
        return classes;
    }

    private static AnalysisResult analyze(Path classes, String method, InferenceOptions.Mode mode, Path resources) throws Exception {
        String library = Path.of(BaseMapper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        try (var code = new JavaClassPathAnalysisInputLocation(classes.toString(), SourceType.Application);
             var dependency = new JavaClassPathAnalysisInputLocation(library, SourceType.Library);
             var runtime = new DefaultRuntimeAnalysisInputLocation()) {
            var config = AnalyzerConfig.defaults().withInferenceOptions(InferenceOptions.defaults().withMode(mode).withRandomSeed(7L));
            var analyzer = new Analyzer(config, List.of(new MybatisAnalysisModule(MybatisOptions.defaults(), List.of(new MybatisPlusExtension()))), List.of());
            var request = new AnalysisRequest(List.of(code, dependency, runtime), resources == null ? List.of() : List.of(new PathResourceSource(resources)));
            return analyzer.analyze(request, "<sample.Fixture: java.util.List " + method + "(sample.UserMapper,"
                    + (method.equals("unknownColumn")?"java.lang.String,":"") + "long)>");
        }
    }

    private String oracle(Path classes, long value) throws Exception {
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            var configuration = new MybatisConfiguration();
            GlobalConfigUtils.setGlobalConfig(configuration, new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()));
            configuration.addMapper(loader.loadClass(MAPPER));
            Object wrapper = loader.loadClass("sample.Fixture").getMethod("oracle", long.class).invoke(null, value);
            return configuration.getMappedStatement(MAPPER + ".selectList").getBoundSql(Map.of("ew",wrapper)).getSql();
        }
    }

    private record OracleStatement(String sql, List<SqlValue.Known> bindings) {}

    private OracleStatement xmlOracle(Path classes, String xml, long value) throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            var configuration = new MybatisConfiguration();
            GlobalConfigUtils.setGlobalConfig(configuration, new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()));
            String document = "<!DOCTYPE mapper PUBLIC '-//mybatis.org//DTD Mapper 3.0//EN' 'https://mybatis.org/dtd/mybatis-3-mapper.dtd'>" + xml;
            try (var input = new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8))) {
                new org.apache.ibatis.builder.xml.XMLMapperBuilder(input, configuration, "foreach-mapper.xml", configuration.getSqlFragments()).parse();
            }
            Class<?> mapper = loader.loadClass(MAPPER);
            if (!configuration.hasMapper(mapper)) configuration.addMapper(mapper);
            Object wrapper = loader.loadClass("sample.Fixture").getMethod("oracle", long.class).invoke(null, value);
            var parameters = Map.of("ew", wrapper, "w0", value);
            var bound = configuration.getMappedStatement(MAPPER + ".custom").getBoundSql(parameters);
            var bindings = bound.getParameterMappings().stream().map(mapping -> new SqlValue.Known(
                    bound.hasAdditionalParameter(mapping.getProperty()) ? bound.getAdditionalParameter(mapping.getProperty())
                            : configuration.newMetaObject(parameters).getValue(mapping.getProperty()))).toList();
            return new OracleStatement(bound.getSql(), bindings);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }
    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").replaceAll("\\s*([(),=])\\s*", "$1").strip();
    }
}
