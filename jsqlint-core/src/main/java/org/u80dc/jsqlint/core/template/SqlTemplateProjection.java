package org.u80dc.jsqlint.core.template;

import org.u80dc.jsqlint.core.RawSql;
import org.u80dc.jsqlint.core.ReplayBudgets;
import org.u80dc.jsqlint.core.SqlRecoveryBudgets;
import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.parameter.ParameterValueInferencer;
import org.u80dc.jsqlint.core.semantics.ValueEvaluationLimitException;
import java.util.*;
import java.util.regex.Pattern;

/** 从已恢复的候选派生模板视图；只折叠已知输入，不为兼容输出生成随机值。 @author u80dc */
public final class SqlTemplateProjection {
    private static final Pattern CHOICE_NAME = Pattern.compile("(?<![\\p{L}\\p{N}_$])choice_[0-9]+(?![\\p{L}\\p{N}_$])");
    private SqlTemplateProjection() {}

    public static SqlNode template(RawSql raw, Runnable checkpoint) {
        return template(raw, ReplayBudgets.defaults(), checkpoint);
    }
    public static SqlNode template(RawSql raw, ReplayBudgets budgets, Runnable checkpoint) {
        return template(raw, budgets, SqlRecoveryBudgets.defaults(), checkpoint);
    }

    /** 值折叠使用重演策略；SQL 结构投影独立消费恢复策略，不用参数长度截断静态文本。 */
    public static SqlNode template(RawSql raw, ReplayBudgets budgets, SqlRecoveryBudgets recovery, Runnable checkpoint) {
        var projectionBudgets = projectionCapacity(recovery);
        var work = new Work(projectionBudgets, checkpoint);
        var inference = new ParameterValueInferencer(
                (type, name) -> new SqlValue.Unknown("External value retained symbolically"), checkpoint, budgets);
        List<SqlNode> alternatives = new ArrayList<>();
        for (var variant : raw.variants()) {
            work.check(); work.text(variant.text());
            List<SqlNode> parts = new ArrayList<>();
            int start = 0;
            for (var slot : variant.placeholders()) {
                work.check();
                parts.add(new StaticTextSqlNode(variant.text().substring(start, slot.start())));
                var trace = new ValueTrace(slot.source().variable(),
                        variant.trace().map(ValueTrace::callChain).orElse(List.of()), slot.source());
                var values = inference.infer(trace);
                // SQL 槽位是转换后的字符串；原始 null 或非字符串 SQL 不能被折叠成有效文本。
                if (values.complete() && values.possibleValues().size() == 1
                        && values.possibleValues().getFirst().value() instanceof SqlValue.Known known
                        && known.value() instanceof String text) parts.add(new StaticTextSqlNode(text));
                else parts.add(ValueSqlNode.raw(slot.name(), "null"));
                start = slot.end();
            }
            parts.add(new StaticTextSqlNode(variant.text().substring(start)));
            if (parts.stream().allMatch(StaticTextSqlNode.class::isInstance)) {
                var text = new StringBuilder();
                for (SqlNode part : parts) {
                    work.check();
                    String literal = ((StaticTextSqlNode) part).text();
                    if ((long) text.length() + literal.length() > projectionBudgets.length())
                        throw new ValueEvaluationLimitException("SQL projection text length budget exceeded");
                    text.append(literal);
                }
                alternatives.add(new StaticTextSqlNode(text.toString()));
            } else alternatives.add(new MixedSqlNode(parts));
        }
        return alternatives(alternatives, projectionBudgets, checkpoint);
    }

    /**
     * 选择标识仅展示候选关系；实际控制条件保存在联合求解结果中。
     * 单层默认保留 choice_0，组合时避开子模板已有选择键和同名业务引用。
     */
    public static SqlNode alternatives(Collection<? extends SqlNode> nodes) {
        return alternatives(nodes, ReplayBudgets.defaults(), () -> {});
    }

    public static SqlNode alternatives(Collection<? extends SqlNode> nodes, ReplayBudgets budgets, Runnable checkpoint) {
        var distinct = SqlNodeGraphs.distinct(nodes, budgets, checkpoint);
        if (distinct.isEmpty()) return ValueSqlNode.raw("unresolvedSql");
        if (distinct.size() == 1) return distinct.getFirst();
        var work = new Work(budgets, checkpoint);
        Set<String> used = choiceNames(distinct, work);
        int group = 0;
        while (used.contains("choice_" + group)) { work.check(); group++; }
        String selector = "choice_" + group;
        List<IfSqlNode> branches = new ArrayList<>();
        for (int i = 0; i < distinct.size(); i++) { work.check(); branches.add(new IfSqlNode(selector + " == " + i, distinct.get(i))); }
        return new ChooseSqlNode(branches, null);
    }

    /** 已恢复候选的结构组合使用 SQL 恢复容量，图工具保持旧容量接口兼容。 */
    public static SqlNode alternatives(Collection<? extends SqlNode> nodes, SqlRecoveryBudgets budgets, Runnable checkpoint) {
        return alternatives(nodes, projectionCapacity(budgets), checkpoint);
    }

    private static ReplayBudgets projectionCapacity(SqlRecoveryBudgets budgets) {
        Objects.requireNonNull(budgets);
        return new ReplayBudgets(budgets.variants(), budgets.steps(), budgets.structureDepth(), budgets.length());
    }

    private static Set<String> choiceNames(Collection<? extends SqlNode> nodes, Work work) {
        Set<String> names = new HashSet<>();
        Set<SqlNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<SqlNode> pending = new ArrayDeque<>();
        for (SqlNode node : nodes) { work.check(); pending.addLast(node); }
        while (!pending.isEmpty()) {
            work.check();
            SqlNode node = pending.removeFirst();
            if (!seen.add(node)) continue;
            switch (node) {
                case BindSqlNode bind -> { choiceNames(bind.name(), names, work); choiceNames(bind.expression(), names, work); }
                case ChooseSqlNode ignored -> {}
                case ForEachSqlNode foreach -> {
                    choiceNames(foreach.collection(), names, work); choiceNames(foreach.item(), names, work); choiceNames(foreach.index(), names, work);
                }
                case IfSqlNode conditional -> choiceNames(conditional.test(), names, work);
                case MixedSqlNode ignored -> {}
                case SetSqlNode ignored -> {}
                case TextSqlNode text -> choiceNames(text.text(), names, work);
                case TrimSqlNode ignored -> {}
                case ValueSqlNode value -> choiceNames(value.getExpression(), names, work);
                case WhereSqlNode ignored -> {}
                case StaticTextSqlNode ignored -> {}
            }
            for (SqlNode child : SqlNodeGraphs.children(node)) { work.check(); pending.addLast(child); }
        }
        return names;
    }

    private static void choiceNames(String expression, Set<String> names, Work work) {
        work.text(expression);
        var matcher = CHOICE_NAME.matcher(expression);
        while (matcher.find()) { work.check(); names.add(matcher.group()); }
    }

    private static final class Work {
        final ReplayBudgets budgets;
        final Runnable checkpoint;
        int steps;
        long textLength;
        Work(ReplayBudgets budgets, Runnable checkpoint) { this.budgets = Objects.requireNonNull(budgets); this.checkpoint = Objects.requireNonNull(checkpoint); }
        void check() {
            checkpoint.run();
            if (++steps > budgets.steps()) throw new ValueEvaluationLimitException("SQL projection work budget exceeded");
        }
        void text(String text) {
            check(); textLength += text.length();
            if (textLength > budgets.length()) throw new ValueEvaluationLimitException("SQL projection text length budget exceeded");
        }
    }
}
