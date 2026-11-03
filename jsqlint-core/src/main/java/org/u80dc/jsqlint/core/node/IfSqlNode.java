package org.u80dc.jsqlint.core.node;

import lombok.Getter;

/**
 * IF 条件节点
 *
 * @author u80dc
 */
@Getter
public final class IfSqlNode implements SqlNode {

    public IfSqlNode(String test, SqlNode contents) {
        test = requireText(test, "test");
        contents = java.util.Objects.requireNonNull(contents, "contents");
        this.test = test;
        this.contents = contents;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private final String test;

    private final SqlNode contents;

    @Override
    public boolean isDynamic() {
        return true;
    }

    @Override
    public SqlNodeType getType() {
        return SqlNodeType.IF;
    }
}
