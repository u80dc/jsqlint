package org.u80dc.jsqlint.core.node;

import java.util.Objects;

/**
 * 静态文本节点（不包含动态表达式）
 *
 * @author u80dc
 */
public record StaticTextSqlNode(String text) implements SqlNode {

    public StaticTextSqlNode {
        Objects.requireNonNull(text, "text");
    }

    @Override
    public boolean isDynamic() {
        return false;
    }

    @Override
    public SqlNodeType getType() {
        return SqlNodeType.STATIC_TEXT;
    }
}
