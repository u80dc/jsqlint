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
 * @param bindings 语句对象上 setXxx 绑定调用的记录（参数下标 + 绑定值模型 + 依赖），
 *                 供组装阶段生成常量绑定推断与参数下标标注
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
        List<SqlExtractionSupport.BoundParameter> bindings,
        List<String> diagnostics,
        AnalyzeResult.Metadata metadata) {

    public SqlStatement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(rawSql, "rawSql");
        Objects.requireNonNull(sqlNode, "sqlNode");
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        metadata = metadata == null ? AnalyzeResult.Metadata.empty() : metadata;
    }

    /**
     * 兼容无绑定记录的旧签名。
     */
    public SqlStatement(
            String id,
            StatementType type,
            SqlSourceType sourceType,
            SourceLocation location,
            String rawSql,
            SqlNode sqlNode,
            List<MethodParameterRef> parameters,
            List<String> diagnostics,
            AnalyzeResult.Metadata metadata) {
        this(id, type, sourceType, location, rawSql, sqlNode, parameters, List.of(), diagnostics, metadata);
    }

    public boolean dynamic() {
        return sqlNode.isDynamic();
    }
}
