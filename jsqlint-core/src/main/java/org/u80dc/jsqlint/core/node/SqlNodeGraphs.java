package org.u80dc.jsqlint.core.node;

import org.u80dc.jsqlint.core.ReplayBudgets;
import org.u80dc.jsqlint.core.semantics.ValueEvaluationLimitException;
import java.util.*;
import java.util.function.Consumer;

/** Iterative SQL IR queries and structural keys; shared children are inspected once per query. */
public final class SqlNodeGraphs {
    private SqlNodeGraphs() {}

    /** An unfinished dynamic query remains conservative instead of reporting a static template. */
    public static boolean isDynamic(SqlNode node) {
        return query(node, false, ReplayBudgets.defaults().withSteps(20000), () -> {}, ignored -> {});
    }

    /** An unfinished query may contain control conditions. */
    public static boolean containsControl(SqlNode node, Runnable checkpoint) {
        return containsControl(node, ReplayBudgets.defaults(), checkpoint, ignored -> {});
    }

    public static boolean containsControl(SqlNode node, ReplayBudgets budgets, Runnable checkpoint, Consumer<String> limits) {
        return query(node, true, budgets, checkpoint, limits);
    }

    private static boolean query(SqlNode root, boolean controlsOnly, ReplayBudgets budgets,
                                 Runnable checkpoint, Consumer<String> limits) {
        var work = new Work(budgets, checkpoint);
        Set<SqlNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<SqlNode> pending = new ArrayDeque<>(); pending.add(root);
        try {
            while (!pending.isEmpty()) {
                work.check();
                SqlNode node = pending.removeLast();
                if (!seen.add(node)) continue;
                if (node instanceof IfSqlNode || node instanceof ChooseSqlNode || node instanceof ForEachSqlNode) return true;
                if (!controlsOnly && (node instanceof BindSqlNode || node instanceof ValueSqlNode
                        || node instanceof TextSqlNode text && text.isDynamic())) return true;
                for (SqlNode child : children(node)) { work.check(); pending.addLast(child); }
            }
            return false;
        } catch (ValueEvaluationLimitException limit) {
            limits.accept(limit.getMessage());
            return true;
        }
    }

    public static List<SqlNode> distinct(Collection<? extends SqlNode> roots, ReplayBudgets budgets, Runnable checkpoint) {
        var work = new Work(budgets, checkpoint);
        // Repeated occurrences of the same root require neither a structural hash nor a graph walk.
        Set<SqlNode> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        List<SqlNode> unique = new ArrayList<>();
        for (SqlNode node : roots) { work.check(); if (identities.add(node)) unique.add(Objects.requireNonNull(node)); }
        if (unique.size() < 2) return List.copyOf(unique);
        Map<SqlNode, Integer> ids = new IdentityHashMap<>();
        Map<Key, Integer> keys = new HashMap<>();
        Set<Integer> selected = new HashSet<>();
        List<SqlNode> result = new ArrayList<>();
        for (SqlNode root : unique) {
            Deque<Frame> pending = new ArrayDeque<>(); pending.addLast(new Frame(root, false));
            while (!pending.isEmpty()) {
                work.check();
                Frame frame = pending.removeLast();
                if (ids.containsKey(frame.node())) continue;
                List<SqlNode> children = children(frame.node());
                if (!frame.ready()) {
                    pending.addLast(new Frame(frame.node(), true));
                    for (int i = children.size() - 1; i >= 0; i--) {
                        work.check();
                        if (!ids.containsKey(children.get(i))) pending.addLast(new Frame(children.get(i), false));
                    }
                } else {
                    List<Integer> inputs = new ArrayList<>(children.size());
                    for (SqlNode child : children) { work.check(); inputs.add(ids.get(child)); }
                    Key key = new Key(frame.node().getType(), fields(frame.node(), work), List.copyOf(inputs));
                    int id = keys.computeIfAbsent(key, ignored -> keys.size());
                    ids.put(frame.node(), id);
                }
            }
            if (selected.add(ids.get(root))) result.add(root);
        }
        return List.copyOf(result);
    }

    /** Direct children only; this does not invoke recursive node equality or isDynamic. */
    public static List<SqlNode> children(SqlNode node) {
        return switch (node) {
            case MixedSqlNode mixed -> mixed.contents();
            case IfSqlNode conditional -> List.of(conditional.contents());
            case ForEachSqlNode foreach -> List.of(foreach.contents());
            case WhereSqlNode where -> List.of(where.contents());
            case SetSqlNode set -> List.of(set.contents());
            case TrimSqlNode trim -> List.of(trim.contents());
            case ChooseSqlNode choose -> {
                yield new AbstractList<>() {
                    @Override public SqlNode get(int index) {
                        Objects.checkIndex(index, size());
                        return index < choose.whenContents().size() ? choose.whenContents().get(index) : choose.otherwiseContents();
                    }
                    @Override public int size() { return choose.whenContents().size() + (choose.otherwiseContents() == null ? 0 : 1); }
                };
            }
            default -> List.of();
        };
    }

    private static List<Object> fields(SqlNode node, Work work) {
        List<Object> fields = switch (node) {
            case StaticTextSqlNode text -> List.of(text.text());
            case TextSqlNode text -> List.of(text.text());
            case ValueSqlNode value -> List.of(value.getKind(), value.getExpression(), value.getNullText(),
                    value.getValueType(), value.getBindingOptions());
            case MixedSqlNode mixed -> List.of(mixed.separator());
            case IfSqlNode conditional -> List.of(conditional.test());
            case BindSqlNode bind -> List.of(bind.name(), bind.expression());
            case ForEachSqlNode foreach -> List.of(foreach.collection(), foreach.item(), foreach.index(), foreach.open(),
                    foreach.close(), foreach.separator(), foreach.nullable(), foreach.skipBlank());
            case TrimSqlNode trim -> List.of(trim.prefix(), trim.suffix(), trim.prefixOverrides(), trim.suffixOverrides());
            case ChooseSqlNode choose -> List.of(choose.whenContents().size(), choose.otherwiseContents() != null);
            case WhereSqlNode ignored -> List.of();
            case SetSqlNode ignored -> List.of();
        };
        for (Object field : fields) {
            work.check();
            if (field instanceof String text && text.length() > work.budgets.length())
                throw new ValueEvaluationLimitException("SQL node graph text budget exceeded");
        }
        return fields;
    }

    private record Frame(SqlNode node, boolean ready) {}
    private record Key(SqlNodeType type, List<Object> fields, List<Integer> inputs) {}
    private static final class Work {
        final ReplayBudgets budgets;
        final Runnable checkpoint;
        int steps;
        Work(ReplayBudgets budgets, Runnable checkpoint) {
            this.budgets = Objects.requireNonNull(budgets); this.checkpoint = Objects.requireNonNull(checkpoint);
        }
        void check() {
            checkpoint.run();
            if (++steps > budgets.steps()) throw new ValueEvaluationLimitException("SQL node graph work budget exceeded");
        }
    }
}
