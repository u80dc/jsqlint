package org.u80dc.jsqlint.core.node;

import java.util.List;
import java.util.Objects;

/**
 * 混合内容节点（包含多个子节点）
 *
 * @author u80dc
 */
public record MixedSqlNode(List<SqlNode> contents, String separator) implements SqlNode {
    public MixedSqlNode(List<SqlNode> contents) { this(contents, ""); }

    public MixedSqlNode {
        contents = List.copyOf(contents);
        Objects.requireNonNull(separator);
    }

    @Override
    public boolean isDynamic() {
        return SqlNodeGraphs.isDynamic(this);
    }

    @Override
    public SqlNodeType getType() {
        return SqlNodeType.MIXED;
    }
}
