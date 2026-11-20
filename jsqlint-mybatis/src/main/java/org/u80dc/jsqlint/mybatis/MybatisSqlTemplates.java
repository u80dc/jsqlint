package org.u80dc.jsqlint.mybatis;

import org.u80dc.jsqlint.core.SqlDefinition;
import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.spi.SourceLocation;
import java.util.*;

/** 将扩展生成的 MyBatis SQL/script 转为统一定义，复用语言解析、OGNL 与绑定选项规则。 */
public final class MybatisSqlTemplates {
    private MybatisSqlTemplates() {}
    /** 复用标准 ParamNameResolver，扩展仅替换已建模实参的来源，不自行猜测参数名。 */
    public static org.u80dc.jsqlint.core.spi.SqlTraceSource parameters(SqlDefinition definition,
            org.u80dc.jsqlint.mybatis.spi.MapperMethodContext context, org.u80dc.jsqlint.core.dataflow.FlowPoint point,
            MybatisOptions options, Map<Integer,org.u80dc.jsqlint.core.spi.TemplateSqlInput.Parameter> modeled) {
        var site=point.invocation(context.view()).orElseThrow();
        return new MapperParameterResolver(options).resolve(definition,context.method(),context.view(),
                index->modeled.containsKey(index)?modeled.get(index):new org.u80dc.jsqlint.core.spi.TemplateSqlInput.Parameter(
                        point.value(site.invocation().getArg(index)),context.method().getParameterType(index).toString()),modeled.keySet());
    }
    public static SqlDefinition definition(String id, SourceLocation location, String sql,
                                           MybatisOptions options, Map<String, String> metadata, List<String> issues) {
        var evaluator = new MybatisExpressionEvaluator(Objects.requireNonNull(options));
        var problems = new ArrayList<>(issues);
        SqlNode template = ValueSqlNode.raw("unresolvedSqlDefinition");
        try { template = MybatisSqlAdapter.annotation(sql, options); evaluator.validate(template); }
        catch (RuntimeException error) { problems.add(Objects.toString(error.getMessage(), error.getClass().getSimpleName())); }
        return new SqlDefinition(id, location, template, evaluator, metadata, problems);
    }
}
