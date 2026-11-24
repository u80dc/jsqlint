package org.u80dc.jsqlint.mybatis.plus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.mybatis.*;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.*;
import com.baomidou.mybatisplus.extension.plugins.inner.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 运行实际 InnerInterceptor 钩子，对照 SQL、count 和全部参数；Executor 仅返回受控 count，不访问数据库。 */
class MybatisPlusPluginTest {
    @TempDir Path temporary;
    @Test void tenantAndDynamicTablesFollowConfiguredOrder()throws Exception {
        var tenant=new PlusPluginConfig.Tenant("tenant_id",new SqlValue.Known(7L));
        var dynamic=new PlusPluginConfig.DynamicTables(Map.of("users",new SqlValue.Known("users_2026")));
        verify("selectById",new PlusPluginConfig(List.of(tenant,dynamic)),"",null,true);
        verify("selectById",new PlusPluginConfig(List.of(dynamic,tenant)),"",null,true);
    }
    @Test void tenantInsertAndUnknownTenantKeepBindingsAndEvidence()throws Exception {
        verify("insert",new PlusPluginConfig(List.of(new PlusPluginConfig.Tenant("tenant_id",new SqlValue.Known(7L)))),"",null,true);
        var result=verify("selectById",new PlusPluginConfig(List.of(new PlusPluginConfig.Tenant("tenant_id",new SqlValue.Unknown("tenant unavailable")))),"",null,false);
        assertTrue(result.calls().getFirst().samples().stream().allMatch(sample->sample.sql().isEmpty()));
        assertTrue(result.calls().getFirst().samples().getFirst().rewrites().getFirst().beforeSql().isPresent());
    }
    @Test void mysqlPaginationRecordsCountAndBoundParameters()throws Exception {
        var result=verify("selectPage",new PlusPluginConfig(List.of(new PlusPluginConfig.Tenant("tenant_id",new SqlValue.Known(7L)),new PlusPluginConfig.Pagination(PlusPluginConfig.Dialect.MYSQL))),"",null,true,true);
        var statistics=result.statistics().orElseThrow();
        assertTrue(statistics.phases().get(AnalysisStatistics.Phase.REWRITING).invocations()>0);
        assertTrue(statistics.phases().get(AnalysisStatistics.Phase.RENDERING).invocations()>0);
        assertEquals(statistics.elapsedNanos(),statistics.phases().values().stream().mapToLong(AnalysisStatistics.PhaseCost::selfNanos).sum());
        assertTrue(statistics.measurements().isEmpty());
    }
    @Test void postgresPaginationWithoutCountAndMaxLimitMatchFramework()throws Exception {
        verify("selectPage",new PlusPluginConfig(List.of(new PlusPluginConfig.Pagination(PlusPluginConfig.Dialect.POSTGRESQL,Optional.of(4L),false,true))),"page.setSearchCount(false);",null,true);
    }
    @Test void paginationBeforeDynamicTableDoesNotRewriteCountWithLaterPlugin()throws Exception {
        verify("selectPage",new PlusPluginConfig(List.of(new PlusPluginConfig.Pagination(PlusPluginConfig.Dialect.MYSQL),new PlusPluginConfig.DynamicTables(Map.of("users",new SqlValue.Known("users_2026"))))),"",null,true);
    }
    @Test void optimisticVersionAndTenantHooksMatchGeneratedUpdateById()throws Exception {
        verify("updateById",new PlusPluginConfig(List.of(new PlusPluginConfig.Tenant("tenant_id",new SqlValue.Known(7L)),new PlusPluginConfig.OptimisticLock())),"",null,true);
    }
    @Test void overflowAndUnknownPageAreNotPresentedAsKnownMainSql()throws Exception {
        var result=verify("selectPage",new PlusPluginConfig(List.of(new PlusPluginConfig.Pagination(PlusPluginConfig.Dialect.MYSQL,Optional.empty(),true,true))),"",null,false);
        assertTrue(result.calls().getFirst().samples().stream().filter(sample->sample.definition().orElseThrow().metadata().get("statementRole").equals("main")).allMatch(sample->sample.sql().isEmpty()));
        var unknown=verify("selectPage",new PlusPluginConfig(List.of(new PlusPluginConfig.Pagination(PlusPluginConfig.Dialect.MYSQL))),"page.setSize(size());",null,false);
        assertTrue(unknown.calls().getFirst().samples().stream().allMatch(sample->sample.sql().isEmpty()));
    }
    @Test void quotedQuestionMarksAreNotTreatedAsBindings()throws Exception {
        verify("selectById",new PlusPluginConfig(List.of(new PlusPluginConfig.Tenant("tenant_id",new SqlValue.Known(7L)))),"","SELECT '?' AS marker,id FROM users WHERE id=#{id}",true);
    }
    @Test void tenantJoinAndIgnoredTablesMatchFramework()throws Exception {
        var tenant=new PlusPluginConfig.Tenant("tenant_id",new SqlValue.Known(7L));
        verify("selectById",new PlusPluginConfig(List.of(tenant)),"","SELECT u.id FROM users u LEFT JOIN departments d ON d.id=u.id WHERE u.id=#{id}",true);
        verify("selectById",new PlusPluginConfig(List.of(new PlusPluginConfig.Tenant("tenant_id",new SqlValue.Known(7L),Set.of("users")))),"",null,true);
    }
    @Test void countProjectionBindingFallbackAndUnknownTableRetainEvidence()throws Exception {
        verify("selectPage",new PlusPluginConfig(List.of(new PlusPluginConfig.Pagination(PlusPluginConfig.Dialect.MYSQL))),"","<bind name='marker' value='7'/>SELECT #{marker} AS marker,id FROM users ${ew.customSqlSegment}",true);
        var result=verify("selectById",new PlusPluginConfig(List.of(new PlusPluginConfig.DynamicTables(Map.of("users",new SqlValue.Unknown("table unavailable"))))),"",null,false);
        assertTrue(result.calls().getFirst().samples().stream().allMatch(sample->sample.sql().isEmpty()));
    }
    private AnalysisResult verify(String operation,PlusPluginConfig plugins,String extra,String xml,boolean compare)throws Exception {
        return verify(operation,plugins,extra,xml,compare,false);
    }
    private AnalysisResult verify(String operation,PlusPluginConfig plugins,String extra,String xml,boolean compare,boolean collectStatistics)throws Exception {
        boolean paging=operation.equals("selectPage");String call=paging?"page,wrapper":operation.equals("insert")||operation.equals("updateById")?"new User(\"one\",\"Alice\",3)":"\"one\"";
        String setup=paging?"com.baomidou.mybatisplus.extension.plugins.pagination.Page<User> page=new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(2,10);com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<User> wrapper=new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();wrapper.eq(\"name\",\"Alice\");":"";
        String parameter=paging?"java.util.Map.of(\"page\",page,\"ew\",wrapper)":operation.equals("updateById")?"java.util.Map.of(\"et\",new User(\"one\",\"Alice\",3))":operation.equals("insert")?"new User(\"one\",\"Alice\",3)":"\"one\"";
        String source="package sample;import com.baomidou.mybatisplus.core.mapper.BaseMapper;import com.baomidou.mybatisplus.annotation.*;@TableName(\"users\")class User{@TableId(type=IdType.INPUT)String id;String name;@Version Integer version;User(String key,String text,Integer value){id=key;name=text;version=value;}public String getId(){return id;}public String getName(){return name;}public Integer getVersion(){return version;}}interface UserMapper extends BaseMapper<User>{}public class Fixture{static native long size();public static Object parameter(){"+setup+extra+"return "+parameter+";}public static void entry(UserMapper mapper){"+setup+extra+"mapper."+operation+"("+call+");}}";
        Path root=Files.createTempDirectory(temporary,"fixture-");Path sourceFile=Files.writeString(root.resolve("Fixture.java"),source);Path classes=Files.createDirectories(root.resolve("classes"));
        assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-parameters","-classpath",System.getProperty("java.class.path"),"-d",classes.toString(),sourceFile.toString()));
        Path resources=Files.createDirectories(root.resolve("xml"));String statement=paging?"selectList":operation;
        String xmlText="<mapper namespace='sample.UserMapper'><select id='"+statement+"'>"+xml+"</select></mapper>";
        if(xml!=null)Files.writeString(resources.resolve("mapper.xml"),xmlText);
        var inputs=new ArrayList<sootup.core.inputlocation.AnalysisInputLocation>();AnalysisResult result;
        try(var code=new JavaClassPathAnalysisInputLocation(classes.toString(),SourceType.Application);var runtime=new DefaultRuntimeAnalysisInputLocation()) {
            inputs.add(code);inputs.add(runtime);
            var libraries=new java.util.LinkedHashSet<Path>();
            for(Class<?> type:List.of(com.baomidou.mybatisplus.core.mapper.BaseMapper.class,com.baomidou.mybatisplus.extension.plugins.pagination.Page.class,
                    com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor.class))
                libraries.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()));
            for(Path library:libraries)inputs.add(new JavaClassPathAnalysisInputLocation(library.toString(),SourceType.Library));
            result=new Analyzer(List.of(new MybatisAnalysisModule(MybatisOptions.defaults(),List.of(new MybatisPlusExtension(MybatisPlusOptions.defaults(),plugins)))),List.of()).analyze(
                    new AnalysisRequest(inputs,xml==null?List.of():List.of(new org.u80dc.jsqlint.core.resource.PathResourceSource(resources)),AnalysisOptions.defaults().withStatistics(collectStatistics)),"<sample.Fixture: void entry(sample.UserMapper)>");
        }finally{for(var input:inputs)if(input instanceof JavaClassPathAnalysisInputLocation location)location.close();}
        assertEquals(1,result.calls().size(),result.diagnostics().toString());
        var samples=result.calls().getFirst().samples();assertTrue(samples.stream().allMatch(sample->!sample.rewrites().isEmpty()),result.diagnostics().toString());if(!compare)return result;
        try(var loader=new java.net.URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},getClass().getClassLoader())) {
            var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(config,new com.baomidou.mybatisplus.core.config.GlobalConfig().setDbConfig(new com.baomidou.mybatisplus.core.config.GlobalConfig.DbConfig()));
            if(xml!=null)new org.apache.ibatis.builder.xml.XMLMapperBuilder(new java.io.ByteArrayInputStream(("<!DOCTYPE mapper PUBLIC '-//mybatis.org//DTD Mapper 3.0//EN' 'https://mybatis.org/dtd/mybatis-3-mapper.dtd'>"+xmlText).getBytes(java.nio.charset.StandardCharsets.UTF_8)),config,"mapper.xml",config.getSqlFragments()).parse();
            if(!config.hasMapper(loader.loadClass("sample.UserMapper")))config.addMapper(loader.loadClass("sample.UserMapper"));
            Object parameters=loader.loadClass("sample.Fixture").getMethod("parameter").invoke(null);
            if(parameters instanceof Map<?,?> map)parameters=new HashMap<>(map);
            var mapped=config.getMappedStatement("sample.UserMapper."+statement);
            var nativePlugins=new ArrayList<InnerInterceptor>();
            for(var plugin:plugins.plugins()) {
                if(plugin instanceof PlusPluginConfig.Tenant tenant)nativePlugins.add(new TenantLineInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler(){public net.sf.jsqlparser.expression.Expression getTenantId(){return new net.sf.jsqlparser.expression.LongValue(((Number)((SqlValue.Known)tenant.value()).value()).longValue());}public String getTenantIdColumn(){return tenant.column();}public boolean ignoreTable(String table){return tenant.ignoredTables().contains(table);}}));
                else if(plugin instanceof PlusPluginConfig.DynamicTables dynamic)nativePlugins.add(new DynamicTableNameInnerInterceptor((sql,table)->dynamic.tables().containsKey(table)?(String)((SqlValue.Known)dynamic.tables().get(table)).value():table));
                else if(plugin instanceof PlusPluginConfig.Pagination pagination){var pager=new PaginationInnerInterceptor(pagination.dialect()==PlusPluginConfig.Dialect.MYSQL?com.baomidou.mybatisplus.annotation.DbType.MYSQL:com.baomidou.mybatisplus.annotation.DbType.POSTGRE_SQL);pagination.maxLimit().ifPresent(pager::setMaxLimit);pager.setOverflow(pagination.overflow());pager.setOptimizeJoin(pagination.optimizeJoin());nativePlugins.add(pager);}
                else nativePlugins.add(new OptimisticLockerInnerInterceptor());
            }
            var expected=new ArrayList<List<Object>>();
            Object executor=java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{org.apache.ibatis.executor.Executor.class},(proxy,method,args)->{
                if(method.getName().equals("createCacheKey"))return new org.apache.ibatis.cache.CacheKey();
                if(method.getName().equals("query")){var count=(org.apache.ibatis.mapping.BoundSql)args[5];expected.add(bound(count,args[1],config));return List.of(100L);}return null;
            });
            if(mapped.getSqlCommandType()==org.apache.ibatis.mapping.SqlCommandType.UPDATE)for(var plugin:nativePlugins)plugin.beforeUpdate((org.apache.ibatis.executor.Executor)executor,mapped,parameters);
            var sql=mapped.getBoundSql(parameters);
            if(mapped.getSqlCommandType()==org.apache.ibatis.mapping.SqlCommandType.SELECT)for(var plugin:nativePlugins){assertTrue(plugin.willDoQuery((org.apache.ibatis.executor.Executor)executor,mapped,parameters,org.apache.ibatis.session.RowBounds.DEFAULT,null,sql));plugin.beforeQuery((org.apache.ibatis.executor.Executor)executor,mapped,parameters,org.apache.ibatis.session.RowBounds.DEFAULT,null,sql);}
            else {var handler=new org.apache.ibatis.executor.statement.RoutingStatementHandler((org.apache.ibatis.executor.Executor)executor,mapped,parameters,org.apache.ibatis.session.RowBounds.DEFAULT,null,sql);for(var plugin:nativePlugins)plugin.beforePrepare(handler,null,null);}
            expected.add(bound(sql,parameters,config));
            var actual=samples.stream().map(sample->{assertTrue(sample.sql().isPresent(),sample.issues()+result.diagnostics().toString());return List.<Object>of(normalize(sample.sql().orElseThrow()),sample.bindings().stream().map(SqlSample.Binding::value).toList());}).toList();
            assertEquals(expected,actual);
        }
        return result;
    }
    private static List<Object> bound(org.apache.ibatis.mapping.BoundSql sql,Object parameters,org.apache.ibatis.session.Configuration config) {
        var values=sql.getParameterMappings().stream().map(mapping->new SqlValue.Known(sql.hasAdditionalParameter(mapping.getProperty())?sql.getAdditionalParameter(mapping.getProperty()):parameters==null?null:config.getTypeHandlerRegistry().hasTypeHandler(parameters.getClass())?parameters:config.newMetaObject(parameters).getValue(mapping.getProperty()))).toList();return List.of(normalize(sql.getSql()),values);
    }
    private static String normalize(String sql){return sql.replaceAll("\\s+"," ").replaceAll("\\s*([(),=<>])\\s*","$1").strip();}
}
