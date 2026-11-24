package org.u80dc.jsqlint.mybatis.plus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.mybatis.*;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.*;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 通过真实默认注入器对照实体写入 SQL、参数顺序和值。 */
class MybatisPlusCrudTest {
    @TempDir Path temporary;

    @Test void insertStrategiesAndNullMatchFramework() throws Exception {
        verify("insert", "@TableId(type=IdType.INPUT) String id; String name; @TableField(insertStrategy=FieldStrategy.ALWAYS) String nullable; @TableField(insertStrategy=FieldStrategy.NOT_EMPTY) String empty; @TableField(insertStrategy=FieldStrategy.NEVER) String ignored; int count;",
                "u.id=\"one\";u.name=\"Alice\";u.empty=\"\";u.ignored=\"hidden\";u.count=3;", true);
    }
    @Test void autoIncrementWithAndWithoutExplicitIdMatchesFramework() throws Exception {
        for (String id : List.of("", "u.id=7L;")) verify("insert", "@TableId(type=IdType.AUTO) Long id; String name;", id+"u.name=\"Alice\";", true);
    }
    @Test void updateStrategiesExpressionsAndLogicDeleteMatchFramework() throws Exception {
        verify("updateById", "String id; String name; @TableField(updateStrategy=FieldStrategy.ALWAYS) String nullable; @TableField(updateStrategy=FieldStrategy.NOT_EMPTY) String empty; @TableField(updateStrategy=FieldStrategy.NEVER) String ignored; @TableField(update=\"%s+1\") int count; @TableLogic Integer deleted;",
                "u.id=\"one\";u.name=\"Alice\";u.empty=\"\";u.ignored=\"hidden\";u.count=3;u.deleted=1;", true);
    }
    @Test void generatedPrimaryKeyRemainsUnknown() throws Exception {
        var result = verify("insert", "Long id; String name;", "u.name=\"Alice\";", false);
        assertEquals(AnalysisResult.Status.PARTIAL, result.status());
        assertTrue(result.calls().getFirst().samples().stream().flatMap(sample->sample.bindings().stream())
                .anyMatch(binding -> !(binding.value() instanceof SqlValue.Known)));
    }
    @Test void emptyUuidRemainsUnknown() throws Exception {
        var result=verify("insert", "@TableId(type=IdType.ASSIGN_UUID) String id; String name;", "u.id=\"\";u.name=\"Alice\";", false);
        assertTrue(result.calls().getFirst().samples().stream().flatMap(sample->sample.bindings().stream())
                .anyMatch(binding->!(binding.value() instanceof SqlValue.Known)));
    }
    @Test void sharedCrudProjectKeepsFourCallsAndTheirBindings() throws Exception {
        Path project=Path.of("../shared-test-resources/mybatis-plus-crud");
        Path classes=Files.createDirectories(temporary.resolve("sample-classes"));
        var arguments=new ArrayList<>(List.of("-proc:none","-classpath",System.getProperty("java.class.path"),"-d",classes.toString()));
        try(var files=Files.walk(project.resolve("src/main/java"))) { files.filter(path->path.toString().endsWith(".java")).forEach(path->arguments.add(path.toString())); }
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,arguments.toArray(String[]::new)));
        String library=Path.of(com.baomidou.mybatisplus.core.mapper.BaseMapper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        try(var code=new JavaClassPathAnalysisInputLocation(classes.toString(),SourceType.Application);
            var dependency=new JavaClassPathAnalysisInputLocation(library,SourceType.Library);
            var runtime=new DefaultRuntimeAnalysisInputLocation()) {
            var result=new Analyzer(List.of(new MybatisAnalysisModule(MybatisOptions.defaults(),List.of(new MybatisPlusExtension()))),List.of())
                    .analyze(new AnalysisRequest(List.of(code,dependency,runtime),List.of()),"<example.UserService: void createAndUpdate(example.UserMapper)>");
            assertEquals(4,result.calls().size(),result.diagnostics().toString());
            assertTrue(result.calls().stream().allMatch(call->!call.samples().isEmpty()));
            var sqls=result.calls().stream().flatMap(call->call.samples().stream()).map(sample->sample.sql().orElseThrow()).toList();
            assertTrue(sqls.stream().anyMatch(sql->sql.startsWith("INSERT")));
            assertEquals(2,sqls.stream().filter(sql->sql.startsWith("UPDATE")).count());
            assertTrue(sqls.stream().anyMatch(sql->sql.startsWith("SELECT")));
            assertTrue(result.calls().stream().flatMap(call->call.samples().stream()).flatMap(sample->sample.bindings().stream())
                    .allMatch(binding->binding.value() instanceof SqlValue.Known));
        }
    }
    @Test void insertWithoutPrimaryKeyMatchesFramework() throws Exception {
        verify("insert", "String name; int count;", "u.name=\"Alice\";u.count=2;", true);
    }
    @Test void automaticFillIsExplicitlyIncomplete() throws Exception {
        var result = verify("insert", "@TableId(type=IdType.INPUT) String id; @TableField(fill=FieldFill.INSERT) String name;", "u.id=\"one\";", false);
        assertEquals(AnalysisResult.Status.PARTIAL, result.status());
        assertTrue(result.diagnostics().stream().anyMatch(issue->issue.toString().contains("fill")));
    }
    @Test void globalStrategiesAndAnnotationOverridesMatchFramework() throws Exception {
        var options=MybatisPlusOptions.defaults().withIdType("AUTO").withFieldStrategies("ALWAYS","NEVER").withTablePrefix("app_");
        verify("insert", "Long id; String name; @TableField(insertStrategy=FieldStrategy.NEVER) String ignored;", "u.ignored=\"hidden\";", true, options);
        verify("updateById", "Long id; String ignored; @TableField(updateStrategy=FieldStrategy.ALWAYS) String name;", "u.id=7L;", true, options);
    }
    @Test void globalNoneKeepsNullIdWithoutInventingGeneratedValue() throws Exception {
        verify("insert", "Long id; String name;", "u.name=\"Alice\";", true, MybatisPlusOptions.defaults().withIdType("NONE"));
    }
    @Test void legacyGlobalStrategyAliasesUseEquivalentFrameworkRules() throws Exception {
        verify("insert", "String id; String name;", "u.id=\"one\";", true,
                MybatisPlusOptions.defaults().withIdType("id_worker_str").withFieldStrategies("ignored","default"));
    }
    @Test void bindingOptionsAndExplicitAssignedIdMatchFramework() throws Exception {
        verify("insert", "@TableId(type=IdType.ASSIGN_UUID) String id; @TableField(jdbcType=org.apache.ibatis.type.JdbcType.VARCHAR) String name;", "u.id=\"explicit\";u.name=\"Alice\";", true);
    }
    @Test void primitiveNeverAndNullUpdateMatchFramework() throws Exception {
        verify("updateById", "String id; @TableField(updateStrategy=FieldStrategy.NEVER) int ignored; @TableField(updateStrategy=FieldStrategy.ALWAYS) String name;", "u.id=\"one\";u.ignored=9;", true);
    }

    private AnalysisResult verify(String operation, String fields, String initialize, boolean compare) throws Exception {
        return verify(operation,fields,initialize,compare,MybatisPlusOptions.defaults());
    }
    private AnalysisResult verify(String operation, String fields, String initialize, boolean compare, MybatisPlusOptions options) throws Exception {
        String source = "package sample; import com.baomidou.mybatisplus.core.mapper.BaseMapper; import com.baomidou.mybatisplus.annotation.*;"
                + "@TableName(\"users\") class User {"+fields+"} interface UserMapper extends BaseMapper<User> {}"
                + "public class Fixture { public static User value(){User u=new User();"+initialize+"return u;}"
                + "public static void entry(UserMapper mapper){User u=new User();"+initialize+"mapper."+operation+"(u);} }";
        Path root=Files.createTempDirectory(temporary,"fixture-"); Path java=Files.writeString(root.resolve("Fixture.java"),source);
        Path classes=Files.createDirectories(root.resolve("classes"));
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-parameters","-classpath",System.getProperty("java.class.path"),"-d",classes.toString(),java.toString()));
        String library=Path.of(com.baomidou.mybatisplus.core.mapper.BaseMapper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        AnalysisResult result;
        try(var code=new JavaClassPathAnalysisInputLocation(classes.toString(),SourceType.Application);
            var dependency=new JavaClassPathAnalysisInputLocation(library,SourceType.Library);
            var runtime=new DefaultRuntimeAnalysisInputLocation()) {
            var analyzer=new Analyzer(List.of(new MybatisAnalysisModule(MybatisOptions.defaults(),List.of(new MybatisPlusExtension(options)))),List.of());
            result=analyzer.analyze(new AnalysisRequest(List.of(code,dependency,runtime),List.of()),"<sample.Fixture: void entry(sample.UserMapper)>");
        }
        assertFalse(result.calls().isEmpty(),result.diagnostics().toString());
        if (!compare) return result;
        assertTrue(result.diagnostics().stream().allMatch(issue->issue.code().equals("JOINT_ANALYSIS_INCOMPLETE")
                && issue.message().contains("Template control conditions")), result.diagnostics().toString());
        try(var loader=new java.net.URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},getClass().getClassLoader())) {
            var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();
            var db=new com.baomidou.mybatisplus.core.config.GlobalConfig.DbConfig()
                    .setIdType(com.baomidou.mybatisplus.annotation.IdType.valueOf(options.idType()))
                    .setInsertStrategy(com.baomidou.mybatisplus.annotation.FieldStrategy.valueOf(options.insertStrategy()))
                    .setUpdateStrategy(com.baomidou.mybatisplus.annotation.FieldStrategy.valueOf(options.updateStrategy()))
                    .setTablePrefix(options.tablePrefix());
            com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(config,new com.baomidou.mybatisplus.core.config.GlobalConfig().setDbConfig(db));
            config.addMapper(loader.loadClass("sample.UserMapper"));
            Object value=loader.loadClass("sample.Fixture").getMethod("value").invoke(null);
            Object parameter=operation.equals("updateById")?Map.of("et",value):value;
            var bound=config.getMappedStatement("sample.UserMapper."+operation).getBoundSql(parameter);
            var actualValues=bound.getParameterMappings().stream().map(mapping->new SqlValue.Known(config.newMetaObject(parameter).getValue(mapping.getProperty()))).toList();
            var sample=result.calls().getFirst().samples().getFirst();
            assertEquals(normalize(bound.getSql()),normalize(sample.sql().orElseThrow()));
            assertEquals(actualValues,sample.bindings().stream().map(SqlSample.Binding::value).toList());
        }
        return result;
    }
    private static String normalize(String sql) { return sql.replaceAll("\\s+", " ").replaceAll("\\s*([(),=])\\s*", "$1").strip(); }
}
