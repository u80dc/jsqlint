package org.u80dc.jsqlint.mybatis.plus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.core.resource.PathResourceSource;
import org.u80dc.jsqlint.mybatis.*;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.*;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.AnalysisResult.Status.*;

/** 实例属性只取调用发生前该对象的有效写入，并保持路径和不同调用的值关联。 */
class MybatisPlusEntityParameterTest {
    @TempDir Path temporary;
    private static final String HEAD = """
            package sample;
            import com.baomidou.mybatisplus.core.mapper.BaseMapper;
            import com.baomidou.mybatisplus.annotation.*;
            interface UserMapper extends BaseMapper<User> {}
            """;

    @Test void constructorDirectWritesAliasesAndSettersPreserveLastValueForEachCall() throws Exception {
        var classes = compile("""
                class User { String id; User(String value) { id=value; } public void setId(String value) { id=value; } }
                public class Fixture {
                    static void delete(UserMapper mapper, User user) { mapper.deleteById(user); }
                    public static void entry(UserMapper mapper) {
                        User first=new User("ctor"); User other=new User("other"); User alias=first;
                        alias.id="field"; delete(mapper,first);
                        first.setId("setter"); delete(mapper,alias); delete(mapper,other);
                        first.id="after";
                    }
                }
                """);
        var result = analyze(classes, null, null);
        assertEquals(SUCCESS, result.status(), result.diagnostics().toString());
        assertEquals(Set.of("field", "setter", "other"), values(result));
        assertFalse(values(result).contains("ctor")); assertFalse(values(result).contains("after"));
    }

    @Test void getterReadsItsRealBodyInsteadOfAssumingSameNameField() throws Exception {
        var classes = compile("""
                class Parent { protected String stored; public String getId() { return "prefix-"+stored; } }
                class User extends Parent { @TableId String id; User(String value){ stored=value; id="wrong"; } }
                public class Fixture { public static void entry(UserMapper mapper) { mapper.deleteById(new User("one")); } }
                """);
        var result = analyze(classes, null, null);
        assertEquals(SUCCESS, result.status(), result.diagnostics().toString());
        assertEquals(Set.of("prefix-one"), values(result));
    }

    @Test void gettersAndOrdinaryPropertiesCanFeedExplicitSqlUsingFrozenRootMap() throws Exception {
        var classes = compile("""
                class User { String id; String name; boolean active;
                    User(String value) { id=value; name="display"; active=true; }
                    public String getName() { return name+"!"; }
                    public boolean isActive(){return active;}
                }
                public class Fixture { public static void entry(UserMapper mapper) { mapper.deleteById(new User("one")); } }
                """);
        Path resources=Files.createDirectories(temporary.resolve("xml"));
        Files.writeString(resources.resolve("mapper.xml"), "<mapper namespace='sample.UserMapper'><delete id='deleteById'>DELETE FROM users WHERE id=#{id} AND name=#{_parameter.name} AND active=#{active}</delete></mapper>");
        var result=analyze(classes,resources,null);
        assertEquals(SUCCESS,result.status(),result.diagnostics().toString());
        var sample=result.calls().getFirst().samples().getFirst();
        assertEquals(List.of(new SqlValue.Known("one"),new SqlValue.Known("display!"),new SqlValue.Known(true)),sample.bindings().stream().map(SqlSample.Binding::value).toList());
    }

    @Test void branchPropertiesRemainCorrelatedInsteadOfMixingIndependentCandidates() throws Exception {
        var classes=compile("""
                class User { String id; String name; }
                public class Fixture { public static void entry(UserMapper mapper, boolean choose) {
                    User user=new User();
                    if(choose){user.id="A";user.name="alpha";}else{user.id="B";user.name="beta";}
                    mapper.deleteById(user);
                } }
                """);
        Path resources=Files.createDirectories(temporary.resolve("branches"));
        Files.writeString(resources.resolve("mapper.xml"),"<mapper namespace='sample.UserMapper'><delete id='deleteById'>DELETE FROM users WHERE id=#{id} AND name=#{name}</delete></mapper>");
        var result=analyze(classes,resources,null);
        assertEquals(SUCCESS,result.status(),result.diagnostics().toString());
        Set<List<Object>> combinations=new HashSet<>();
        for(var sample:result.calls().getFirst().samples()) if(sample.bindings().stream().allMatch(binding->binding.value() instanceof SqlValue.Known))
            combinations.add(sample.bindings().stream().map(binding->((SqlValue.Known)binding.value()).value()).toList());
        assertEquals(Set.of(List.of("A","alpha"),List.of("B","beta")),combinations);
    }

    @Test void unwrittenNewObjectFieldRetainsRealNull() throws Exception {
        var classes=compile("class User { String id; } public class Fixture { public static void entry(UserMapper mapper){mapper.deleteById(new User());} }");
        var result=analyze(classes,null,null);
        assertEquals(SUCCESS,result.status(),result.diagnostics().toString());
        assertTrue(result.calls().getFirst().samples().stream().allMatch(sample->sample.bindings().getFirst().value().equals(new SqlValue.Known(null))));
    }

    @Test void unknownEntryEntityAndEscapedObjectsKeepUnknownPropertySources() throws Exception {
        var classes=compile("""
                class User { String id; }
                public class Fixture {
                    static native void mutate(Object value);
                    public static void input(UserMapper mapper, User user){mapper.deleteById(user);}
                    public static void escaped(UserMapper mapper){User user=new User(); user.id="before"; mutate(user); mapper.deleteById(user);}
                }
                """);
        for(String method:List.of("input","escaped")){
            var result=analyze(classes,null,"<sample.Fixture: void "+method+"(sample.UserMapper"+(method.equals("input")?",sample.User":"")+")>");
            assertEquals(PARTIAL,result.status(),result.diagnostics().toString());
            assertTrue(result.calls().stream().flatMap(call->call.samples().stream()).flatMap(sample->sample.bindings().stream()).noneMatch(binding->binding.value() instanceof SqlValue.Known));
        }
    }

    @Test void booleanIsGetterAndAllocatedRuntimeTypeTakePrecedence()throws Exception{
        var classes=compile("""
                class Parent { public String getId(){return "parent";} }
                class User extends Parent { String id; public String getId(){return "child";}
                    boolean active; public boolean getActive(){return false;} public boolean isActive(){return true;}
                }
                public class Fixture { public static void entry(UserMapper mapper) {
                    Object user=new User(); mapper.deleteById(user,false);
                } }
                """);
        Path resources=Files.createDirectories(temporary.resolve("getters"));
        Files.writeString(resources.resolve("mapper.xml"),"<mapper namespace='sample.UserMapper'><delete id='deleteById'>DELETE FROM users WHERE id=#{id} AND active=#{active}</delete></mapper>");
        var result=analyze(classes,resources,null);
        assertEquals(SUCCESS,result.status(),result.diagnostics().toString());
        assertEquals(List.of(new SqlValue.Known("child"),new SqlValue.Known(true)),result.calls().getFirst().samples().getFirst().bindings().stream().map(SqlSample.Binding::value).toList());
    }

    @Test void onlyUsedPropertiesAreTracedAndWritesAfterUnknownCallCanRestoreThem()throws Exception{
        var classes=compile("""
                class User { String id; String unused; public native String getUnused(); }
                public class Fixture { static native void mutate(Object user);
                    public static void entry(UserMapper mapper){User user=new User(); mutate(user); user.id="restored"; mapper.deleteById(user);}
                }
                """);
        var result=analyze(classes,null,null);
        assertEquals(SUCCESS,result.status(),result.diagnostics().toString());
        assertEquals(Set.of("restored"),values(result));
    }

    @Test void objectsReturnedByFactoryAndConstructorDelegationPreservePropertySources()throws Exception{
        var classes=compile("""
                class Parent { protected String id; Parent(String value){id=value;} }
                class User extends Parent { User(String value){super(value);} }
                public class Fixture {
                    static User create(String value){return new User(value);}
                    public static void entry(UserMapper mapper){mapper.deleteById(create("factory"));}
                }
                """);
        var result=analyze(classes,null,null);
        assertEquals(SUCCESS,result.status(),result.diagnostics().toString());
        assertEquals(Set.of("factory"),values(result));
    }

    @Test void heapStoredAliasBeforeExternalCallKeepsSourceIncomplete()throws Exception{
        var classes=compile("""
                class User { String id; }
                class Holder { User user; }
                public class Fixture { static native void mutate(Holder holder);
                    public static void entry(UserMapper mapper){User user=new User(); Holder holder=new Holder();holder.user=user;
                        user.id="candidate"; mutate(holder); mapper.deleteById(user);}
                }
                """);
        var result=analyze(classes,null,null);
        assertEquals(PARTIAL,result.status(),result.diagnostics().toString());
        assertTrue(result.calls().stream().flatMap(call->call.samples().stream()).flatMap(sample->sample.bindings().stream()).noneMatch(binding->binding.value() instanceof SqlValue.Known));
    }

    @Test void traceBudgetExhaustionKeepsAnIncompleteResultInsteadOfDroppingSql()throws Exception{
        var classes=compile("""
                class User { String id; User(String value){id=value;} public String getId(){return id+"!";} }
                public class Fixture { public static void entry(UserMapper mapper){mapper.deleteById(new User("one"));} }
                """);
        for(var config:List.of(AnalyzerConfig.defaults().withMaxTraceDepth(1),AnalyzerConfig.defaults().withMaxTraceNodes(1))){
            var result=analyze(classes,null,null,config);
            assertEquals(PARTIAL,result.status(),result.diagnostics().toString());
            assertFalse(result.calls().isEmpty());
            assertTrue(result.calls().stream().flatMap(call->call.samples().stream()).flatMap(sample->sample.bindings().stream()).noneMatch(binding->binding.value() instanceof SqlValue.Known));
            assertTrue(result.diagnostics().stream().anyMatch(issue->issue.code().contains("LIMIT")||issue.code().contains("INCOMPLETE")));
        }
    }

    @Test void objectParameterMapAndGetterBindingMatchTheRealFramework()throws Exception{
        var classes=compile("""
                class User { String id; User(String value){id=value;} public String getId(){return "prefix-"+id;} }
                public class Fixture { public static void entry(UserMapper mapper){mapper.deleteById(new User("one"));} }
                """);
        var result=analyze(classes,null,null);
        assertEquals(SUCCESS,result.status(),result.diagnostics().toString());
        try(var loader=new java.net.URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},getClass().getClassLoader())){
            var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();
            com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(config,
                    new com.baomidou.mybatisplus.core.config.GlobalConfig().setDbConfig(new com.baomidou.mybatisplus.core.config.GlobalConfig.DbConfig()));
            Class<?> mapper=loader.loadClass("sample.UserMapper"); config.addMapper(mapper);
            var constructor=loader.loadClass("sample.User").getDeclaredConstructor(String.class); constructor.setAccessible(true);
            Object instance=constructor.newInstance("one");
            var bound=config.getMappedStatement("sample.UserMapper.deleteById").getBoundSql(instance);
            var mapping=bound.getParameterMappings().getFirst();
            Object actual=config.newMetaObject(instance).getValue(mapping.getProperty());
            var sample=result.calls().getFirst().samples().getFirst();
            assertEquals(bound.getSql().replaceAll("\\s+"," ").strip(),sample.sql().orElseThrow().replaceAll("\\s+"," ").strip());
            assertEquals(new SqlValue.Known(actual),sample.bindings().getFirst().value());
        }
    }

    @Test void ambiguousAndNonPublicGettersRemainIncompleteInsteadOfReadingField()throws Exception{
        for(String getter:List.of("public String getId(){return \"first\";} public String isId(){return \"second\";}",
                "private String getId(){return \"hidden\";}")){
            var classes=compile("class User { String id=\"field\"; "+getter+" } public class Fixture {public static void entry(UserMapper mapper){mapper.deleteById(new User());}}");
            var result=analyze(classes,null,null);
            assertEquals(PARTIAL,result.status(),result.diagnostics().toString());
            assertTrue(result.calls().stream().flatMap(call->call.samples().stream()).flatMap(sample->sample.bindings().stream()).noneMatch(binding->binding.value() instanceof SqlValue.Known));
        }
    }

    private Path compile(String code)throws Exception{
        Path root=Files.createTempDirectory(temporary,"fixture-"); Path source=Files.writeString(root.resolve("Fixture.java"),HEAD+code);
        Path output=Files.createDirectories(root.resolve("classes"));
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-parameters","-classpath",System.getProperty("java.class.path"),"-d",output.toString(),source.toString()));
        return output;
    }
    private AnalysisResult analyze(Path classes,Path resources,String signature)throws Exception{
        return analyze(classes,resources,signature,AnalyzerConfig.defaults());
    }
    private AnalysisResult analyze(Path classes,Path resources,String signature,AnalyzerConfig config)throws Exception{
        String library=Path.of(com.baomidou.mybatisplus.core.mapper.BaseMapper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        try(var code=new JavaClassPathAnalysisInputLocation(classes.toString(),SourceType.Application);
            var dependency=new JavaClassPathAnalysisInputLocation(library,SourceType.Library);
            var runtime=new DefaultRuntimeAnalysisInputLocation()){
            var analyzer=new Analyzer(config,List.of(new MybatisAnalysisModule(MybatisOptions.defaults(),List.of(new MybatisPlusExtension()))),List.of());
            var request=new AnalysisRequest(List.of(code,dependency,runtime),resources==null?List.of():List.of(new PathResourceSource(resources)));
            return signature==null?analyzer.analyze(request):analyzer.analyze(request,signature);
        }
    }
    private static Set<String> values(AnalysisResult result){
        var values=new HashSet<String>();
        result.calls().stream().flatMap(call->call.samples().stream()).flatMap(sample->sample.bindings().stream())
                .map(SqlSample.Binding::value).filter(SqlValue.Known.class::isInstance).map(SqlValue.Known.class::cast)
                .forEach(value->values.add(String.valueOf(value.value())));
        return values;
    }
}
