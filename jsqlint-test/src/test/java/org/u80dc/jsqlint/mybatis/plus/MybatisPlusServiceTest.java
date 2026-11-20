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

/** 执行受控 Service 的默认方法，以代理 Mapper 记录真实逻辑调用和 BoundSql，不连接数据库。 */
class MybatisPlusServiceTest {
    @TempDir Path temporary;
    private static final String HEAD="package sample;import com.baomidou.mybatisplus.core.mapper.BaseMapper;import com.baomidou.mybatisplus.annotation.*;import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;";
    @Test void serviceCrudCallsMatchRealDefaultMethods()throws Exception {
        verify("class UserService extends ServiceImpl<UserMapper,User>{UserService(UserMapper mapper){baseMapper=mapper;}}",
                "User first=new User(\"one\",\"Alice\");service.save(first);User second=new User(\"two\",\"Bob\");service.updateById(second);service.getById(\"one\");service.removeById((java.io.Serializable)\"two\");QueryWrapper<User> w=new QueryWrapper<>();w.eq(\"name\",\"Alice\");service.list(w);",true,false);
    }
    @Test void genericIntermediateServiceAndWrapperDeleteMatchFramework()throws Exception {
        verify("class Generic<M extends BaseMapper<T>,T> extends ServiceImpl<M,T>{}class UserService extends Generic<UserMapper,User>{UserService(UserMapper mapper){baseMapper=mapper;}}",
                "QueryWrapper<User> w=new QueryWrapper<>();w.eq(\"id\",\"one\");service.remove(w);",true,false);
    }
    @Test void defaultNullWrapperAndSingleWrapperUpdateMatchFramework()throws Exception {
        verify("class UserService extends ServiceImpl<UserMapper,User>{UserService(UserMapper mapper){baseMapper=mapper;}}",
                "service.list();service.count();service.listMaps();com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<User> w=new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<>();w.set(\"name\",\"Alice\").eq(\"id\",\"one\");service.update(w);",true,false);
    }
    @Test void interfaceAliasRetainsConcreteServiceMapper()throws Exception {
        verify("class UserService extends ServiceImpl<UserMapper,User>{UserService(UserMapper mapper){baseMapper=mapper;}}",
                "com.baomidou.mybatisplus.extension.service.IService<User> alias=service;alias.getById(\"one\");",true,false);
    }
    @Test void chainFactoryDoesNotCreateASqlExecution()throws Exception {
        var result=verify("class UserService extends ServiceImpl<UserMapper,User>{UserService(UserMapper mapper){baseMapper=mapper;}}","service.update();",false,false);
        assertTrue(result.calls().isEmpty(),result.diagnostics().toString());
    }
    @Test void serviceQueryAndUpdateChainTerminalCallsMatchFramework()throws Exception {
        verify("class UserService extends ServiceImpl<UserMapper,User>{UserService(UserMapper mapper){baseMapper=mapper;}}",
                "service.query().eq(\"name\",\"Alice\").orderByAsc(\"id\").list();service.query().eq(\"id\",\"one\").count();service.query().eq(\"id\",\"one\").one();service.query().eq(\"id\",\"one\").exists();service.update().set(\"name\",\"Bob\").eq(\"id\",\"two\").update();service.update().eq(\"id\",\"three\").remove();",true,false);
    }
    @Test void serviceLambdaChainMapsGetterColumns()throws Exception {
        verify("class UserService extends ServiceImpl<UserMapper,User>{UserService(UserMapper mapper){baseMapper=mapper;}}",
                "service.lambdaQuery().eq(User::getName,\"Alice\").list();service.lambdaUpdate().set(User::getName,\"Bob\").eq(User::getId,\"one\").update();",true,false);
    }
    @Test void explicitMapperXmlKeepsPriorityForServiceSave()throws Exception {
        verify("class UserService extends ServiceImpl<UserMapper,User>{UserService(UserMapper mapper){baseMapper=mapper;}}",
                "service.save(new User(\"one\",\"Alice\"));",true,true);
    }
    @Test void userOverrideIsAnalyzedOnceThroughItsRealMapperCall()throws Exception {
        var result=verify("class UserService extends ServiceImpl<UserMapper,User>{UserService(UserMapper mapper){baseMapper=mapper;}@Override public boolean save(User value){return baseMapper.insert(value)>0;}}",
                "service.save(new User(\"one\",\"Alice\"));",false,false);
        assertEquals(1,result.calls().size(),result.diagnostics().toString());
        assertEquals("insert",result.calls().getFirst().callSite().target().getName());
    }
    @Test void unknownBatchRetainsASqlResultAndDiagnosis()throws Exception {
        var result=verify("class UserService extends ServiceImpl<UserMapper,User>{UserService(UserMapper mapper){baseMapper=mapper;}}",
                "service.saveBatch(batchInput());",false,false);
        assertEquals(AnalysisResult.Status.PARTIAL,result.status());
        assertFalse(result.calls().isEmpty());assertTrue(result.diagnostics().toString().contains("Batch"));
    }
    @Test void finiteBatchInsertAndUpdateKeepEachItemSeparate()throws Exception {
        String service="class UserService extends ServiceImpl<UserMapper,User>{UserService(UserMapper mapper){baseMapper=mapper;}}";
        verify(service,"service.saveBatch(java.util.List.of(new User(\"one\",\"Alice\"),new User(\"two\",\"Bob\")));",true,false);
        verify(service,"service.updateBatchById(java.util.List.of(new User(\"one\",\"Alice\"),new User(\"two\",\"Bob\")),1);",true,false);
    }
    @Test void batchFactoryIsSupportedOnlyWithoutLaterEffects()throws Exception {
        String service="class UserService extends ServiceImpl<UserMapper,User>{UserService(UserMapper mapper){baseMapper=mapper;}}";
        verify(service,"service.saveBatch(values());",true,false);
        var result=verify(service,"User item=new User(\"one\",\"before\");java.util.List<User> items=java.util.List.of(item);item.name=\"after\";service.saveBatch(items);",false,false);
        assertEquals(AnalysisResult.Status.PARTIAL,result.status());
        assertTrue(result.diagnostics().toString().contains("snapshot would be stale"));
    }
    @Test void invalidBatchSizeDoesNotCreateKnownSql()throws Exception {
        var result=verify("class UserService extends ServiceImpl<UserMapper,User>{UserService(UserMapper mapper){baseMapper=mapper;}}",
                "service.saveBatch(java.util.List.of(new User(\"one\",\"Alice\")),0);",false,false);
        assertEquals(AnalysisResult.Status.PARTIAL,result.status());
        assertTrue(result.calls().stream().flatMap(call->call.samples().stream()).allMatch(sample->sample.sql().isEmpty()));
    }
    @Test void independentServiceProjectKeepsCallsBatchesAndBindings()throws Exception {
        Path project=Path.of("../shared-test-resources/mybatis-plus-crud");Path classes=Files.createDirectories(temporary.resolve("sample-classes"));
        var arguments=new ArrayList<>(List.of("-proc:none","-classpath",System.getProperty("java.class.path"),"-d",classes.toString()));
        try(var files=Files.walk(project.resolve("src/main/java"))){files.filter(path->path.toString().endsWith(".java")).forEach(path->arguments.add(path.toString()));}
        assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,arguments.toArray(String[]::new)));
        var inputs=new ArrayList<sootup.core.inputlocation.AnalysisInputLocation>();
        try(var code=new JavaClassPathAnalysisInputLocation(classes.toString(),SourceType.Application);var runtime=new DefaultRuntimeAnalysisInputLocation()) {
            inputs.add(code);inputs.add(runtime);
            for(Class<?> type:List.of(com.baomidou.mybatisplus.core.mapper.BaseMapper.class,com.baomidou.mybatisplus.extension.repository.IRepository.class,com.baomidou.mybatisplus.extension.service.impl.ServiceImpl.class))
                inputs.add(new JavaClassPathAnalysisInputLocation(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),SourceType.Library));
            var result=new Analyzer(List.of(new MybatisAnalysisModule(MybatisOptions.defaults(),List.of(new MybatisPlusExtension()))),List.of()).analyze(
                    new AnalysisRequest(inputs,List.of(new org.u80dc.jsqlint.core.resource.PathResourceSource(project.resolve("src/main/resources")))),"<example.ServiceUsage: void run(example.UserCrudService)>");
            assertEquals(5,result.calls().size(),result.diagnostics().toString());
            var samples=result.calls().stream().flatMap(call->call.samples().stream()).toList();assertEquals(6,samples.size(),result.diagnostics().toString());
            assertTrue(samples.stream().allMatch(sample->sample.sql().isPresent()));
            assertTrue(samples.stream().flatMap(sample->sample.bindings().stream()).allMatch(binding->binding.value() instanceof SqlValue.Known));
            assertEquals(2,samples.stream().filter(sample->sample.definition().orElseThrow().metadata().containsKey("batchIndex")).count());
        }finally{for(var input:inputs)if(input instanceof JavaClassPathAnalysisInputLocation location)location.close();}
    }
    @Test void interfaceInputDoesNotGuessAMapperNamespace()throws Exception {
        var result=verify("class UserService extends ServiceImpl<UserMapper,User>{UserService(UserMapper mapper){baseMapper=mapper;}}","service.getById(\"one\");",false,false,true);
        assertEquals(AnalysisResult.Status.PARTIAL,result.status());assertFalse(result.calls().isEmpty());
        assertTrue(result.diagnostics().toString().contains("Mapper type is unresolved"));
        assertTrue(result.calls().stream().flatMap(call->call.samples().stream()).allMatch(sample->sample.sql().isEmpty()));
    }
    private AnalysisResult verify(String service,String body,boolean compare,boolean explicit)throws Exception {
        return verify(service,body,compare,explicit,false);
    }
    private AnalysisResult verify(String service,String body,boolean compare,boolean explicit,boolean interfaceInput)throws Exception {
        String source=HEAD+"@TableName(\"users\") class User{@TableId(type=IdType.INPUT)String id;String name;User(String key,String text){id=key;name=text;}public String getId(){return id;}public String getName(){return name;}}interface UserMapper extends BaseMapper<User>{}"
                +service+"public class Fixture{static native java.util.Collection<User> batchInput();public static java.util.List<User> values(){return java.util.List.of(new User(\"one\",\"Alice\"),new User(\"two\",\"Bob\"));}public static UserService service(UserMapper mapper){return new UserService(mapper);}public static void run("+(interfaceInput?"com.baomidou.mybatisplus.extension.service.IService<User>":"UserService")+" service){"+body+"}}";
        Path root=Files.createTempDirectory(temporary,"fixture-");Path sourceFile=Files.writeString(root.resolve("Fixture.java"),source);Path classes=Files.createDirectories(root.resolve("classes"));
        assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-parameters","-classpath",System.getProperty("java.class.path"),"-d",classes.toString(),sourceFile.toString()));
        Path resources=Files.createDirectories(root.resolve("xml"));String xml="<mapper namespace='sample.UserMapper'><insert id='insert'>INSERT INTO special_users(id,name) VALUES(#{id},#{name})</insert></mapper>";
        if(explicit)Files.writeString(resources.resolve("mapper.xml"),xml);
        var libraries=new ArrayList<sootup.core.inputlocation.AnalysisInputLocation>();
        for(Class<?> type:List.of(com.baomidou.mybatisplus.core.mapper.BaseMapper.class,com.baomidou.mybatisplus.extension.repository.IRepository.class,com.baomidou.mybatisplus.extension.service.impl.ServiceImpl.class))
            libraries.add(new JavaClassPathAnalysisInputLocation(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),SourceType.Library));
        AnalysisResult result;
        try(var code=new JavaClassPathAnalysisInputLocation(classes.toString(),SourceType.Application);var runtime=new DefaultRuntimeAnalysisInputLocation()) {
            var inputs=new ArrayList<sootup.core.inputlocation.AnalysisInputLocation>();inputs.add(code);inputs.addAll(libraries);inputs.add(runtime);
            result=new Analyzer(List.of(new MybatisAnalysisModule(MybatisOptions.defaults(),List.of(new MybatisPlusExtension()))),List.of()).analyze(
                    new AnalysisRequest(inputs,explicit?List.of(new org.u80dc.jsqlint.core.resource.PathResourceSource(resources)):List.of()),"<sample.Fixture: void run("+(interfaceInput?"com.baomidou.mybatisplus.extension.service.IService":"sample.UserService")+")>");
        }finally{for(var library:libraries)if(library instanceof AutoCloseable closeable)closeable.close();}
        if(!compare)return result;
        try(var loader=new java.net.URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},getClass().getClassLoader())) {
            var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(config,new com.baomidou.mybatisplus.core.config.GlobalConfig().setDbConfig(new com.baomidou.mybatisplus.core.config.GlobalConfig.DbConfig()));
            if(explicit)new org.apache.ibatis.builder.xml.XMLMapperBuilder(new java.io.ByteArrayInputStream(("<!DOCTYPE mapper PUBLIC '-//mybatis.org//DTD Mapper 3.0//EN' 'https://mybatis.org/dtd/mybatis-3-mapper.dtd'>"+xml).getBytes(java.nio.charset.StandardCharsets.UTF_8)),config,"mapper.xml",config.getSqlFragments()).parse();
            Class<?> mapperType=loader.loadClass("sample.UserMapper");if(!config.hasMapper(mapperType))config.addMapper(mapperType);
            var expected=new ArrayList<List<Object>>();var methods=new ArrayList<String>();
            Object session=java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{org.apache.ibatis.session.SqlSession.class},(proxy,method,args)->{
                if(method.getName().equals("getConfiguration"))return config;
                if(!Set.of("insert","update","delete","selectOne","selectList","selectMap").contains(method.getName()))return null;
                String statement=(String)args[0];Object parameter=args.length>1?args[1]:null;
                var bound=config.getMappedStatement(statement).getBoundSql(parameter);
                var values=bound.getParameterMappings().stream().map(mapping->new SqlValue.Known(bound.hasAdditionalParameter(mapping.getProperty())?bound.getAdditionalParameter(mapping.getProperty()):parameter==null?null:config.getTypeHandlerRegistry().hasTypeHandler(parameter.getClass())?parameter:config.newMetaObject(parameter).getValue(mapping.getProperty()))).toList();
                expected.add(List.of(normalize(bound.getSql()),values));methods.add(statement);
                if(Set.of("insert","update","delete").contains(method.getName()))return 1;
                if(statement.endsWith(".selectCount"))return 1L;
                if(method.getName().equals("selectList"))return List.of();if(method.getName().equals("selectMap"))return Map.of();return null;
            });
            Object mapper=config.getMapper(mapperType,(org.apache.ibatis.session.SqlSession)session);
            Object actualService=loader.loadClass("sample.Fixture").getMethod("service",mapperType).invoke(null,mapper);
            Object factory=java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{org.apache.ibatis.session.SqlSessionFactory.class},(proxy,method,args)->switch(method.getName()) {
                case "getConfiguration"->config;case "openSession"->session;case "hashCode"->System.identityHashCode(proxy);case "equals"->proxy==args[0];case "toString"->"recordingSqlSessionFactory";default->null;
            });
            var factoryField=com.baomidou.mybatisplus.extension.repository.AbstractRepository.class.getDeclaredField("sqlSessionFactory");factoryField.setAccessible(true);factoryField.set(actualService,factory);
            loader.loadClass("sample.Fixture").getMethod("run",loader.loadClass("sample.UserService")).invoke(null,actualService);
            var actual=new ArrayList<List<Object>>();
            for(var call:result.calls()) for(var sample:call.samples()) {
                assertTrue(sample.sql().isPresent()&&sample.bindings().stream().allMatch(binding->binding.value() instanceof SqlValue.Known),result.diagnostics().toString());
                actual.add(List.of(normalize(sample.sql().orElseThrow()),sample.bindings().stream().map(SqlSample.Binding::value).toList()));
                assertTrue(sample.definition().orElseThrow().metadata().containsKey("logicalMapperMethod"));
                assertTrue(sample.definition().orElseThrow().metadata().get("logicalMapperMethod").contains("sample.UserMapper"));
            }
            assertEquals(expected,actual);
            assertEquals(methods.size(),actual.size());
        }
        return result;
    }
    private static String normalize(String sql){return sql.replaceAll("\\s+"," ").replaceAll("\\s*([(),=<>])\\s*","$1").strip();}
}
