package org.u80dc.jsqlint.core.node;

import java.util.Objects;

/**
 * IF 条件节点
 *
 * @author u80dc
 */
public record IfSqlNode(String test, SqlNode contents) implements SqlNode {

    public IfSqlNode {
        test = requireText(test, "test");
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
        return SqlNodeType.IF;
    }
}
