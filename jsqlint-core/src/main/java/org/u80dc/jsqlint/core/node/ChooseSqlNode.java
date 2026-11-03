package org.u80dc.jsqlint.core.node;

import lombok.Getter;

import java.util.List;

/**
 * CHOOSE (WHEN/OTHERWISE) 节点
 *
 * @author u80dc
 */
@Getter
public final class ChooseSqlNode implements SqlNode {

    public ChooseSqlNode(List<IfSqlNode> whenContents, SqlNode otherwiseContents) {
        whenContents = List.copyOf(whenContents);
        if (whenContents.isEmpty()) {
            throw new IllegalArgumentException("choose requires at least one when branch");
        }
        this.whenContents = whenContents;
        this.otherwiseContents = otherwiseContents;
    }

    private final List<IfSqlNode> whenContents;

    private final SqlNode otherwiseContents;

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
