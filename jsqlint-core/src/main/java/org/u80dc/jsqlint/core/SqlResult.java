package org.u80dc.jsqlint.core;

import org.u80dc.jsqlint.core.node.SqlNode;
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
}
