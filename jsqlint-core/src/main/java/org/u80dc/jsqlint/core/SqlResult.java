package org.u80dc.jsqlint.core;

import org.u80dc.jsqlint.core.node.SqlNode;
import org.u80dc.jsqlint.core.node.SqlEvaluationContext;
import org.u80dc.jsqlint.core.spi.SourceLocation;

import java.util.List;

/**
 * 所有模块共用的 SQL 结果；资源模板无需伪造 Java 调用点或传播链。
 *
 * @author u80dc
 */
public sealed interface SqlResult permits SqlExtraction, SqlTemplateResult {
    String moduleId();

    SourceLocation location();

    List<SqlNode> templates();

    List<RawSql.Variant> rawSqls();

    SqlAssessment assessment();

    /** 从保存的模板快照/组合字符串投影样例，不重演表达式，也不额外施加默认渲染容量。 */
    default List<SqlSample> samples() { return SqlSampleRenderer.render(this); }
    /** 显式派生使用本次上下文的容量及取消策略；不修改保存的模型。 */
    default List<SqlSample> samples(SqlEvaluationContext context) { return SqlSampleRenderer.render(this, context); }
}
