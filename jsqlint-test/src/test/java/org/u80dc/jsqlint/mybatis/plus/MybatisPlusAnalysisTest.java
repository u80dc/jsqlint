package org.u80dc.jsqlint.mybatis.plus;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.core.resource.PathResourceSource;
import org.u80dc.jsqlint.mybatis.*;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.*;

import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.AnalysisResult.Status.*;

/** 用真实框架注入的 BoundSql 校验静态生成定义；只在受控 oracle 中加载测试实体。 */
class MybatisPlusAnalysisTest {
    @TempDir Path temporary;
    private static final String HEAD = """
            package sample;
            import com.baomidou.mybatisplus.core.mapper.BaseMapper;
            import com.baomidou.mybatisplus.annotation.*;
            import java.io.Serializable;
            """;

    @Test void discoversInheritedCrudCallsOnceAndMatchesFrameworkBoundSql() throws Exception {
        Path classes = compile("""
                @TableName("app_users") class User {
                    @TableField("display_name") String name;
                    @TableId("user_id") String id;
                    @TableField(exist=false) String temporary;
                    @TableField(select=false) String secret;
                }
                interface CommonMapper<T> extends BaseMapper<T> {}
                interface UserMapper extends CommonMapper<User> {}
                public class Fixture {
                    public static void query(UserMapper mapper) { mapper.selectById("seven"); mapper.deleteById("eight"); }
                }
                """);
        var result = analyze(classes, null, null, MybatisPlusOptions.defaults());
        assertEquals(SUCCESS, result.status(), result.diagnostics().toString());
        assertEquals(2, result.calls().size());
        var select = result.calls().stream().filter(call -> call.callSite().target().getName().equals("selectById")).findFirst().orElseThrow();
        var delete = result.calls().stream().filter(call -> call.callSite().target().getName().equals("deleteById")).findFirst().orElseThrow();
        var selected = select.samples().getFirst(); var deleted = delete.samples().getFirst();
        assertEquals(oracle(classes, "UserMapper", "selectById", "seven", MybatisPlusOptions.defaults()), normalize(selected.sql().orElseThrow()));
        assertEquals(oracle(classes, "UserMapper", "deleteById", "eight", MybatisPlusOptions.defaults()), normalize(deleted.sql().orElseThrow()));
        assertEquals(List.of(new SqlValue.Known("seven")), selected.bindings().stream().map(SqlSample.Binding::value).toList());
        assertEquals(List.of(new SqlValue.Known("eight")), deleted.bindings().stream().map(SqlSample.Binding::value).toList());
        assertEquals("sample.User", selected.definition().orElseThrow().metadata().get("entityType"));
        // 自动发现只有一个 core MyBatis 模块，Plus 通过独立 SPI 接入。
        assertEquals(1, ServiceLoader.load(org.u80dc.jsqlint.core.spi.SqlAnalysisModule.class).stream()
                .filter(provider -> provider.type().getName().contains("mybatis")).count());
    }

    @Test void directInheritedMethodsSupportBothSignatureFormatsIncludingLibraryDefaultDelete() throws Exception {
        Path classes = compile("class User { String id; String userName; } interface UserMapper extends BaseMapper<User> {} public class Fixture {}");
        for (String method : List.of("selectById", "deleteById")) {
            String returned = method.equals("selectById") ? "java.lang.Object" : "int";
            var soot = analyze(classes, null, "<sample.UserMapper: " + returned + " " + method + "(java.io.Serializable)>", MybatisPlusOptions.defaults());
            var jvm = analyze(classes, null, "sample/UserMapper." + method + ":(Ljava/io/Serializable;)" + (method.equals("selectById") ? "Ljava/lang/Object;" : "I"), MybatisPlusOptions.defaults());
            assertEquals(SUCCESS, soot.status(), soot.diagnostics().toString()); assertEquals(SUCCESS, jvm.status(), jvm.diagnostics().toString());
            assertEquals(1, soot.definitions().size()); assertTrue(soot.calls().isEmpty());
            var sample = soot.definitions().getFirst().sample(Map.of("id", "id-value"));
            assertEquals(oracle(classes, "UserMapper", method, "id-value", MybatisPlusOptions.defaults()), normalize(sample.sql().orElseThrow()));
            assertEquals(soot.rawSqls(), jvm.rawSqls());
        }
    }

    @Test void logicalDeleteNullValuesAndInheritedFieldsMatchFramework() throws Exception {
        Path classes = compile("""
                class Parent { @TableId("user_id") String id; String inheritedName; }
                @TableName("logic_users") class User extends Parent {
                    @TableLogic(value="null", delval="now()") java.time.LocalDateTime deletedAt;
                    @TableField(exist=false) String inheritedName;
                }
                interface UserMapper extends BaseMapper<User> {}
                public class Fixture {}
                """);
        for (String method : List.of("selectById", "deleteById")) {
            var result = analyze(classes, null, "<sample.UserMapper: " + (method.equals("selectById") ? "java.lang.Object" : "int") + " " + method + "(java.io.Serializable)>", MybatisPlusOptions.defaults());
            assertEquals(SUCCESS, result.status(), result.diagnostics().toString());
            assertEquals(oracle(classes, "UserMapper", method, "value", MybatisPlusOptions.defaults()), normalize(result.definitions().getFirst().sample(Map.of("id", "value")).sql().orElseThrow()));
        }
    }

    @Test void explicitXmlOverridesGeneratedDefinitionsWithoutDuplicateCalls() throws Exception {
        Path classes = compile("""
                class User { String id; }
                interface UserMapper extends BaseMapper<User> {}
                public class Fixture { public static void query(UserMapper mapper) { mapper.selectById("seven"); } }
                """);
        Path resources = Files.createDirectories(temporary.resolve("resources"));
        Files.writeString(resources.resolve("mapper.xml"), "<mapper namespace='sample.UserMapper'><select id='selectById'>SELECT #{id} FROM explicit_users</select></mapper>");
        var result = analyze(classes, resources, null, MybatisPlusOptions.defaults());
        assertEquals(SUCCESS, result.status(), result.diagnostics().toString()); assertEquals(1, result.calls().size());
        assertEquals("SELECT ? FROM explicit_users", result.calls().getFirst().samples().getFirst().sql().orElseThrow());
        assertEquals(resources.resolve("mapper.xml").toUri(), result.calls().getFirst().samples().getFirst().definition().orElseThrow().location().resource());
        // 显式定义损坏不能静默回退到自动 CRUD。
        Files.writeString(resources.resolve("mapper.xml"), "<mapper namespace='sample.UserMapper'><select id='selectById'><include refid='missing'/></select></mapper>");
        var invalid = analyze(classes, resources, null, MybatisPlusOptions.defaults());
        assertEquals(PARTIAL, invalid.status());
        var broken = invalid.calls().getFirst().samples().getFirst().definition().orElseThrow();
        assertFalse(broken.complete()); assertEquals(resources.resolve("mapper.xml").toUri(), broken.location().resource());
        assertTrue(invalid.rawSqls().stream().noneMatch(sql -> sql.text().contains("FROM user")));
    }

    @Test void unresolvedGenericEntityAndMissingPrimaryKeyRemainIncomplete() throws Exception {
        Path classes = compile("""
                class NoKey { String name; }
                interface UserMapper extends BaseMapper<NoKey> {}
                interface RawMapper extends BaseMapper {}
                public class Fixture {}
                """);
        for (String mapper : List.of("UserMapper", "RawMapper")) {
            var result = analyze(classes, null, "<sample." + mapper + ": java.lang.Object selectById(java.io.Serializable)>", MybatisPlusOptions.defaults());
            assertEquals(PARTIAL, result.status()); assertEquals(1, result.definitions().size());
            assertFalse(result.definitions().getFirst().definition().orElseThrow().complete());
            assertTrue(result.definitions().getFirst().samples().getFirst().sql().isEmpty());
        }
    }

    @Test void explicitOptionsAndSessionIsolationMatchFrameworkDefaultsAndOverrides() throws Exception {
        Path classes = compile("class User { String id; String userName; @TableLogic String deleted; } interface UserMapper extends BaseMapper<User> {} public class Fixture {}");
        var options = MybatisPlusOptions.defaults().withTablePrefix("app_").withSchema("audit").withCapitalMode(true).withLogicDelete("", "N", "Y");
        for (var configured : List.of(MybatisPlusOptions.defaults(), options, MybatisPlusOptions.defaults())) {
            var result = analyze(classes, null, "<sample.UserMapper: java.lang.Object selectById(java.io.Serializable)>", configured);
            assertEquals(SUCCESS, result.status(), result.diagnostics().toString());
            assertEquals(oracle(classes, "UserMapper", "selectById", "id", configured), normalize(result.definitions().getFirst().sample(Map.of("id", "id")).sql().orElseThrow()));
        }
    }

    @Test void nullWrapperQueryAndUnknownEntityAreHandledIndependently() throws Exception {
        Path classes = compile("""
                class User { String id; }
                interface UserMapper extends BaseMapper<User> {}
                public class Fixture {
                    public static void query(UserMapper mapper, User user) {
                        mapper.selectList(null); mapper.deleteById(user);
                    }
                }
                """);
        var result = analyze(classes, null, null, MybatisPlusOptions.defaults());
        assertEquals(PARTIAL, result.status(), result.diagnostics().toString());
        assertEquals(2, result.calls().size());
        var wrapper = result.calls().stream().filter(call -> call.callSite().target().getName().equals("selectList")).findFirst().orElseThrow();
        assertTrue(wrapper.samples().getFirst().definition().orElseThrow().complete());
        assertTrue(wrapper.samples().getFirst().sql().orElseThrow().contains("SELECT id FROM user"));
        var entity = result.calls().stream().filter(call -> call.callSite().target().getName().equals("deleteById")).findFirst().orElseThrow();
        assertFalse(entity.complete());
        assertTrue(entity.samples().stream().flatMap(sample -> sample.bindings().stream()).noneMatch(binding -> binding.value() instanceof SqlValue.Known));
    }

    @Test void automaticExtensionDiscoveryAndExplicitEmptyListKeepIndependentBehavior() throws Exception {
        Path classes = compile("class User { String id; } interface UserMapper extends BaseMapper<User> {} public class Fixture {}");
        String library = Path.of(com.baomidou.mybatisplus.core.mapper.BaseMapper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        try (var code = new JavaClassPathAnalysisInputLocation(classes.toString(), SourceType.Application);
             var dependency = new JavaClassPathAnalysisInputLocation(library, SourceType.Library)) {
            var request = new AnalysisRequest(List.of(code, dependency), List.of());
            String signature = "<sample.UserMapper: java.lang.Object selectById(java.io.Serializable)>";
            var automatic = new Analyzer(List.of(new MybatisAnalysisModule()), List.of()).analyze(request, signature);
            assertEquals(SUCCESS, automatic.status(), automatic.diagnostics().toString());
            assertEquals(1, automatic.definitions().size());
            var disabled = new Analyzer(List.of(new MybatisAnalysisModule(MybatisOptions.defaults(), List.of())), List.of()).analyze(request, signature);
            assertEquals(PARTIAL, disabled.status()); assertTrue(disabled.sqlResults().isEmpty());
        }
    }

    @Test void tableResultMapAndQuotedColumnsMatchFramework() throws Exception {
        Path classes = compile("""
                @TableName(value="users",autoResultMap=true) class User {
                    @TableId("`user_id`") String id;
                    @TableField("`display_name`") String userName;
                    @TableField("`created_at`") String createdAt;
                }
                interface UserMapper extends BaseMapper<User> {}
                public class Fixture {}
                """);
        var result = analyze(classes, null, "<sample.UserMapper: java.lang.Object selectById(java.io.Serializable)>", MybatisPlusOptions.defaults());
        assertEquals(SUCCESS, result.status(), result.diagnostics().toString());
        assertEquals(oracle(classes, "UserMapper", "selectById", "one", MybatisPlusOptions.defaults()), normalize(result.definitions().getFirst().sample(Map.of("id", "one")).sql().orElseThrow()));
    }

    @Test void reusedAnalyzerDoesNotCarryEntityMappingsBetweenProjects() throws Exception {
        var analyzer = new Analyzer(List.of(new MybatisAnalysisModule(MybatisOptions.defaults(), List.of(new MybatisPlusExtension()))), List.of());
        String library = Path.of(com.baomidou.mybatisplus.core.mapper.BaseMapper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        for (String table : List.of("first_users", "second_users", "first_users")) {
            Path classes = compile("@TableName(\"" + table + "\") class User { String id; } interface UserMapper extends BaseMapper<User> {} public class Fixture {}");
            try (var code = new JavaClassPathAnalysisInputLocation(classes.toString(), SourceType.Application);
                 var dependency = new JavaClassPathAnalysisInputLocation(library, SourceType.Library)) {
                var result = analyzer.analyze(new AnalysisRequest(List.of(code, dependency), List.of()), "<sample.UserMapper: java.lang.Object selectById(java.io.Serializable)>");
                assertEquals(SUCCESS, result.status(), result.diagnostics().toString());
                assertTrue(result.rawSqls().getFirst().text().contains("FROM " + table));
            }
        }
    }

    @Test void characterLogicValuesFollowFrameworkLiteralRules() throws Exception {
        Path classes = compile("class User { String id; @TableLogic char deleted; } interface UserMapper extends BaseMapper<User> {} public class Fixture {}");
        var result = analyze(classes, null, "<sample.UserMapper: int deleteById(java.io.Serializable)>", MybatisPlusOptions.defaults());
        assertEquals(SUCCESS, result.status(), result.diagnostics().toString());
        assertEquals(oracle(classes, "UserMapper", "deleteById", "one", MybatisPlusOptions.defaults()), normalize(result.definitions().getFirst().sample(Map.of("id", "one")).sql().orElseThrow()));
    }

    private Path compile(String body) throws Exception {
        Path root = Files.createTempDirectory(temporary, "fixture-");
        Path source = Files.writeString(root.resolve("Fixture.java"), HEAD + body);
        Path classes = Files.createDirectories(root.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-parameters", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString(), source.toString()));
        return classes;
    }
    private AnalysisResult analyze(Path classes, Path resources, String signature, MybatisPlusOptions options) throws Exception {
        return analyze(classes,resources,signature,new MybatisPlusExtension(options));
    }
    private AnalysisResult analyze(Path classes, Path resources, String signature, MybatisPlusExtension extension) throws Exception {
        String library = Path.of(com.baomidou.mybatisplus.core.mapper.BaseMapper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        try (var code = new JavaClassPathAnalysisInputLocation(classes.toString(), SourceType.Application);
             var dependencies = new JavaClassPathAnalysisInputLocation(library, SourceType.Library);
             var runtime = new DefaultRuntimeAnalysisInputLocation()) {
            var analyzer = new Analyzer(AnalyzerConfig.defaults(),
                    List.of(new MybatisAnalysisModule(MybatisOptions.defaults(), List.of(extension))), List.of());
            var request = new AnalysisRequest(List.of(code, dependencies, runtime), resources == null ? List.of() : List.of(new PathResourceSource(resources)));
            return signature == null ? analyzer.analyze(request) : analyzer.analyze(request, signature);
        }
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private String oracle(Path classes, String mapperName, String method, Object value, MybatisPlusOptions options) throws Exception {
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(options.mapUnderscoreToCamelCase());
            var db = new GlobalConfig.DbConfig().setTableUnderline(options.tableUnderline()).setCapitalMode(options.capitalMode()).setTablePrefix(options.tablePrefix())
                    .setSchema(options.schema()).setLogicDeleteField(options.logicDeleteField()).setLogicNotDeleteValue(options.logicNotDeleteValue()).setLogicDeleteValue(options.logicDeleteValue());
            GlobalConfigUtils.setGlobalConfig(config, new GlobalConfig().setDbConfig(db));
            Class mapper = loader.loadClass("sample." + mapperName); config.addMapper(mapper);
            var bound = config.getMappedStatement("sample." + mapperName + "." + method).getBoundSql(value);
            assertEquals(1, bound.getParameterMappings().size());
            return normalize(bound.getSql());
        }
    }
    private static String normalize(String sql) { return sql.replaceAll("\\s+", " ").replaceAll("\\s*,\\s*", ",").replaceAll("\\s*=\\s*", "=").strip(); }
    @Test void projectPropertiesAndYamlMatchFrameworkAndCallerOverrides()throws Exception {
        Path classes=compile("class User { String id; String userName; Integer deleted; } interface UserMapper extends BaseMapper<User> {} public class Fixture {}");
        Path resources=Files.createDirectories(temporary.resolve("project"));
        for(String format:List.of("properties","yaml")) {
            Path file=resources.resolve("application."+format);
            Files.writeString(file,format.equals("properties")?"mybatis-plus.global-config.db-config.table-prefix=app_\nmybatis-plus.global-config.db-config.logic-delete-field=deleted\n":"mybatis-plus:\n  global-config:\n    db-config:\n      table-prefix: app_\n      logic-delete-field: deleted\n");
            var options=MybatisPlusOptions.defaults().withTablePrefix("app_").withLogicDelete("deleted","0","1");
            var result=analyze(classes,resources,"<sample.UserMapper: java.lang.Object selectById(java.io.Serializable)>",new MybatisPlusExtension());
            assertEquals(SUCCESS,result.status(),result.diagnostics().toString());
            var sample=result.definitions().getFirst().sample(Map.of("id","one"));
            assertEquals(oracle(classes,"UserMapper","selectById","one",options),normalize(sample.sql().orElseThrow()));
            assertTrue(sample.definition().orElseThrow().metadata().get("configurationSource.tablePrefix").contains("application."+format));
            var override=analyze(classes,resources,"<sample.UserMapper: java.lang.Object selectById(java.io.Serializable)>",MybatisPlusOptions.defaults());
            assertEquals(oracle(classes,"UserMapper","selectById","one",MybatisPlusOptions.defaults()),normalize(override.definitions().getFirst().sample(Map.of("id","one")).sql().orElseThrow()));
            Files.delete(file);
        }
    }
    @Test void unresolvedProjectConfigurationDoesNotInventCrudSql()throws Exception {
        Path classes=compile("class User { String id; } interface UserMapper extends BaseMapper<User> {} public class Fixture {}");
        Path resources=Files.createDirectories(temporary.resolve("project"));
        Files.writeString(resources.resolve("application.properties"),"mybatis-plus.global-config.db-config.table-prefix=${TABLE_PREFIX}\n");
        var result=analyze(classes,resources,"<sample.UserMapper: java.lang.Object selectById(java.io.Serializable)>",new MybatisPlusExtension());
        assertEquals(PARTIAL,result.status());assertTrue(result.definitions().getFirst().sample(Map.of("id","one")).sql().isEmpty());
    }
    @Test void explicitInjectorModelMatchesActualAbstractMethodAndExcludesAbsentDefaults()throws Exception {
        Path classes=compile("class User { String id; } interface UserMapper extends BaseMapper<User> { long countAll(); } public class Fixture {}");
        var model=new PlusSqlInjectorModel("sample.CustomInjector","3.5.16",false,List.of(new PlusSqlInjectorModel.Method("countAll",List.of(),"select","SELECT COUNT(*) FROM user")));
        var extension=new MybatisPlusExtension(MybatisPlusOptions.defaults(),PlusPluginConfig.none(),false,Optional.of(model));
        var result=analyze(classes,null,"<sample.UserMapper: long countAll()>",extension);
        assertEquals(SUCCESS,result.status(),result.diagnostics().toString());
        try(var loader=new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},getClass().getClassLoader())) {
            var configuration=new MybatisConfiguration();
            var injector=new com.baomidou.mybatisplus.core.injector.DefaultSqlInjector(){
                @Override public List<com.baomidou.mybatisplus.core.injector.AbstractMethod> getMethodList(org.apache.ibatis.session.Configuration config,Class<?> mapper,com.baomidou.mybatisplus.core.metadata.TableInfo table) {
                    return List.of(new com.baomidou.mybatisplus.core.injector.AbstractMethod("countAll") {
                        @Override public org.apache.ibatis.mapping.MappedStatement injectMappedStatement(Class<?> mapper,Class<?> entity,com.baomidou.mybatisplus.core.metadata.TableInfo table) {
                            return addSelectMappedStatementForOther(mapper,methodName,createSqlSource(configuration,"SELECT COUNT(*) FROM "+table.getTableName(),Object.class),Long.class);
                        }
                    });
                }
            };
            GlobalConfigUtils.setGlobalConfig(configuration,new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()).setSqlInjector(injector));
            configuration.addMapper(loader.loadClass("sample.UserMapper"));
            assertEquals(configuration.getMappedStatement("sample.UserMapper.countAll").getBoundSql(null).getSql(),result.definitions().getFirst().sample(Map.of()).sql().orElseThrow());
        }
        var absent=analyze(classes,null,"<sample.UserMapper: java.lang.Object selectById(java.io.Serializable)>",extension);
        assertEquals(PARTIAL,absent.status());assertTrue(absent.definitions().getFirst().sample(Map.of("id","one")).sql().isEmpty());
    }
    @Test void injectedMethodParametersAndExplicitXmlUseStandardMybatisBinding()throws Exception {
        Path classes=compile("class User { String id; } interface UserMapper extends BaseMapper<User> { String findName(@org.apache.ibatis.annotations.Param(\"key\") String key); } public class Fixture { public static void query(UserMapper mapper) { mapper.findName(\"one\"); } }");
        var model=new PlusSqlInjectorModel("sample.CustomInjector","3.5.16",false,List.of(new PlusSqlInjectorModel.Method("findName",List.of("java.lang.String"),"select","SELECT name FROM user WHERE id=#{key}")));
        var extension=new MybatisPlusExtension(MybatisPlusOptions.defaults(),PlusPluginConfig.none(),false,Optional.of(model));
        var generated=analyze(classes,null,"<sample.Fixture: void query(sample.UserMapper)>",extension);
        assertEquals(List.of(new SqlValue.Known("one")),generated.calls().getFirst().samples().getFirst().bindings().stream().map(SqlSample.Binding::value).toList());
        Path resources=Files.createDirectories(temporary.resolve("explicit"));Files.writeString(resources.resolve("mapper.xml"),"<mapper namespace='sample.UserMapper'><select id='findName'>SELECT #{key} FROM explicit_users</select></mapper>");
        var explicit=analyze(classes,resources,"<sample.Fixture: void query(sample.UserMapper)>",extension);
        var sample=explicit.calls().getFirst().samples().getFirst();assertEquals("SELECT ? FROM explicit_users",sample.sql().orElseThrow());assertEquals(List.of(new SqlValue.Known("one")),sample.bindings().stream().map(SqlSample.Binding::value).toList());
        var mismatched=new PlusSqlInjectorModel("sample.CustomInjector","3.5.15",false,model.methods());
        var unsupported=analyze(classes,null,"<sample.UserMapper: java.lang.String findName(java.lang.String)>",new MybatisPlusExtension(MybatisPlusOptions.defaults(),PlusPluginConfig.none(),false,Optional.of(mismatched)));
        assertEquals(PARTIAL,unsupported.status());assertTrue(unsupported.definitions().getFirst().sample(Map.of("key","one")).sql().isEmpty());
    }
}
