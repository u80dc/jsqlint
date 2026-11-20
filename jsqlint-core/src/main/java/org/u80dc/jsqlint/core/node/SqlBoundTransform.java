package org.u80dc.jsqlint.core.node;

import java.util.Map;

/** 完整参数化 SQL 的受控变换；实现保留重演模板/输入并同步调整绑定，不执行数据库或目标代码。 */
@FunctionalInterface
public interface SqlBoundTransform {
    SqlNodeRenderer.BoundSql apply(SqlNodeRenderer.BoundSql input, Map<String,String> metadata, SqlEvaluationContext context,
                                  java.util.function.Function<SqlExpressionEvaluator.Reference,SqlValueOrigin> origins);
}
