package org.u80dc.jsqlint.core.node;

import java.util.*;
import java.lang.reflect.Array;

import static org.u80dc.jsqlint.core.node.SqlExpressionEvaluator.*;

/** Records input reads without interpreting an adapter's expression language. */
final class SqlEvaluationTracker {
    private final SqlEvaluationContext context;
    private final Activity activity;
    private final Map<String, ReadNode> roots = new HashMap<>();
    private final Map<Reference, ReadNode> reads = new LinkedHashMap<>();
    private final Set<Object> inputContainers = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<SqlValue.Known, Map<Reference, SqlValue.Known>> observedKnowns = new IdentityHashMap<>();
    private final Map<Map<?, ?>, Map<Reference, TrackedMap>> trackedMaps = new IdentityHashMap<>();
    private int steps;
    private String limit;

    private SqlEvaluationTracker(SqlEvaluationContext context) {
        this.context = context; this.activity = context.activity();
    }

    /** Protected copies are shared by expressions in one execution; trackers and read evidence are not. */
    static final class Activity {
        private final Map<SqlValue.Known, SqlValue.Known> knowns = new IdentityHashMap<>();
        final SqlEvaluationResults results = new SqlEvaluationResults();
        SqlValue.Known known(SqlValue.Known source) {
            return knowns.computeIfAbsent(source, value -> new SqlValue.Known(value.snapshotValue()));
        }
        Object value(Object source) {
            return source instanceof SqlValue.Known saved ? known(saved).snapshotValue() : source;
        }
        Resolution resolve(Map<String, ?> environment, Reference reference, SqlEvaluationContext context) {
            context.check();
            if (!environment.containsKey(reference.name())) return new Resolution(false, null);
            Object result = value(environment.get(reference.name()));
            for (Object key : reference.path()) {
                context.check();
                if (result instanceof Map<?, ?> map) {
                    if (!map.containsKey(key)) return new Resolution(false, null);
                    result = value(map.get(key));
                } else if (key instanceof Number index) {
                    int position;
                    try { position = new java.math.BigDecimal(index.toString()).intValueExact(); }
                    catch (ArithmeticException | NumberFormatException error) { return new Resolution(false, null); }
                    if (result instanceof List<?> list && position >= 0 && position < list.size()) result = value(list.get(position));
                    else if (result != null && result.getClass().isArray() && position >= 0 && position < Array.getLength(result))
                        result = value(Array.get(result, position));
                    else return new Resolution(false, null);
                } else return new Resolution(false, null);
            }
            return new Resolution(true, result);
        }
    }
    record Resolution(boolean found, Object value) {}
    private static final class ReadNode { final Map<Object, ReadNode> children = new HashMap<>(); }
    private static final class EvaluationLimit extends RuntimeException {
        EvaluationLimit(String message) { super(message, null, false, false); }
    }

    /** Tracking wrappers remain live only during evaluation; clean removes them before publishing a snapshot. */
    static boolean isTrackedValue(Object value) { return value instanceof TrackedMap; }

    static Evaluation evaluate(SqlExpressionEvaluator evaluator, String expression, Map<String, ?> parameters, boolean parameter) {
        var context = SqlEvaluationContext.current();
        return context.evaluate(() -> evaluate(evaluator, expression, parameters, parameter, context));
    }

    private static Evaluation evaluate(SqlExpressionEvaluator evaluator, String expression, Map<String, ?> parameters,
                                       boolean parameter, SqlEvaluationContext context) {
        var tracker = new SqlEvaluationTracker(context);
        @SuppressWarnings("unchecked")
        Map<String, ?> observed = (Map<String, ?>) (Map<?, ?>) tracker.tracked(parameters, null);
        SqlValue value;
        Reference resultReference = null;
        ActivityValue activeValue = null;
        Optional<String> resultLimit = Optional.empty();
        try {
            tracker.step();
            var evaluated = tracker.activity.results.capture(() -> parameter ? evaluator.evaluateParameter(expression, observed)
                    : evaluator.evaluate(expression, observed), context);
            value = evaluated.value(); resultLimit = evaluated.limit();
            if (evaluated.activity().isPresent()) {
                var activity = evaluated.activity().get();
                resultReference = activity.reference().orElse(null);
                if (resultReference != null) tracker.read(resultReference);
                Object sourceValue = activity.value(tracker.context);
                // Getter and indexed sources can bypass observed Maps. Their containers still belong to the input,
                // so publishing a snapshot must not replace their live identity with a cleaned container copy.
                if (resultReference != null && !isTrackedValue(sourceValue)
                        && (sourceValue instanceof Collection<?> || sourceValue instanceof Map<?, ?>
                        || sourceValue != null && sourceValue.getClass().isArray())) tracker.inputContainers.add(sourceValue);
                activeValue = tracker.context.activityValue(tracker.clean(sourceValue, new IdentityHashMap<>(), 0), resultReference);
            }
            if (value instanceof SqlValue.Known known)
                value = new SqlValue.Known(tracker.clean(known.snapshotValue(), new IdentityHashMap<>(), 0));
        } catch (RuntimeException error) {
            SqlEvaluationContext.rethrowControl(error);
            value = new SqlValue.Unknown("Evaluation failed: " + error.getMessage());
        }
        // Insertion already paid for every path component. This bounded pass never scans other reads.
        List<Reference> references = new ArrayList<>();
        for (var read : tracker.reads.entrySet()) {
            tracker.context.check();
            if (read.getValue().children.isEmpty()) references.add(read.getKey());
        }
        if (tracker.limit != null) { value = new SqlValue.Unknown(tracker.limit); resultLimit = Optional.of(tracker.limit); }
        boolean direct = references.size() == 1 && references.getFirst().path().isEmpty()
                && expression.strip().equals(references.getFirst().name());
        if (!(value instanceof SqlValue.Known)) activeValue = null;
        return new Evaluation(value, references, direct, Optional.ofNullable(activeValue), resultLimit).withActivity(context, parameters);
    }

    private void step() {
        context.check();
        if (limit != null) throw new EvaluationLimit(limit);
        if (++steps > context.replayBudgets().steps()) {
            limit = "Expression reference tracking work budget exceeded";
            context.limit(limit);
            throw new EvaluationLimit(limit);
        }
    }
    private void read(Reference reference) {
        step();
        if (reference.path().size() > Math.min(128, context.replayBudgets().depth())) {
            limit = "Expression reference tracking path depth budget exceeded";
            context.limit(limit);
            throw new EvaluationLimit(limit);
        }
        ReadNode node = roots.computeIfAbsent(reference.name(), ignored -> new ReadNode());
        for (Object key : reference.path()) { step(); node = node.children.computeIfAbsent(key, ignored -> new ReadNode()); }
        reads.putIfAbsent(reference, node);
    }

    private Object observe(Object value, Reference reference) {
        step();
        if (value instanceof SqlValue.Known known) {
            SqlValue.Known protectedKnown = activity.known(known);
            Object activeValue = protectedKnown.snapshotValue();
            if (activeValue instanceof Map<?, ?> || activeValue instanceof SqlValue.Known)
                return observedKnowns.computeIfAbsent(known, ignored -> new HashMap<>())
                        .computeIfAbsent(reference, ignored -> new SqlValue.Known(observe(activeValue, reference)));
            return protectedKnown;
        }
        if (value instanceof Map<?, ?> map) {
            inputContainers.add(map);
            return tracked(map, reference);
        }
        if (value instanceof Collection<?> || value != null && value.getClass().isArray()) inputContainers.add(value);
        return value;
    }

    private TrackedMap tracked(Map<?, ?> delegate, Reference reference) {
        return trackedMaps.computeIfAbsent(delegate, ignored -> new HashMap<>())
                .computeIfAbsent(reference, ignored -> new TrackedMap(delegate, reference));
    }

    private final class TrackedMap extends AbstractMap<Object, Object> {
        private final Map<?, ?> delegate;
        private final Reference prefix;
        private TrackedMap(Map<?, ?> delegate, Reference prefix) { this.delegate = delegate; this.prefix = prefix; }
        private Reference reference(Object key) {
            step();
            Reference reference;
            if (prefix == null) {
                if (!(key instanceof String name)) return null;
                reference = new Reference(name, List.of());
            } else {
                var path = new ArrayList<>(prefix.path()); path.add(key);
                reference = new Reference(prefix.name(), path);
            }
            read(reference);
            return reference;
        }
        @Override public boolean containsKey(Object key) {
            reference(key); return delegate.containsKey(key);
        }
        @Override public Object get(Object key) {
            Reference reference = reference(key);
            Object value = delegate.get(key);
            return reference == null ? value : observe(value, reference);
        }
        @Override public Set<Entry<Object, Object>> entrySet() {
            return new AbstractSet<>() {
                @Override public int size() { step(); return delegate.size(); }
                @Override public Iterator<Entry<Object, Object>> iterator() {
                    step();
                    var iterator = delegate.entrySet().iterator();
                    return new Iterator<>() {
                        @Override public boolean hasNext() { step(); return iterator.hasNext(); }
                        @Override public Entry<Object, Object> next() {
                            step();
                            var entry = iterator.next();
                            return new SimpleImmutableEntry<>(entry.getKey(), get(entry.getKey()));
                        }
                    };
                }
            };
        }
    }

    /** Saved results must not retain a tracker or its execution context. */
    private Object clean(Object value, Map<Object, Object> seen, int depth) {
        step();
        if (depth > Math.min(128, context.replayBudgets().depth())) {
            limit = "Expression result container depth budget exceeded"; context.limit(limit);
            throw new EvaluationLimit(limit);
        }
        if (value == null || inputContainers.contains(value)) return value;
        if (seen.containsKey(value)) return seen.get(value);
        if (value instanceof TrackedMap map) return map.prefix == null ? clean(map.delegate, seen, depth + 1) : map.delegate;
        if (value instanceof SqlValue.Known known) {
            var result = new SqlValue.Known(clean(known.snapshotValue(), seen, depth + 1));
            seen.put(value, result); return result;
        }
        if (value.getClass().isArray() && !value.getClass().getComponentType().isPrimitive()) {
            int length = Array.getLength(value);
            Object copy = Array.newInstance(value.getClass().getComponentType(), length); seen.put(value, copy);
            for (int i = 0; i < length; i++) Array.set(copy, i, clean(Array.get(value, i), seen, depth + 1));
            return copy;
        }
        if (value instanceof Map<?, ?> map) {
            var copy = new LinkedHashMap<Object, Object>(); var result = Collections.unmodifiableMap(copy); seen.put(value, result);
            for (var entry : map.entrySet()) copy.put(clean(entry.getKey(), seen, depth + 1), clean(entry.getValue(), seen, depth + 1));
            return result;
        }
        if (value instanceof List<?> list) {
            var copy = new ArrayList<>(); var result = Collections.unmodifiableList(copy); seen.put(value, result);
            for (Object item : list) copy.add(clean(item, seen, depth + 1));
            return result;
        }
        if (value instanceof Set<?> set) {
            var copy = new ArrayList<Object>(); var result = SqlValueSnapshots.capturedSet(copy); seen.put(value, result);
            for (Object item : set) copy.add(clean(item, seen, depth + 1));
            return result;
        }
        return value;
    }
}
