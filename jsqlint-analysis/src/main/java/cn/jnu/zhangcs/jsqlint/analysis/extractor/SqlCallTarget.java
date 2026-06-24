package cn.jnu.zhangcs.jsqlint.analysis.extractor;

import cn.jnu.zhangcs.jsqlint.analysis.SqlSourceType;

import java.util.List;
import java.util.Map;

/**
 * A framework call site whose argument carries SQL text.
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
