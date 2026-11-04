package org.u80dc.jsqlint.core.node;

import java.util.List;

/**
 * 混合内容节点（包含多个子节点）
 *
 * @author u80dc
 */
public record MixedSqlNode(List<SqlNode> contents) implements SqlNode {

    public MixedSqlNode {
        contents = List.copyOf(contents);
    }

    @Override
    public boolean isDynamic() {
        return contents.stream().anyMatch(SqlNode::isDynamic);
    }

    @Override
    public SqlNodeType getType() {
        return SqlNodeType.MIXED;
    }
}
