package cn.jnu.zhangcs.jsqlint.analysis.node;

import lombok.Getter;

/**
 * 静态文本节点（不包含动态表达式）
 *
 * @author zhangcs
 */
@Getter
public class StaticTextSqlNode implements SqlNode {

    private final String text;

    public StaticTextSqlNode(String text) {
        this.text = text;
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