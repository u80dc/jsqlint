package org.u80dc.jsqlint.core.dataflow;

import org.u80dc.jsqlint.core.RawSql;
import org.u80dc.jsqlint.core.node.SqlNode;
import java.util.Collection;

/**
 * 旧包的 SQL 模板投影入口；实现归属提取结果消费层，不属于通用程序数据流。
 * @deprecated 使用 {@link org.u80dc.jsqlint.core.template.SqlTemplateProjection}。
 * @author u80dc
 */
@Deprecated
public final class SqlTemplateProjection {
    private SqlTemplateProjection() {}
    public static SqlNode template(RawSql raw, Runnable checkpoint) {
        return org.u80dc.jsqlint.core.template.SqlTemplateProjection.template(raw, checkpoint);
    }
    public static SqlNode alternatives(Collection<? extends SqlNode> nodes) {
        return org.u80dc.jsqlint.core.template.SqlTemplateProjection.alternatives(nodes);
    }
}
