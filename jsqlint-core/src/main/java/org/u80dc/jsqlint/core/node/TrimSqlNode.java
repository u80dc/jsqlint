package org.u80dc.jsqlint.core.node;

import lombok.Getter;

/**
 * TRIM 节点
 *
 * @author u80dc
 */
@Getter
public final class TrimSqlNode implements SqlNode {

    public TrimSqlNode(String prefix, String suffix, String prefixOverrides, String suffixOverrides, SqlNode contents) {
        prefix = prefix == null ? "" : prefix;
        suffix = suffix == null ? "" : suffix;
        prefixOverrides = prefixOverrides == null ? "" : prefixOverrides;
        suffixOverrides = suffixOverrides == null ? "" : suffixOverrides;
        contents = java.util.Objects.requireNonNull(contents, "contents");
        this.prefix = prefix;
        this.suffix = suffix;
        this.prefixOverrides = prefixOverrides;
        this.suffixOverrides = suffixOverrides;
        this.contents = contents;
    }

    private final String prefix;

    private final String suffix;

    private final String prefixOverrides;

    private final String suffixOverrides;

    private final SqlNode contents;

    @Override
    public boolean isDynamic() {
        return contents.isDynamic();
    }

    @Override
    public SqlNodeType getType() {
        return SqlNodeType.TRIM;
    }
}
