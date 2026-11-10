package org.u80dc.jsqlint.core.node;

import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.Objects;

/**
 * 将表达式结果输出到 SQL：原始拼接和绑定参数是不同语义。
 * 表达式的语言由 SqlExpressionEvaluator 决定，节点不依赖 JDBC 或 MyBatis。
 * 保留私有构造器和工厂方法以约束字段组合，按全部语义字段比较相等性。
 *
 * @author u80dc
 */
@Getter
@EqualsAndHashCode
public final class ValueSqlNode implements SqlNode {
    private final String expression;
    private final Kind kind;
    /**
     * 仅用于已知 null；未知值始终保留节点。
     */
    private final String nullText;
    /**
     * 保留模板中的绑定选项，供适配层解释；core 的样例渲染不执行类型处理器。
     */
    private final String bindingOptions;
    /** 使用位置提供的 Java 类型提示；空字符串表示由输入值或传播结果确定。 */
    private final String valueType;
    private ValueSqlNode(String expression, Kind kind, String nullText, String bindingOptions) {
        this(expression, kind, nullText, bindingOptions, "");
    }
    private ValueSqlNode(String expression, Kind kind, String nullText, String bindingOptions, String valueType) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("expression must not be blank");
        }
        this.expression = expression;
        this.kind = kind;
        this.nullText = Objects.requireNonNull(nullText, "nullText");
        this.bindingOptions = Objects.requireNonNull(bindingOptions, "bindingOptions");
        this.valueType = Objects.requireNonNull(valueType, "valueType");
    }

    public static ValueSqlNode raw(String expression) {
        return raw(expression, "");
    }

    /**
     * 例如 Java 字符串拼接使用 nullText="null"。
     */
    public static ValueSqlNode raw(String expression, String nullText) {
        return new ValueSqlNode(expression, Kind.RAW_TEXT, nullText, "");
    }

    public static ValueSqlNode parameter(String expression) {
        return parameter(expression, "");
    }

    public static ValueSqlNode parameter(String expression, String bindingOptions) {
        return new ValueSqlNode(expression, Kind.PARAMETER, "NULL", bindingOptions);
    }
    public static ValueSqlNode parameter(String expression, String bindingOptions, String valueType) {
        return new ValueSqlNode(expression, Kind.PARAMETER, "NULL", bindingOptions, valueType);
    }

    @Override
    public boolean isDynamic() {
        return true;
    }

    @Override
    public SqlNodeType getType() {
        return SqlNodeType.VALUE;
    }

    public enum Kind {RAW_TEXT, PARAMETER}
}
