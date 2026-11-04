package org.u80dc.jsqlint.core.node;

import java.util.List;

/**
 * CHOOSE (WHEN/OTHERWISE) 节点
 *
 * @author u80dc
 */
public record ChooseSqlNode(List<IfSqlNode> whenContents, SqlNode otherwiseContents) implements SqlNode {

    public ChooseSqlNode {
        whenContents = List.copyOf(whenContents);
        if (whenContents.isEmpty()) {
            throw new IllegalArgumentException("choose requires at least one when branch");
        }
    }

    @Override
    public boolean isDynamic() {
        boolean dynamic = whenContents.stream().anyMatch(SqlNode::isDynamic);
        if (otherwiseContents != null) {
            dynamic = dynamic || otherwiseContents.isDynamic();
        }
        return dynamic;
    }

    @Override
    public SqlNodeType getType() {
        return SqlNodeType.CHOOSE;
    }
}
