package org.u80dc.jsqlint.mybatis.plus;

import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.node.SqlNodeRenderer.*;
import com.baomidou.mybatisplus.extension.plugins.inner.*;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.update.Update;
import java.util.*;

/** 仅实例化模型侧的固定版本插件；参数/表名 Handler 由冻结配置提供，不执行项目 Handler。 */
final class PlusSqlTransform implements SqlBoundTransform {
    record PageRequest(long current,long size,boolean searchCount,boolean optimizeCount,boolean optimizeJoin,
                       Optional<Long> maxLimit,String currentKey,String sizeKey) {}
    private final PlusPluginConfig config;
    private final Optional<PageRequest> page;
    private final boolean pageExpected,count;
    PlusSqlTransform(PlusPluginConfig config,Optional<PageRequest> page,boolean pageExpected,boolean count){this.config=config;this.page=page;this.pageExpected=pageExpected;this.count=count;}
    @Override public BoundSql apply(BoundSql input,Map<String,String> metadata,SqlEvaluationContext context,
                                   java.util.function.Function<SqlExpressionEvaluator.Reference,SqlValueOrigin> origins) {
        BoundSql current=input;
        var ordered=new ArrayList<>(config.plugins());
        if(metadata.getOrDefault("command","").equals("update"))ordered.sort(Comparator.comparingInt(plugin->plugin instanceof PlusPluginConfig.OptimisticLock?0:1));
        for(var plugin:ordered) {
            context.check();String id=plugin instanceof PlusPluginConfig.Tenant?"tenant":plugin instanceof PlusPluginConfig.DynamicTables?"dynamic-table":plugin instanceof PlusPluginConfig.Pagination?count?"pagination-count":"pagination":"optimistic-lock";
            if(!current.rendered().complete())break;
            var before=current;
            try {
                String version=metadata.getOrDefault("pluginFrameworkVersion",metadata.getOrDefault("frameworkVersion","unknown"));
                if(!version.equals("3.5.16"))throw new IllegalArgumentException("Unsupported plugin framework version: "+version+"; supported version is 3.5.16");
                if(before.rendered().sql().orElseThrow().length()>Math.min(context.renderBudgets().length(),200000))throw new IllegalArgumentException("Plugin SQL text budget exceeded");
                var bindings=new ArrayList<>(before.bindings());String sql=PlusSqlParameters.tag(before.rendered().sql().orElseThrow(),bindings.size());
                if(plugin instanceof PlusPluginConfig.Tenant tenant) {
                    var interceptor=new TenantLineInnerInterceptor(new TenantLineHandler(){
                        public Expression getTenantId(){return literal(tenant.value());}
                        public String getTenantIdColumn(){return tenant.column();}
                        public boolean ignoreTable(String table){return tenant.ignoredTables().contains(table);}
                    });sql=interceptor.parserSingle(sql,null);
                } else if(plugin instanceof PlusPluginConfig.DynamicTables dynamic) {
                    sql=new DynamicTableNameInnerInterceptor((original,table)->{
                        var value=dynamic.tables().get(table);if(value==null)return table;
                        if(value instanceof SqlValue.Known known&&known.value() instanceof String name&&!name.isBlank())return name;
                        throw new IllegalArgumentException("Dynamic table is unresolved: "+table);
                    }).changeTable(sql);
                    CCJSqlParserUtil.parse(sql);
                } else if(plugin instanceof PlusPluginConfig.Pagination pagination) {
                    if(pageExpected&&page.isEmpty())throw new IllegalArgumentException("Page facts are unresolved");
                    if(page.isPresent()&&isSelect(metadata,sql)) {
                        var request=page.get();var pager=new Page<Object>(request.current(),request.size(),request.searchCount());
                        pager.setOptimizeCountSql(request.optimizeCount());pager.setOptimizeJoinOfCountSql(request.optimizeJoin());request.maxLimit().ifPresent(pager::setMaxLimit);
                        var interceptor=pager(pagination);
                        if(count)sql=interceptor.autoCountSql(pager,sql);
                        else {
                            if(pagination.overflow()&&request.searchCount()&&request.current()>1)throw new IllegalArgumentException("Pagination overflow may reset current after an unknown count result");
                            var configuration=new com.baomidou.mybatisplus.core.MybatisConfiguration();
                            var mappings=new ArrayList<org.apache.ibatis.mapping.ParameterMapping>();
                            for(int position=0;position<bindings.size();position++)mappings.add(new org.apache.ibatis.mapping.ParameterMapping.Builder(configuration,"binding"+(position+1),Object.class).build());
                            var bound=new org.apache.ibatis.mapping.BoundSql(configuration,sql,mappings,Map.of("page",pager));
                            var statement=new org.apache.ibatis.mapping.MappedStatement.Builder(configuration,"jsqlint.model.select",parameter->bound,org.apache.ibatis.mapping.SqlCommandType.SELECT).build();
                            int original=bindings.size();
                            interceptor.beforeQuery(null,statement,Map.of("page",pager),org.apache.ibatis.session.RowBounds.DEFAULT,null,bound);
                            for(int index=original;index<bound.getParameterMappings().size();index++) {
                                String name=bound.getParameterMappings().get(index).getProperty();Object value=bound.getAdditionalParameter(name);
                                var dependencies=new ArrayList<SqlValueOrigin>();dependencies.add(origins.apply(new SqlExpressionEvaluator.Reference(request.currentKey(),List.of())));dependencies.add(origins.apply(new SqlExpressionEvaluator.Reference(request.sizeKey(),List.of())));
                                request.maxLimit().or(()->pagination.maxLimit()).ifPresent(limit->dependencies.add(new SqlValueOrigin.Literal(new SqlValue.Known(limit))));
                                var origin=new SqlValueOrigin.Expression("Pagination bound: "+name,dependencies);
                                bindings.add(new BoundParameter(bindings.size()+1,name,"long","",new SqlValue.Known(value),origin));
                            }
                            sql=PlusSqlParameters.tagNew(bound.getSql(),original);
                        }
                    }
                } else if(plugin instanceof PlusPluginConfig.OptimisticLock&&metadata.getOrDefault("command","").equals("update")) {
                    String column=metadata.get("versionColumn");
                    if(column!=null) {
                        if(!metadata.getOrDefault("framework","").equals("mybatis-plus")||!metadata.getOrDefault("method","").contains(" updateById("))throw new IllegalArgumentException("Only generated updateById optimistic locking is currently modeled");
                        sql=optimistic(sql,bindings,column,metadata.get("logicDeleteColumn"));
                    }
                }
                var restored=PlusSqlParameters.restore(sql,bindings);
                current=result(before,Optional.of(restored.sql()),restored.bindings(),id,List.of());
                if(count&&plugin instanceof PlusPluginConfig.Pagination)break;
            } catch(Exception | LinkageError failure) {
                if(failure instanceof RuntimeException runtime)SqlEvaluationContext.rethrowControl(runtime);
                var diagnostic=new SqlDiagnostic("SQL_PLUGIN_UNRESOLVED",id+": "+Objects.toString(failure.getMessage(),failure.getClass().getSimpleName()));
                current=result(before,Optional.empty(),before.bindings(),id,List.of(diagnostic));break;
            }
        }
        return current;
    }
    static PaginationInnerInterceptor pager(PlusPluginConfig.Pagination config) {
        var result=new PaginationInnerInterceptor(config.dialect()==PlusPluginConfig.Dialect.MYSQL?com.baomidou.mybatisplus.annotation.DbType.MYSQL:com.baomidou.mybatisplus.annotation.DbType.POSTGRE_SQL);
        config.maxLimit().ifPresent(result::setMaxLimit);result.setOverflow(config.overflow());result.setOptimizeJoin(config.optimizeJoin());return result;
    }
    private static boolean isSelect(Map<String,String> metadata,String sql){return metadata.getOrDefault("command","").startsWith("select")||sql.stripLeading().toUpperCase(Locale.ROOT).startsWith("SELECT");}
    private static String optimistic(String sql,List<BoundParameter> bindings,String column,String logicColumn)throws Exception {
        if(!(CCJSqlParserUtil.parse(sql) instanceof Update update))throw new IllegalArgumentException("Optimistic lock requires UPDATE");
        JdbcParameter parameter=null;
        for(var set:update.getUpdateSets())for(int index=0;index<set.getColumns().size();index++)if(set.getColumns().get(index).getColumnName().equals(column)) {
            if(parameter!=null||!(set.getValues().get(index) instanceof JdbcParameter value))throw new IllegalArgumentException("Ambiguous/computed version SET expression");parameter=value;
        }
        if(parameter==null)return sql;
        int index=parameter.getIndex()-1;var original=bindings.get(index);
        if(!(original.value() instanceof SqlValue.Known known))throw new IllegalArgumentException("Original version is unknown");
        Object value=known.value();if(value==null)return sql;Object next;
        if(value instanceof Integer number)next=number+1;else if(value instanceof Long number)next=number+1L;else throw new IllegalArgumentException("Temporal/custom version increments are not statically known");
        bindings.set(index,new BoundParameter(index+1,original.expression(),original.type(),original.options(),new SqlValue.Known(next),
                new SqlValueOrigin.Expression("Optimistic version + 1",List.of(original.origin(),new SqlValueOrigin.Literal(new SqlValue.Known(1))))));
        int position=bindings.size()+1;bindings.add(new BoundParameter(position,"MP_OPTLOCK_VERSION_ORIGINAL",original.type(),original.options(),original.value(),original.origin()));
        var old=new JdbcParameter();old.setIndex(position);old.setUseFixedIndex(true);
        var predicate=new EqualsTo(new Column(column),old);
        if(logicColumn!=null&&update.getWhere() instanceof AndExpression conjunction)
            update.setWhere(new AndExpression(new AndExpression(conjunction.getLeftExpression(),predicate),conjunction.getRightExpression()));
        else update.setWhere(update.getWhere()==null?predicate:new AndExpression(update.getWhere(),predicate));return update.toString();
    }
    private static Expression literal(SqlValue value) {
        if(!(value instanceof SqlValue.Known known))throw new IllegalArgumentException("Tenant ID is unresolved");
        Object raw=known.value();if(raw==null)return new NullValue();if(raw instanceof String text)return new StringValue(text.replace("'","''"));
        if(raw instanceof Byte||raw instanceof Short||raw instanceof Integer||raw instanceof Long||raw instanceof java.math.BigInteger)return new LongValue(raw.toString());
        throw new IllegalArgumentException("Unsupported tenant ID type");
    }
    private static BoundSql result(BoundSql before,Optional<String> sql,List<BoundParameter> bindings,String id,List<SqlDiagnostic> problems) {
        var steps=new ArrayList<>(before.rewrites());steps.add(new SqlRewriteStep(id,before.rendered().sql(),sql,before.bindings(),bindings,problems));
        var diagnostics=new ArrayList<>(before.rendered().diagnostics());diagnostics.addAll(problems);
        var rendered=new RenderedSql(sql,before.rendered().residual(),before.rendered().parameters(),diagnostics,before.rendered().recoveryMode());
        return new BoundSql(rendered,bindings,steps);
    }
}
