package org.u80dc.jsqlint.core.node;

import lombok.Getter;

/**
 * WHERE 节点
 *
 * @author u80dc
 */
@Getter
public final class WhereSqlNode implements SqlNode {

    private final SqlNode contents;

    public WhereSqlNode(SqlNode contents) {
        contents = java.util.Objects.requireNonNull(contents, "contents");
        this.contents = contents;
    }

    @Override
    public boolean isDynamic() {
        return contents.isDynamic();
    }

    @Override
    public SqlNodeType getType() {
        return SqlNodeType.WHERE;
    }
}
