package org.u80dc.jsqlint.core.node;

/**
 * BIND节点，对应变量声明`<bind></bind>`标签
 *
 * @author u80dc
 */
public record BindSqlNode(String name, String expression) implements SqlNode {

    public BindSqlNode {
        name = requireText(name, "name");
        expression = requireText(expression, "expression");
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
        return SqlNodeType.BIND;
    }
}
