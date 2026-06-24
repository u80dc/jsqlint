package cn.jnu.zhangcs.jsqlint.analysis.node;

import lombok.Getter;

/**
 * 包含 OGNL 表达式的文本节点
 */
@Getter
public class TextSqlNode implements SqlNode {

    private final String text;

    public TextSqlNode(String text) {
        this.text = text;
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