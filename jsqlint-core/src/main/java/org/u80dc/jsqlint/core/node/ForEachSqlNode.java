package org.u80dc.jsqlint.core.node;

import lombok.Getter;

/**
 * FOREACH 循环节点
 *
 * @author u80dc
 */
@Getter
public final class ForEachSqlNode implements SqlNode {

    public ForEachSqlNode(String collection, String item, String index, String open, String close, String separator, SqlNode contents) {
        this(collection, item, index, open, close, separator, contents, false, true);
    }

    /**
     * nullable 仅允许已知 null 作为空集合；skipBlank 控制是否过滤空白迭代片段。
     * 精确字符串拼接应使用 skipBlank=false，防止丢失空格或换行。
     */
    public ForEachSqlNode(String collection, String item, String index, String open, String close, String separator,
                          SqlNode contents, boolean nullable, boolean skipBlank) {
        collection = requireText(collection, "collection");
        item = item == null || item.isBlank() ? "item" : item;
        index = index == null || index.isBlank() ? "index" : index;
        open = open == null ? "" : open;
        close = close == null ? "" : close;
        separator = separator == null ? "" : separator;
        contents = java.util.Objects.requireNonNull(contents, "contents");
        this.collection = collection;
        this.item = item;
        this.index = index;
        this.open = open;
        this.close = close;
        this.separator = separator;
        this.contents = contents;
        this.nullable = nullable;
        this.skipBlank = skipBlank;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private final String collection;

    private final String item;

    private final String index;

    private final String open;

    private final String close;

    private final String separator;

    private final SqlNode contents;

    private final boolean nullable;

    private final boolean skipBlank;

    @Override
    public boolean isDynamic() {
        return true;
    }

    @Override
    public SqlNodeType getType() {
        return SqlNodeType.FOREACH;
    }
}
