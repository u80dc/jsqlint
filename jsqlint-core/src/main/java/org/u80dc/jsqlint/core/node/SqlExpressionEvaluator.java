package org.u80dc.jsqlint.core.node;

import java.util.*;

/**
 * 独立的表达式扩展接口，不依赖 SootUp 或旧 analysis 模块。
 * 实现不应修改输入或外部状态；不支持的表达式应返回 Unknown。
 * 默认求值器为兼容对象属性访问会调用输入对象的 getter/record 访问器，无法保证这些方法的纯度。
 * 调用方应提供无副作用的参数对象或已冻结的数据 Map；需要其他访问策略时可注入自己的求值器。
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

    /** 同一次求值中记录 get/containsKey 访问的输入（包括缺失名称）；实现也可显式返回自身语言的结构化引用。 */
    default Evaluation evaluateWithReferences(String expression, Map<String, ?> parameters, boolean parameter) {
        return SqlEvaluationTracker.evaluate(this, expression, parameters, parameter);
    }

    record Reference(String name, List<Object> path) {
        public Reference {
            Objects.requireNonNull(name);
            path = Collections.unmodifiableList(new ArrayList<>(path));
        }
    }
    record Evaluation(SqlValue value, List<Reference> references, boolean directReference) {
        public Evaluation {
            Objects.requireNonNull(value); references = List.copyOf(references);
            if (directReference && references.size() != 1) throw new IllegalArgumentException("A direct value requires one input reference");
        }
    }
}
