package org.u80dc.jsqlint.mybatis.plus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.mybatis.*;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MybatisPlusLambdaXmlTest {
    @TempDir Path temporary;
    @Test void getterReferencesMapColumnsAndPreserveValues()throws Exception {
        verify(false,"w.eq(User::getUserName,\"Alice\").ge(User::getAge,18).orderByAsc(User::getId);",null,true);
    }
    @Test void lambdaSelectionArraysAndNestedConditionsMatchFramework()throws Exception {
        verify(false,"w.select(User::getId,User::getUserName).and(child->child.like(User::getUserName,\"Al\").or().lt(User::getAge,10));",null,true);
    }
    @Test void lambdaUpdateSetAndWhereMatchFramework()throws Exception {
        verify(true,"w.set(User::getUserName,\"Alice\").eq(User::getId,\"one\");",null,true);
    }
    @Test void inheritedBooleanGetterAndFactoryConversionMatchFramework()throws Exception {
        verify(false,"w=com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery();w.eq(User::isActive,true).eq(User::getId,\"one\");",null,true);
        verify(false,"w=new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<User>().lambda();w.eq(User::getUserName,\"Alice\");",null,true);
    }
    @Test void xmlCustomSegmentKeepsExplicitTableAndBindings()throws Exception {
        verify(false,"w.eq(User::getUserName,\"Alice\").orderByAsc(User::getId);","SELECT id,display_name,age FROM explicit_users ${ew.customSqlSegment}",true);
        verify(false,"w.orderByAsc(User::getId);","SELECT id FROM explicit_users ${ew.customSqlSegment}",true);
    }
    @Test void customMapperAndScalarParameterKeepParamResolverNames()throws Exception {
        verify(false,"w.eq(User::getUserName,\"Alice\");","SELECT #{marker} AS marker FROM explicit_users ${ew.customSqlSegment}",true);
    }
    @Test void arbitraryLambdaRemainsIncomplete()throws Exception {
        var result=verify(false,"w.eq(user->user.getUserName(),\"Alice\");",null,false);
        assertEquals(AnalysisResult.Status.PARTIAL,result.status());
        assertTrue(result.calls().getFirst().samples().stream().allMatch(sample->sample.sql().isEmpty()));
    }
    @Test void independentProjectKeepsLambdaAndXmlCalls()throws Exception {
        Path project=Path.of("../shared-test-resources/mybatis-plus-crud");Path classes=Files.createDirectories(temporary.resolve("sample-classes"));
        var arguments=new ArrayList<>(List.of("-proc:none","-classpath",System.getProperty("java.class.path"),"-d",classes.toString()));
        try(var files=Files.walk(project.resolve("src/main/java"))){files.filter(path->path.toString().endsWith(".java")).forEach(path->arguments.add(path.toString()));}
        assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,arguments.toArray(String[]::new)));
        String library=Path.of(com.baomidou.mybatisplus.core.mapper.BaseMapper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        try(var code=new JavaClassPathAnalysisInputLocation(classes.toString(),SourceType.Application);var dependency=new JavaClassPathAnalysisInputLocation(library,SourceType.Library);var runtime=new DefaultRuntimeAnalysisInputLocation()) {
            var result=new Analyzer(List.of(new MybatisAnalysisModule(MybatisOptions.defaults(),List.of(new MybatisPlusExtension()))),List.of()).analyze(
                    new AnalysisRequest(List.of(code,dependency,runtime),List.of(new org.u80dc.jsqlint.core.resource.PathResourceSource(project.resolve("src/main/resources")))),"<example.UserService: void lambdaAndXml(example.UserMapper)>");
            assertEquals(2,result.calls().size(),result.diagnostics().toString());
            var samples=result.calls().stream().flatMap(call->call.samples().stream()).toList();
            assertTrue(samples.stream().allMatch(sample->sample.sql().isPresent()));
            assertTrue(samples.stream().flatMap(sample->sample.bindings().stream()).allMatch(binding->binding.value() instanceof SqlValue.Known));
            assertTrue(samples.stream().flatMap(sample->sample.bindings().stream()).anyMatch(binding->binding.value().equals(new SqlValue.Known("sample"))));
        }
    }
    private AnalysisResult verify(boolean update,String operations,String xml,boolean compare)throws Exception {
        boolean custom=xml!=null&&xml.contains("#{marker}");String operation=custom?"custom":update?"update":"selectList";
        String wrapper=update?"LambdaUpdateWrapper":"LambdaQueryWrapper";
        String source="package sample;import com.baomidou.mybatisplus.core.mapper.BaseMapper;import com.baomidou.mybatisplus.annotation.*;"
                +"import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;"
                +"class Parent {String id;public String getId(){return id;}}@TableName(\"users\") class User extends Parent {@TableField(\"display_name\") String userName;Integer age;boolean active;public boolean isActive(){return active;}public String getUserName(){return userName;}public Integer getAge(){return age;}}"
                +"interface UserMapper extends BaseMapper<User>{"+(custom?"java.util.List<User> custom(@org.apache.ibatis.annotations.Param(\"ew\") com.baomidou.mybatisplus.core.conditions.Wrapper<User> wrapper,@org.apache.ibatis.annotations.Param(\"marker\") String marker);":"")+"}"
                +"public class Fixture {public static "+wrapper+"<User> wrapper(){"+wrapper+"<User> w=new "+wrapper+"<>();"+operations+"return w;}"
                +"public static void entry(UserMapper mapper){"+wrapper+"<User> w=new "+wrapper+"<>();"+operations+"mapper."+operation+"("+(update?"null,":"")+"w"+(custom?",\"tag\"":"")+");}}";
        Path root=Files.createTempDirectory(temporary,"fixture-");Path javaFile=Files.writeString(root.resolve("Fixture.java"),source);Path classes=Files.createDirectories(root.resolve("classes"));
        assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-parameters","-classpath",System.getProperty("java.class.path"),"-d",classes.toString(),javaFile.toString()));
        Path resources=Files.createDirectories(root.resolve("xml"));
        String xmlText="<mapper namespace='sample.UserMapper'><select id='"+operation+"'>"+xml+"</select></mapper>";
        if(xml!=null)Files.writeString(resources.resolve("mapper.xml"),xmlText);
        String library=Path.of(com.baomidou.mybatisplus.core.mapper.BaseMapper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();AnalysisResult result;
        try(var code=new JavaClassPathAnalysisInputLocation(classes.toString(),SourceType.Application);var dependency=new JavaClassPathAnalysisInputLocation(library,SourceType.Library);var runtime=new DefaultRuntimeAnalysisInputLocation()) {
            result=new Analyzer(List.of(new MybatisAnalysisModule(MybatisOptions.defaults(),List.of(new MybatisPlusExtension()))),List.of()).analyze(
                    new AnalysisRequest(List.of(code,dependency,runtime),xml==null?List.of():List.of(new org.u80dc.jsqlint.core.resource.PathResourceSource(resources))),"<sample.Fixture: void entry(sample.UserMapper)>");
        }
        assertEquals(1,result.calls().size(),result.diagnostics().toString());if(!compare)return result;
        try(var loader=new java.net.URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},getClass().getClassLoader())) {
            var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(config,new com.baomidou.mybatisplus.core.config.GlobalConfig().setDbConfig(new com.baomidou.mybatisplus.core.config.GlobalConfig.DbConfig()));
            if(xml!=null)new org.apache.ibatis.builder.xml.XMLMapperBuilder(new java.io.ByteArrayInputStream(("<!DOCTYPE mapper PUBLIC '-//mybatis.org//DTD Mapper 3.0//EN' 'https://mybatis.org/dtd/mybatis-3-mapper.dtd'>"+xmlText).getBytes(java.nio.charset.StandardCharsets.UTF_8)),config,"mapper.xml",config.getSqlFragments()).parse();
            if(!config.hasMapper(loader.loadClass("sample.UserMapper"))) config.addMapper(loader.loadClass("sample.UserMapper"));
            Object wrapperValue=loader.loadClass("sample.Fixture").getMethod("wrapper").invoke(null);var parameters=new HashMap<String,Object>();parameters.put("ew",wrapperValue);parameters.put("et",null);parameters.put("marker","tag");
            var bound=config.getMappedStatement("sample.UserMapper."+operation).getBoundSql(parameters);
            var values=bound.getParameterMappings().stream().map(mapping->new SqlValue.Known(bound.hasAdditionalParameter(mapping.getProperty())?bound.getAdditionalParameter(mapping.getProperty()):config.newMetaObject(parameters).getValue(mapping.getProperty()))).toList();
            var samples=result.calls().getFirst().samples().stream().filter(sample->sample.sql().isPresent()&&sample.bindings().stream().allMatch(binding->binding.value() instanceof SqlValue.Known)).toList();
            assertFalse(samples.isEmpty(),result.diagnostics().toString());
            for(var sample:samples){assertEquals(normalize(bound.getSql()),normalize(sample.sql().orElseThrow()));assertEquals(values,sample.bindings().stream().map(SqlSample.Binding::value).toList());}
        }
        return result;
    }
    private static String normalize(String sql){return sql.replaceAll("\\s+"," ").replaceAll("\\s*([(),=<>])\\s*","$1").strip();}
}
