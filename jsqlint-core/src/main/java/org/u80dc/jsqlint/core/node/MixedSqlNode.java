package org.u80dc.jsqlint.core.node;

import lombok.Getter;

import java.util.List;

/**
 * 混合内容节点（包含多个子节点）
 *
 * @author u80dc
 */
@Getter
public final class MixedSqlNode implements SqlNode {

    public MixedSqlNode(List<SqlNode> contents) {
        contents = List.copyOf(contents);
        this.contents = contents;
    }

    private final List<SqlNode> contents;

    @Override
    public boolean isDynamic() {
        return contents.stream().anyMatch(SqlNode::isDynamic);
    }

    @Override
    public SqlNodeType getType() {
        return SqlNodeType.MIXED;
    }
}
