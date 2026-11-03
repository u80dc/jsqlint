package org.u80dc.jsqlint.core.node;

import lombok.Getter;

/**
 * BIND节点，对应变量声明`<bind></bind>`标签
 *
 * @author u80dc
 */
@Getter
public final class BindSqlNode implements SqlNode {

    public BindSqlNode(String name, String expression) {
        name = requireText(name, "name");
        expression = requireText(expression, "expression");
        this.name = name;
        this.expression = expression;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private final String name;

    private final String expression;

    @Override
    public boolean isDynamic() {
        return true;
    }

    @Override
    public SqlNodeType getType() {
        return SqlNodeType.BIND;
    }
}
