package org.u80dc.jsqlint.core.node;

import java.util.Objects;

/**
 * WHERE 节点
 *
 * @author u80dc
 */
public record WhereSqlNode(SqlNode contents) implements SqlNode {

    public WhereSqlNode {
        Objects.requireNonNull(contents, "contents");
    }

    @Override
    public boolean isDynamic() {
        return SqlNodeGraphs.isDynamic(this);
    }

    @Override
    public SqlNodeType getType() {
        return SqlNodeType.WHERE;
    }
}
