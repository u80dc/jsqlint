package cn.jnu.zhangcs.jsqlint.analysis.extractor;

import cn.jnu.zhangcs.jsqlint.analysis.SqlSourceType;

import java.util.List;
import java.util.Map;

/**
 * SQL 调用目标
 * <p>
 * 描述一个“参数携带 SQL 文本”的框架调用点，例如 {@code JdbcTemplate.query(sql, ...)}
 * 或 {@code PreparedStatement.execute(sql)}。记录其类别、展示名、SQL 参数所在位置、
 * 来源类型、标签及扩展属性，供调用点扫描时识别与归类。
 * </p>
 *
 * @param kind 类别标识（如 jdbc-call、jdbcTemplate-query 等）
 * @param displayName 展示名称
 * @param extra 扩展属性
 * @param sourceType 来源类型
 * @param sqlArgumentIndex SQL 文本所在的参数下标
 * @param tags 自定义标签
 * @author zhangcs
 */
public record SqlCallTarget(
        String kind,
        String displayName,
        int sqlArgumentIndex,
        SqlSourceType sourceType,
        List<String> tags,
        Map<String, Object> extra) {

    public SqlCallTarget {
        kind = kind == null || kind.isBlank() ? "sql-call" : kind;
        displayName = displayName == null || displayName.isBlank() ? kind : displayName;
        sourceType = sourceType == null ? SqlSourceType.PARAM_SQL : sourceType;
        tags = tags == null || tags.isEmpty() ? List.of(kind) : List.copyOf(tags);
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }

    public static SqlCallTarget of(String kind, String displayName, int sqlArgumentIndex, List<String> tags) {
        return new SqlCallTarget(kind, displayName, sqlArgumentIndex, SqlSourceType.PARAM_SQL, tags, Map.of());
    }
}
