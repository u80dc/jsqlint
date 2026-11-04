package org.u80dc.jsqlint.core.node;

import java.util.Objects;

/**
 * FOREACH 循环节点
 *
 * @param index 空字符串表示不绑定索引，保留外层同名变量
 * @author u80dc
 */
public record ForEachSqlNode(String collection, String item, String index, String open, String close,
                             String separator, SqlNode contents, boolean nullable, boolean skipBlank) implements SqlNode {

    public ForEachSqlNode(String collection, String item, String index, String open, String close, String separator, SqlNode contents) {
        this(collection, item, index, open, close, separator, contents, false, true);
    }

    /**
     * nullable 仅允许已知 null 作为空集合；skipBlank 控制是否过滤空白迭代片段。
     * 精确字符串拼接应使用 skipBlank=false，防止丢失空格或换行。
     * 未指定 index 时不绑定索引变量；item 与 index 可以同名，同名时元素值优先。
     */
    public ForEachSqlNode {
        collection = requireText(collection, "collection");
        item = item == null || item.isBlank() ? "item" : item;
        index = index == null || index.isBlank() ? "" : index;
        open = open == null ? "" : open;
        close = close == null ? "" : close;
        separator = separator == null ? "" : separator;
        Objects.requireNonNull(contents, "contents");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    @Override
    public boolean isDynamic() {
        return true;
    }

    @Override
    public SqlNodeType getType() {
        return SqlNodeType.FOREACH;
    }
}
