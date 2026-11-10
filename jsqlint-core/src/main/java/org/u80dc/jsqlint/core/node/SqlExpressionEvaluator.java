package org.u80dc.jsqlint.core.node;

import java.util.Map;

/**
 * 独立的表达式扩展接口，不依赖 SootUp 或旧 analysis 模块。
 * 实现必须无副作用；不支持的表达式应返回 Unknown。
 *
 * @author u80dc
 */
@FunctionalInterface
public interface SqlExpressionEvaluator {
    static SqlExpressionEvaluator simple() {
        return new SimpleSqlExpressionEvaluator();
    }

    SqlValue evaluate(String expression, Map<String, ?> parameters);

    /** 绑定路径可使用与动态表达式不同的语法；默认沿用普通表达式规则。 */
    default SqlValue evaluateParameter(String expression, Map<String, ?> parameters) { return evaluate(expression, parameters); }
}
