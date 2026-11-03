package org.u80dc.jsqlint.core.node;

import lombok.Getter;

/**
 * 静态文本节点（不包含动态表达式）
 *
 * @author u80dc
 */
@Getter
public final class StaticTextSqlNode implements SqlNode {

    private final String text;

    public StaticTextSqlNode(String text) {
        this.text = java.util.Objects.requireNonNull(text, "text");
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
