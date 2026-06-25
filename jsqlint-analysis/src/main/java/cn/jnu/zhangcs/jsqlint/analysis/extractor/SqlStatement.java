package cn.jnu.zhangcs.jsqlint.analysis.extractor;

import cn.jnu.zhangcs.jsqlint.analysis.AnalyzeResult;
import cn.jnu.zhangcs.jsqlint.analysis.SourceLocation;
import cn.jnu.zhangcs.jsqlint.analysis.SqlSourceType;
import cn.jnu.zhangcs.jsqlint.analysis.StatementType;
import cn.jnu.zhangcs.jsqlint.analysis.node.SqlNode;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.MethodParameterRef;

import java.util.List;
import java.util.Objects;

/**
 * 从源代码或资源中提取的 SQL 定义.
 *
 * @author zhangcs
 */
public record SqlStatement(
        String id,
        StatementType type,
        SqlSourceType sourceType,
        SourceLocation location,
        String rawSql,
        SqlNode sqlNode,
        List<MethodParameterRef> parameters,
        List<String> diagnostics,
        AnalyzeResult.Metadata metadata) {

    public SqlStatement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(rawSql, "rawSql");
        Objects.requireNonNull(sqlNode, "sqlNode");
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        metadata = metadata == null ? AnalyzeResult.Metadata.empty() : metadata;
    }

    public boolean dynamic() {
        return sqlNode.isDynamic();
    }
}
