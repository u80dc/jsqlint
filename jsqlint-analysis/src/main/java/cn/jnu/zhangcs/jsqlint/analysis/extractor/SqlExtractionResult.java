package cn.jnu.zhangcs.jsqlint.analysis.extractor;

import java.util.List;

public record SqlExtractionResult(
        List<SqlStatement> statements,
        List<String> diagnostics) {

    public SqlExtractionResult {
        statements = statements == null ? List.of() : List.copyOf(statements);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public static SqlExtractionResult empty() {
        return new SqlExtractionResult(List.of(), List.of());
    }
}
