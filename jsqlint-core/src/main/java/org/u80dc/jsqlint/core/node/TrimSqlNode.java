package org.u80dc.jsqlint.core.node;

import java.util.Objects;

/**
 * TRIM 节点
 *
 * @author u80dc
 */
public record TrimSqlNode(String prefix, String suffix, String prefixOverrides, String suffixOverrides,
                          SqlNode contents) implements SqlNode {

    public TrimSqlNode {
        prefix = prefix == null ? "" : prefix;
        suffix = suffix == null ? "" : suffix;
        prefixOverrides = prefixOverrides == null ? "" : prefixOverrides;
        suffixOverrides = suffixOverrides == null ? "" : suffixOverrides;
        Objects.requireNonNull(contents, "contents");
    }

    @Override
    public boolean isDynamic() {
        return SqlNodeGraphs.isDynamic(this);
    }

    @Override
    public SqlNodeType getType() {
        return SqlNodeType.TRIM;
    }
}
