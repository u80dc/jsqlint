package org.u80dc.jsqlint.core.node;

import lombok.Getter;

/**
 * 尚未拆分占位符的模板文本。新代码可通过 SqlNodeBuilder.templateText
 * 直接得到静态文本和 ValueSqlNode；表达式不限定为 OGNL。
 *
 * @author u80dc
 */
@Getter
public final class TextSqlNode implements SqlNode {

    private final String text;

    public TextSqlNode(String text) {
        this.text = java.util.Objects.requireNonNull(text, "text");
    }

    @Override
    public boolean isDynamic() {
        return true;
    }

    @Override
    public SqlNodeType getType() {
        return SqlNodeType.TEXT;
    }
}
