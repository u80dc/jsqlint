package org.u80dc.jsqlint.core.node;

import java.util.*;

import static org.u80dc.jsqlint.core.node.SqlExpressionEvaluator.*;

/** 通过只读 Map 访问记录依赖，不在 core 解析适配层的表达式语法。 @author u80dc */
final class SqlEvaluationTracker {
    private final Set<Reference> reads = new LinkedHashSet<>();
    private final Set<Object> inputContainers = Collections.newSetFromMap(new IdentityHashMap<>());

    static Evaluation evaluate(SqlExpressionEvaluator evaluator, String expression, Map<String, ?> parameters, boolean parameter) {
        var tracker = new SqlEvaluationTracker();
        @SuppressWarnings("unchecked")
        Map<String, ?> observed = (Map<String, ?>) (Map<?, ?>) tracker.new TrackedMap(parameters, null);
        SqlValue value;
        try {
            value = Objects.requireNonNull(parameter ? evaluator.evaluateParameter(expression, observed)
                    : evaluator.evaluate(expression, observed), "Evaluator returned null");
        } catch (RuntimeException e) { value = new SqlValue.Unknown("Evaluation failed: " + e.getMessage()); }
        if (value instanceof SqlValue.Known known) value = new SqlValue.Known(tracker.clean(known.value(), new IdentityHashMap<>()));
        var references = tracker.reads.stream().filter(reference -> tracker.reads.stream()
                .noneMatch(other -> parent(reference, other))).toList();
        boolean direct = references.size() == 1 && references.getFirst().path().isEmpty()
                && expression.strip().equals(references.getFirst().name());
        return new Evaluation(value, references, direct);
    }

    private static boolean parent(Reference parent, Reference child) {
        return parent.name().equals(child.name()) && parent.path().size() < child.path().size()
                && child.path().subList(0, parent.path().size()).equals(parent.path());
    }

    private Object observe(Object value, Reference reference) {
        if (value instanceof SqlValue.Known known) return new SqlValue.Known(observe(known.value(), reference));
        if (value instanceof Map<?, ?> map) {
            inputContainers.add(map);
            return new TrackedMap(map, reference);
        }
        if (value instanceof Collection<?> || value != null && value.getClass().isArray()) inputContainers.add(value);
        return value;
    }

    private final class TrackedMap extends AbstractMap<Object, Object> {
        private final Map<?, ?> delegate;
        private final Reference prefix;
        private TrackedMap(Map<?, ?> delegate, Reference prefix) { this.delegate = delegate; this.prefix = prefix; }
        @Override public boolean containsKey(Object key) { return delegate.containsKey(key); }
        @Override public Object get(Object key) {
            Reference reference;
            if (prefix == null) {
                if (!(key instanceof String name)) return delegate.get(key);
                reference = new Reference(name, List.of());
            } else {
                var path = new ArrayList<>(prefix.path()); path.add(key);
                reference = new Reference(prefix.name(), path);
            }
            reads.add(reference);
            return observe(delegate.get(key), reference);
        }
        @Override public Set<Entry<Object, Object>> entrySet() {
            return new AbstractSet<>() {
                @Override public int size() { return delegate.size(); }
                @Override public Iterator<Entry<Object, Object>> iterator() {
                    var iterator = delegate.entrySet().iterator();
                    return new Iterator<>() {
                        @Override public boolean hasNext() { return iterator.hasNext(); }
                        @Override public Entry<Object, Object> next() {
                            var entry = iterator.next();
                            return new SimpleImmutableEntry<>(entry.getKey(), get(entry.getKey()));
                        }
                    };
                }
            };
        }
    }

    /** 不让值快照持有跟踪 Map 或渲染上下文；原始输入容器无需重新遍历。 */
    private Object clean(Object value, Map<Object, Object> seen) {
        if (value instanceof TrackedMap map) return map.prefix == null ? clean(map.delegate, seen) : map.delegate;
        if (value instanceof SqlValue.Known known) return new SqlValue.Known(clean(known.value(), seen));
        if (value == null || inputContainers.contains(value)) return value;
        if (seen.containsKey(value)) return seen.get(value);
        if (value instanceof Map<?, ?> map) {
            var copy = new LinkedHashMap<Object, Object>(); var result = Collections.unmodifiableMap(copy); seen.put(value, result);
            map.forEach((key, item) -> copy.put(clean(key, seen), clean(item, seen)));
            return result;
        }
        if (value instanceof List<?> list) {
            var copy = new ArrayList<>(); var result = Collections.unmodifiableList(copy); seen.put(value, result);
            list.forEach(item -> copy.add(clean(item, seen)));
            return result;
        }
        if (value instanceof Set<?> set) {
            var copy = new LinkedHashSet<>(); var result = Collections.unmodifiableSet(copy); seen.put(value, result);
            set.forEach(item -> copy.add(clean(item, seen)));
            return result;
        }
        return value;
    }
}
