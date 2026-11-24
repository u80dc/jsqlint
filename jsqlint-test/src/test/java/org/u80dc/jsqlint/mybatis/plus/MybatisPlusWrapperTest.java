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

/** 以真实 Wrapper/BoundSql 对照字符串操作顺序及按位置绑定值。 */
class MybatisPlusWrapperTest {
    @TempDir Path temporary;
    @Test void comparisonsBetweenNullAndOrderingMatchFramework() throws Exception {
        verify("selectList",false,"w.eq(\"name\",\"Alice\").ge(\"age\",18).between(\"age\",20,40).isNotNull(\"name\").orderByAsc(\"age\");",true);
    }
    @Test void likeAndInMatchFramework() throws Exception {
        verify("selectList",false,"w.like(\"name\",\"Al\").notLikeLeft(\"name\",\"ce\").in(\"age\",18,21,30).notIn(\"id\",java.util.List.of(\"x\",\"y\"));",true);
    }
    @Test void conditionalOverloadsDoNotLeaveOrphanConnectors() throws Exception {
        verify("selectCount",false,"w.eq(false,\"name\",\"ignored\").eq(true,\"age\",18).or().lt(false,\"age\",10).gt(\"age\",30);",true);
        verify("selectList",false,"boolean include=false;w.eq(\"id\",\"one\").or().eq(include,\"name\",\"ignored\").gt(\"age\",30);",true);
        verify("selectList",false,"boolean include=true;w.eq(\"id\",\"one\").or(include).gt(\"age\",30);",true);
    }
    @Test void countSelectionAndObjectSelectionMatchFramework() throws Exception {
        verify("selectCount",false,"w.select(\"name\").eq(\"age\",18);",true);
        verify("selectObjs",false,"w.eq(\"age\",18);",true);
    }
    @Test void nestedConsumerAndCapturedValueMatchFramework() throws Exception {
        verify("selectList",false,"String name=\"Alice\";w.eq(\"id\",\"one\").and(child->child.eq(\"name\",name).or().gt(\"age\",20)).or(child->child.isNull(\"name\").lt(\"age\",10));",true);
    }
    @Test void updateSetOrderAndWhereBindingsMatchFramework() throws Exception {
        verify("update",true,"w.set(\"name\",\"Alice\").set(false,\"age\",99).set(\"age\",21).eq(\"id\",\"one\");",true);
    }
    @Test void entityAndWrapperSetBindingsMatchFramework() throws Exception {
        verify("update",true,"User entity=entity();w.set(\"age\",21).eq(\"id\",\"one\");",true);
    }
    @Test void deleteLogicalAndEmptyWrapperMatchFramework() throws Exception {
        verify("delete",false,"w.eq(\"id\",\"one\");",true);
        verify("selectList",false,"",true);
    }
    @Test void aliasesAndApplicationHelperKeepWrapperIdentity() throws Exception {
        verify("selectMaps",false,"QueryWrapper<User> alias=w;alias.eq(\"name\",\"Alice\");add(w);",true);
    }
    @Test void multipleOrderingColumnsAndSelectMatchFramework() throws Exception {
        verify("selectList",false,"w.select(\"id\",\"name\").orderByAsc(\"age\",\"name\").orderByDesc(\"id\");",true);
        verify("selectList",false,"w.orderBy(true,false,\"age\",\"id\");",true);
    }
    @Test void factoryNestedNotAndClearMatchFramework() throws Exception {
        verify("selectList",false,"w=com.baomidou.mybatisplus.core.toolkit.Wrappers.query();w.eq(\"id\",\"ignored\");w.clear();w.not(child->child.eq(\"name\",\"A\")).nested(child->child.lt(\"age\",20));",true);
    }
    @Test void nullEmptyInAndBindingMappingMatchFramework() throws Exception {
        verify("selectList",false,"w.eq(\"name\",null).in(\"id\",new Object[]{});",true);
        verify("update",true,"w.set(\"name\",null,\"jdbcType=VARCHAR\").eq(\"id\",\"one\");",true);
    }
    @Test void backingArrayUpdatesBeforeInArePreserved() throws Exception {
        verify("selectList",false,"String[] names={\"A\",\"B\"};java.util.List<String> list=java.util.Arrays.asList(names);names[0]=\"updated\";w.in(\"name\",list);",true);
    }
    @Test void heapEscapeKeepsWrapperIncomplete() throws Exception {
        var result=verify("selectList",false,"w.eq(\"id\",\"one\");saved=w;",false);
        assertEquals(AnalysisResult.Status.PARTIAL,result.status());
        assertTrue(result.diagnostics().toString().contains("heap storage"));
        assertTrue(result.calls().getFirst().samples().stream().allMatch(sample->sample.sql().isEmpty()));
    }
    @Test void explicitXmlIsPreservedInsteadOfGeneratedCrud() throws Exception {
        var result=verify("selectList",false,"w.eq(\"id\",\"one\");",false,true);
        assertTrue(result.calls().getFirst().samples().stream().anyMatch(sample->sample.sql().orElse("").contains("explicit")));
        assertTrue(result.calls().getFirst().samples().stream().noneMatch(sample->sample.sql().orElse("").contains("FROM users")));
    }
    @Test void cyclicHistoryRemainsIncomplete() throws Exception {
        var result=verify("selectList",false,"while(flag()){w.eq(\"id\",\"one\");}",false);
        assertEquals(AnalysisResult.Status.PARTIAL,result.status());
        assertTrue(result.calls().getFirst().samples().stream().allMatch(sample->sample.sql().isEmpty()));
    }
    @Test void sharedWrapperProjectKeepsQueryUpdateAndDeleteBindings() throws Exception {
        Path project=Path.of("../shared-test-resources/mybatis-plus-crud");Path classes=Files.createDirectories(temporary.resolve("sample-classes"));
        var arguments=new ArrayList<>(List.of("-proc:none","-classpath",System.getProperty("java.class.path"),"-d",classes.toString()));
        try(var files=Files.walk(project.resolve("src/main/java"))) { files.filter(path->path.toString().endsWith(".java")).forEach(path->arguments.add(path.toString())); }
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,arguments.toArray(String[]::new)));
        String library=Path.of(com.baomidou.mybatisplus.core.mapper.BaseMapper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        try(var code=new JavaClassPathAnalysisInputLocation(classes.toString(),SourceType.Application);
            var dependency=new JavaClassPathAnalysisInputLocation(library,SourceType.Library);var runtime=new DefaultRuntimeAnalysisInputLocation()) {
            var result=new Analyzer(List.of(new MybatisAnalysisModule(MybatisOptions.defaults(),List.of(new MybatisPlusExtension()))),List.of())
                    .analyze(new AnalysisRequest(List.of(code,dependency,runtime),List.of()),"<example.UserService: void stringWrappers(example.UserMapper)>");
            assertEquals(3,result.calls().size(),result.diagnostics().toString());
            var samples=result.calls().stream().flatMap(call->call.samples().stream()).toList();
            assertEquals(3,samples.size(),result.diagnostics().toString());
            assertTrue(samples.stream().allMatch(sample->sample.sql().isPresent()));
            assertTrue(samples.stream().flatMap(sample->sample.bindings().stream()).allMatch(binding->binding.value() instanceof SqlValue.Known));
            assertTrue(samples.stream().anyMatch(sample->sample.sql().orElseThrow().contains(" IN ")));
            assertTrue(samples.stream().anyMatch(sample->sample.bindings().stream().anyMatch(binding->binding.value().equals(new SqlValue.Known("alice@example.com")))));
        }
    }
    @Test void unknownConditionAndUnsupportedOperationKeepIncompleteSql() throws Exception {
        var result=verify("selectList",false,"w.eq(flag(),\"name\",\"Alice\");",false);
        assertEquals(AnalysisResult.Status.PARTIAL,result.status());
        assertTrue(result.calls().getFirst().samples().stream().anyMatch(sample->sample.sql().isEmpty()));
        var unsupported=verify("selectList",false,"w.apply(\"age > {0}\",18);",false);
        assertEquals(AnalysisResult.Status.PARTIAL,unsupported.status());
        assertTrue(unsupported.diagnostics().toString().contains("Unsupported string Wrapper"));
    }
    @Test void multipleJavaPathsAreNotMergedIntoOneKnownSql() throws Exception {
        var result=verify("selectList",false,"if(flag()){w.eq(\"name\",\"A\");}else{w.eq(\"name\",\"B\");}",false);
        assertEquals(AnalysisResult.Status.PARTIAL,result.status());
        assertTrue(result.calls().getFirst().samples().stream().allMatch(sample->sample.sql().isEmpty()));
    }
    private AnalysisResult verify(String operation,boolean update,String operations,boolean compare) throws Exception {
        return verify(operation,update,operations,compare,false);
    }
    private AnalysisResult verify(String operation,boolean update,String operations,boolean compare,boolean explicit) throws Exception {
        String wrapper=update?"UpdateWrapper":"QueryWrapper";
        String source="package sample;import com.baomidou.mybatisplus.core.mapper.BaseMapper;import com.baomidou.mybatisplus.annotation.*;"
                +"import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;"
                +"@TableName(\"users\") class User {String id;String name;Integer age;@TableLogic Integer deleted;} interface UserMapper extends BaseMapper<User>{}"
                +"public class Fixture {static Object saved;static native boolean flag();public static User entity(){User u=new User();u.name=\"Entity\";return u;}static void add(QueryWrapper<User> w){w.ge(\"age\",18);}"
                +"public static "+wrapper+"<User> wrapper(){"+wrapper+"<User> w=new "+wrapper+"<>();"+operations+"return w;}"
                +"public static void entry(UserMapper mapper){"+wrapper+"<User> w=new "+wrapper+"<>();"+operations+"mapper."+operation+"("+(update?operations.contains("User entity=")?"entity,":"null,":"")+"w);}}";
        Path root=Files.createTempDirectory(temporary,"fixture-");Path java=Files.writeString(root.resolve("Fixture.java"),source);Path classes=Files.createDirectories(root.resolve("classes"));
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-parameters","-classpath",System.getProperty("java.class.path"),"-d",classes.toString(),java.toString()));
        String library=Path.of(com.baomidou.mybatisplus.core.mapper.BaseMapper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Path resources=Files.createDirectories(root.resolve("resources"));
        if(explicit) Files.writeString(resources.resolve("mapper.xml"),"<mapper namespace='sample.UserMapper'><select id='selectList'>SELECT 'explicit'</select></mapper>");
        AnalysisResult result;
        try(var code=new JavaClassPathAnalysisInputLocation(classes.toString(),SourceType.Application);
            var dependency=new JavaClassPathAnalysisInputLocation(library,SourceType.Library);var runtime=new DefaultRuntimeAnalysisInputLocation()) {
            result=new Analyzer(List.of(new MybatisAnalysisModule(MybatisOptions.defaults(),List.of(new MybatisPlusExtension()))),List.of())
                    .analyze(new AnalysisRequest(List.of(code,dependency,runtime),explicit?List.of(new org.u80dc.jsqlint.core.resource.PathResourceSource(resources)):List.of()),"<sample.Fixture: void entry(sample.UserMapper)>");
        }
        assertEquals(1,result.calls().size(),result.diagnostics().toString());
        if(!compare)return result;
        try(var loader=new java.net.URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},getClass().getClassLoader())) {
            var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();
            com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(config,new com.baomidou.mybatisplus.core.config.GlobalConfig().setDbConfig(new com.baomidou.mybatisplus.core.config.GlobalConfig.DbConfig()));
            config.addMapper(loader.loadClass("sample.UserMapper"));
            Object wrapperValue=loader.loadClass("sample.Fixture").getMethod("wrapper").invoke(null);
            var parameters=new HashMap<String,Object>();parameters.put("ew",wrapperValue);parameters.put("et",operations.contains("User entity=")?loader.loadClass("sample.Fixture").getMethod("entity").invoke(null):null);
            var bound=config.getMappedStatement("sample.UserMapper."+operation).getBoundSql(parameters);
            var values=bound.getParameterMappings().stream().map(mapping->new SqlValue.Known(bound.hasAdditionalParameter(mapping.getProperty())?bound.getAdditionalParameter(mapping.getProperty()):config.newMetaObject(parameters).getValue(mapping.getProperty()))).toList();
            var samples=result.calls().getFirst().samples().stream().filter(sample->sample.sql().isPresent()
                    && sample.bindings().stream().allMatch(binding->binding.value() instanceof SqlValue.Known)).toList();
            assertFalse(samples.isEmpty(),result.diagnostics().toString());
            for(var sample:samples) {
                assertEquals(normalize(bound.getSql()),normalize(sample.sql().orElseThrow()));
                assertEquals(values,sample.bindings().stream().map(SqlSample.Binding::value).toList());
            }
        }
        return result;
    }
    private static String normalize(String sql){return sql.replaceAll("\\s+"," ").replaceAll("\\s*([(),=<>])\\s*","$1").strip();}
}
