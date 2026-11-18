package org.u80dc.jsqlint.core.node;

import java.util.*;
import java.lang.reflect.Array;

import static org.u80dc.jsqlint.core.node.SqlExpressionEvaluator.*;

/** 通过只读 Map 访问记录依赖，不在 core 解析适配层的表达式语法。 @author u80dc */
final class SqlEvaluationTracker {
    private final Set<Reference> reads = new LinkedHashSet<>();
    private final Set<Object> inputContainers = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Tracking wrappers remain live only inside evaluation; clean removes them before publishing a snapshot. */
    static boolean isTrackedValue(Object value) { return value instanceof TrackedMap; }

    static Evaluation evaluate(SqlExpressionEvaluator evaluator, String expression, Map<String, ?> parameters, boolean parameter) {
        var tracker = new SqlEvaluationTracker();
        @SuppressWarnings("unchecked")
        Map<String, ?> observed = (Map<String, ?>) (Map<?, ?>) tracker.new TrackedMap(parameters, null);
        SqlValue value;
        try {
            value = Objects.requireNonNull(parameter ? evaluator.evaluateParameter(expression, observed)
                    : evaluator.evaluate(expression, observed), "Evaluator returned null");
        } catch (RuntimeException e) { value = new SqlValue.Unknown("Evaluation failed: " + e.getMessage()); }
        if (value instanceof SqlValue.Known known) {
            try { value = new SqlValue.Known(tracker.clean(known.snapshotValue(), new IdentityHashMap<>())); }
            catch (RuntimeException e) { value = new SqlValue.Unknown("Evaluation failed: " + e.getMessage()); }
        }
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
        private Reference read(Object key) {
            Reference reference;
            if (prefix == null) {
                if (!(key instanceof String name)) return null;
                reference = new Reference(name, List.of());
            } else {
                var path = new ArrayList<>(prefix.path()); path.add(key);
                reference = new Reference(prefix.name(), path);
            }
            reads.add(reference);
            return reference;
        }
        @Override public boolean containsKey(Object key) {
            read(key);
            return delegate.containsKey(key);
        }
        @Override public Object get(Object key) {
            Reference reference = read(key);
            Object value = delegate.get(key);
            return reference == null ? value : observe(value, reference);
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
        if (value == null || inputContainers.contains(value)) return value;
        if (seen.containsKey(value)) return seen.get(value);
        if (value instanceof TrackedMap map) return map.prefix == null ? clean(map.delegate, seen) : map.delegate;
        if (value instanceof SqlValue.Known known) {
            var result = new SqlValue.Known(clean(known.snapshotValue(), seen));
            seen.put(value, result);
            return result;
        }
        if (value.getClass().isArray() && !value.getClass().getComponentType().isPrimitive()) {
            int length = Array.getLength(value);
            Object copy = Array.newInstance(value.getClass().getComponentType(), length); seen.put(value, copy);
            for (int i = 0; i < length; i++) Array.set(copy, i, clean(Array.get(value, i), seen));
            return copy;
        }
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
            var copy = new ArrayList<Object>(); var result = SqlValueSnapshots.capturedSet(copy); seen.put(value, result);
            set.forEach(item -> copy.add(clean(item, seen)));
            return result;
        }
        return value;
    }
}
