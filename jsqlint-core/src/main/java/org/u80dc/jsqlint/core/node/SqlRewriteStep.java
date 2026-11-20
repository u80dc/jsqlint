package org.u80dc.jsqlint.core.node;

import java.util.*;

/** 按顺序冻结的 SQL 与绑定改写证据，未知结果用空 afterSql 表示。 */
public record SqlRewriteStep(String id, Optional<String> beforeSql, Optional<String> afterSql,
                             List<SqlNodeRenderer.BoundParameter> beforeBindings,
                             List<SqlNodeRenderer.BoundParameter> afterBindings, List<SqlDiagnostic> diagnostics) {
    public SqlRewriteStep {
        Objects.requireNonNull(id);Objects.requireNonNull(beforeSql);Objects.requireNonNull(afterSql);
        beforeBindings=List.copyOf(beforeBindings);afterBindings=List.copyOf(afterBindings);diagnostics=List.copyOf(diagnostics);
    }
}
