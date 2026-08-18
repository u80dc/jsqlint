package cn.jnu.zhangcs.jsqlint.analysis.extractor;

import java.util.List;

/**
 * SQL 提取结果
 * <p>
 * 封装某个 {@link SqlExtractor} 在一次提取过程中得到的 SQL 语句列表，以及过程中产生的诊断信息。
 * </p>
 *
 * @param diagnostics 提取过程中的诊断信息（警告、错误等）
 * @param statements  提取到的 SQL 语句列表
 * @author zhangcs
 */
public record SqlExtractionResult(
        List<SqlStatement> statements,
        List<String> diagnostics) {

    /**
     * 紧凑构造器：对入参做非空校验与不可变拷贝。
     */
    public SqlExtractionResult {
        statements = statements == null ? List.of() : List.copyOf(statements);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    /**
     * 创建一个空的提取结果（无语句、无诊断）。
     *
     * @return 空结果
     */
    public static SqlExtractionResult empty() {
        return new SqlExtractionResult(List.of(), List.of());
    }
}
