package cn.jnu.zhangcs.jsqlint.analysis.extractor;

import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.ValueModel;

import java.util.List;
import java.util.Objects;

public record SqlValue(String rawSql, ValueModel model, List<String> diagnostics) {

    public SqlValue {
        Objects.requireNonNull(rawSql, "rawSql");
        Objects.requireNonNull(model, "model");
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
