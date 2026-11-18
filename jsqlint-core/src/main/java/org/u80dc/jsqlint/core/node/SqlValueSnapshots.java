package org.u80dc.jsqlint.core.node;

import java.lang.reflect.Array;
import java.util.*;

/**
 * Captures acyclic data containers without retaining caller-owned mutable storage.
 * Arrays keep their runtime type and are copied again when exposed; lists, sets and maps are read-only.
 * Other objects are retained and must be immutable for the lifetime of a saved result.
 */
public final class SqlValueSnapshots {
    private SqlValueSnapshots() {}

    public static Object capture(Object value) {
        return copy(value, new IdentityHashMap<>(), Collections.newSetFromMap(new IdentityHashMap<>()), 0);
    }

    public static Map<String, Object> parameters(Map<String, ?> parameters) {
        Objects.requireNonNull(parameters, "parameters");
        @SuppressWarnings("unchecked")
        var snapshot = (Map<String, Object>) capture(parameters);
        return snapshot;
    }

    private static Object copy(Object value, Map<Object, Object> copies, Set<Object> active, int depth) {
        // Capturing a tracker by enumerating it would add artificial reads and stop tracking subsequent property access.
        if (SqlEvaluationTracker.isTrackedValue(value)) return value;
        if (value == null || !(value.getClass().isArray() || value instanceof Collection<?> || value instanceof Map<?, ?>))
            return value;
        if (depth > 256) throw new IllegalArgumentException("SQL value snapshot container depth exceeded");
        if (!active.add(value)) throw new IllegalArgumentException("Cyclic containers are not supported in SQL value snapshots");
        try {
            if (copies.containsKey(value)) return copies.get(value);
            Object snapshot;
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                snapshot = Array.newInstance(value.getClass().getComponentType(), length);
                if (value.getClass().getComponentType().isPrimitive()) {
                    System.arraycopy(value, 0, snapshot, 0, length);
                } else for (int i = 0; i < length; i++) {
                    Object item = copy(Array.get(value, i), copies, active, depth + 1);
                    try { Array.set(snapshot, i, item); }
                    catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("Array component type cannot hold a frozen SQL value: "
                                + value.getClass().getComponentType().getName(), e);
                    }
                }
            } else if (value instanceof List<?> list) {
                var items = new ArrayList<Object>(list.size());
                for (Object item : list) items.add(copy(item, copies, active, depth + 1));
                snapshot = Collections.unmodifiableList(items);
            } else if (value instanceof Set<?> set) {
                var items = new ArrayList<Object>(set.size());
                for (Object item : set) items.add(copy(item, copies, active, depth + 1));
                // Membership is already unique; rebuilding a hash table would recursively hash every member.
                snapshot = capturedSet(items);
            } else if (value instanceof Map<?, ?> map) {
                var items = new LinkedHashMap<Object, Object>();
                for (var entry : map.entrySet()) {
                    Object key = entry.getKey();
                    if (key != null && (key.getClass().isArray() || key instanceof Collection<?> || key instanceof Map<?, ?>))
                        throw new IllegalArgumentException("Mutable container keys are not supported in SQL value snapshots");
                    items.put(key, copy(entry.getValue(), copies, active, depth + 1));
                }
                snapshot = Collections.unmodifiableMap(items);
            } else {
                throw new IllegalArgumentException("Unsupported SQL value snapshot collection: " + value.getClass().getName());
            }
            copies.put(value, snapshot);
            return snapshot;
        } finally { active.remove(value); }
    }

    /** Content comparison for supported snapshots, including arrays nested in containers. */
    static boolean sameValue(Object left, Object right) {
        return sameValue(left, right, new IdentityHashMap<>());
    }

    private static boolean sameValue(Object left, Object right, Map<Object, Map<Object, Boolean>> comparisons) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        var compared = comparisons.get(left);
        if (compared != null && compared.containsKey(right)) return compared.get(right);
        boolean same = compare(left, right, comparisons);
        comparisons.computeIfAbsent(left, ignored -> new IdentityHashMap<>()).put(right, same);
        return same;
    }

    private static boolean compare(Object left, Object right, Map<Object, Map<Object, Boolean>> comparisons) {
        if (left instanceof SqlValue.Known a && right instanceof SqlValue.Known b)
            return sameValue(a.snapshotValue(), b.snapshotValue(), comparisons);
        if (left.getClass().isArray() || right.getClass().isArray()) {
            if (left.getClass() != right.getClass() || Array.getLength(left) != Array.getLength(right)) return false;
            for (int i = 0; i < Array.getLength(left); i++)
                if (!sameValue(Array.get(left, i), Array.get(right, i), comparisons)) return false;
            return true;
        }
        if (left instanceof List<?> a && right instanceof List<?> b) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!sameValue(a.get(i), b.get(i), comparisons)) return false;
            return true;
        }
        if (left instanceof Set<?> a && right instanceof Set<?> b) {
            if (a.size() != b.size()) return false;
            var unmatched = new ArrayList<>(b);
            for (Object item : a) {
                int match = -1;
                for (int i = 0; i < unmatched.size(); i++) if (sameValue(item, unmatched.get(i), comparisons)) { match = i; break; }
                if (match < 0) return false;
                unmatched.remove(match);
            }
            return true;
        }
        if (left instanceof Map<?, ?> a && right instanceof Map<?, ?> b) {
            if (a.size() != b.size()) return false;
            for (var entry : a.entrySet())
                if (!b.containsKey(entry.getKey()) || !sameValue(entry.getValue(), b.get(entry.getKey()), comparisons)) return false;
            return true;
        }
        return left.equals(right);
    }

    static int contentHash(Object value) {
        return contentHash(value, new IdentityHashMap<>());
    }

    private static int contentHash(Object value, Map<Object, Integer> hashes) {
        if (value == null) return 0;
        if (hashes.containsKey(value)) return hashes.get(value);
        int hash = hash(value, hashes);
        hashes.put(value, hash);
        return hash;
    }

    private static int hash(Object value, Map<Object, Integer> hashes) {
        if (value instanceof SqlValue.Known known) return contentHash(known.snapshotValue(), hashes);
        if (value.getClass().isArray()) {
            int result = value.getClass().getName().hashCode();
            for (int i = 0; i < Array.getLength(value); i++) result = 31 * result + contentHash(Array.get(value, i), hashes);
            return result;
        }
        if (value instanceof List<?> list) {
            int result = 1;
            for (Object item : list) result = 31 * result + contentHash(item, hashes);
            return result;
        }
        if (value instanceof Set<?> set) return set.stream().mapToInt(item -> contentHash(item, hashes)).sum();
        if (value instanceof Map<?, ?> map) return map.entrySet().stream()
                .mapToInt(entry -> Objects.hashCode(entry.getKey()) ^ contentHash(entry.getValue(), hashes)).sum();
        return value.hashCode();
    }

    static Set<Object> capturedSet(List<Object> items) { return Collections.unmodifiableSet(new CapturedSet(items)); }

    private static final class CapturedSet extends AbstractSet<Object> {
        private final List<Object> items;
        private CapturedSet(List<Object> items) { this.items = Collections.unmodifiableList(items); }
        @Override public Iterator<Object> iterator() { return items.iterator(); }
        @Override public int size() { return items.size(); }
    }
}
