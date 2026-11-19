package org.u80dc.jsqlint.core.node;

import org.u80dc.jsqlint.core.ReplayBudgets;
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

    /** 冻结定义可保存重演预算；不使用 Java 来源的旧扩展保持原实例。 */
    default SqlExpressionEvaluator withReplayBudgets(ReplayBudgets budgets) {
        Objects.requireNonNull(budgets);
        return this;
    }

    default SqlValue evaluate(String expression, Map<String, ?> parameters, SqlEvaluationContext context) {
        return Objects.requireNonNull(context).evaluate(() -> evaluate(expression, parameters));
    }

    /** 绑定路径可使用与动态表达式不同的语法；默认沿用普通表达式规则。 */
    default SqlValue evaluateParameter(String expression, Map<String, ?> parameters) { return evaluate(expression, parameters); }

    default SqlValue evaluateParameter(String expression, Map<String, ?> parameters, SqlEvaluationContext context) {
        return Objects.requireNonNull(context).evaluate(() -> evaluateParameter(expression, parameters));
    }

    /** 同一次求值中记录 get/containsKey 访问的输入（包括缺失名称）；实现也可显式返回自身语言的结构化引用。 */
    default Evaluation evaluateWithReferences(String expression, Map<String, ?> parameters, boolean parameter) {
        return SqlEvaluationTracker.evaluate(this, expression, parameters, parameter);
    }

    /** 为本次执行提供预算、取消检查及活动身份；旧的自定义引用求值实现继续生效。 */
    default Evaluation evaluateWithReferences(String expression, Map<String, ?> parameters, boolean parameter,
                                               SqlEvaluationContext context) {
        return Objects.requireNonNull(context).evaluate(() ->
                evaluateWithReferences(expression, parameters, parameter).withActivity(context, parameters));
    }

    record Reference(String name, List<Object> path) {
        public Reference {
            Objects.requireNonNull(name);
            path = Collections.unmodifiableList(new ArrayList<>(path));
        }
    }
    /** 活动值只属于一次执行；持久结果始终通过 Evaluation.value 保存防御快照。 */
    final class ActivityValue {
        private final Object value;
        private final Reference reference;
        private final Object scope;

        ActivityValue(Object value, Reference reference, Object scope) {
            this.value = value; this.reference = reference; this.scope = scope;
        }

        public Optional<Reference> reference() { return Optional.ofNullable(reference); }

        Object value(SqlEvaluationContext context) {
            if (scope != context.activity()) throw new IllegalArgumentException("Activity value belongs to another execution");
            return value;
        }
    }

    /** 值快照、读取证据、活动身份及容量终态均由本次结果携带，不依赖求值器实现类型。 */
    record Evaluation(SqlValue value, List<Reference> references, boolean directReference,
                      Optional<ActivityValue> activity, Optional<String> limit) {
        /** 兼容旧扩展；直接引用的活动身份可由本次输入恢复。 */
        public Evaluation(SqlValue value, List<Reference> references, boolean directReference) {
            this(value, references, directReference, Optional.empty(), Optional.empty());
        }

        public Evaluation {
            Objects.requireNonNull(value); references = List.copyOf(references);
            Objects.requireNonNull(activity); Objects.requireNonNull(limit);
            if (directReference && references.size() != 1) throw new IllegalArgumentException("A direct value requires one input reference");
            if (activity.isPresent() && !(value instanceof SqlValue.Known))
                throw new IllegalArgumentException("Only a known result has an activity value");
        }

        /** Complete the legacy result contract at the interface boundary, before a renderer consumes it. */
        Evaluation withActivity(SqlEvaluationContext context, Map<String, ?> parameters) {
            if (activity.isPresent() || !(value instanceof SqlValue.Known known)) return this;
            Reference source = directReference ? references.getFirst() : null;
            var resolved = source == null ? null : context.activity().resolve(parameters, source, context);
            Object active = resolved != null && resolved.found() ? resolved.value() : known.value();
            return new Evaluation(value, references, directReference, Optional.of(context.activityValue(active, source)), limit);
        }
    }
}
