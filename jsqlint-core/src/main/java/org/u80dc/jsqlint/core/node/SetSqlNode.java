package org.u80dc.jsqlint.core.node;

import java.util.Objects;

/**
 * SET 节点（UPDATE 语句中的 SET）
 *
 * @author u80dc
 */
public record SetSqlNode(SqlNode contents) implements SqlNode {

    public SetSqlNode {
        Objects.requireNonNull(contents, "contents");
    }

    @Override
    public boolean isDynamic() {
        return contents.isDynamic();
    }

    @Override
    public SqlNodeType getType() {
        return SqlNodeType.SET;
    }
}
