package org.u80dc.jsqlint.core.dataflow;

import org.u80dc.jsqlint.core.RawSql;
import org.u80dc.jsqlint.core.SqlRecoveryBudgets;

/**
 * 旧包的 SQL 恢复入口；实现归属 SQL 模板恢复层。
 * @deprecated 使用 {@link org.u80dc.jsqlint.core.template.RawSqlBuilder}。
 */
@Deprecated
public final class RawSqlBuilder {
    private RawSqlBuilder() {}
    public static RawSql build(ValueTrace trace, Runnable checkpoint) {
        return org.u80dc.jsqlint.core.template.RawSqlBuilder.build(trace, checkpoint);
    }
    public static RawSql build(ValueTrace trace, SqlRecoveryBudgets budgets, Runnable checkpoint) {
        return org.u80dc.jsqlint.core.template.RawSqlBuilder.build(trace, budgets, checkpoint);
    }
}
