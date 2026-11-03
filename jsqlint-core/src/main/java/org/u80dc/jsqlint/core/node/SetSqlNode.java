package org.u80dc.jsqlint.core.node;

import lombok.Getter;

/**
 * SET 节点（UPDATE 语句中的 SET）
 *
 * @author u80dc
 */
@Getter
public final class SetSqlNode implements SqlNode {

    public SetSqlNode(SqlNode contents) {
        contents = java.util.Objects.requireNonNull(contents, "contents");
        this.contents = contents;
    }

    private final SqlNode contents;

    @Override
    public boolean isDynamic() {
        return contents.isDynamic();
    }

    @Override
    public SqlNodeType getType() {
        return SqlNodeType.SET;
    }
}
