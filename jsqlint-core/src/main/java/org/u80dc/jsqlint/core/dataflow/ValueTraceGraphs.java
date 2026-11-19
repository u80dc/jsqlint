package org.u80dc.jsqlint.core.dataflow;

import java.util.*;
import java.util.function.BiFunction;

import static org.u80dc.jsqlint.core.dataflow.ValueTrace.*;

/** Iterative operations on immutable value evidence DAGs; sharing never expands into paths. */
public final class ValueTraceGraphs {
    private ValueTraceGraphs() {}

    /** Capacity failure is separate from cancellation, which always escapes unchanged. */
    public static final class Limit extends RuntimeException {
        public Limit(String message) { super(message, null, false, false); }
    }

    public static final class Work {
        private final int maxSteps;
        private final Runnable checkpoint;
        private int steps;
        public Work(int maxSteps, Runnable checkpoint) {
            if (maxSteps < 1) throw new IllegalArgumentException("Graph work must be positive");
            this.maxSteps = maxSteps; this.checkpoint = Objects.requireNonNull(checkpoint);
        }
        public void step() {
            checkpoint.run();
            if (steps >= maxSteps) throw new Limit("Value graph work limit exceeded");
            steps++;
        }
    }

    private record Shallow(Variable variable, Kind kind, String detail, Computation computation, List<Integer> inputs) {}
    private record NodeId(Object scope, int id) {}
    private record TraceId(Variable variable, List<org.u80dc.jsqlint.core.spi.SqlCallSite> calls, NodeId root) {}
    private record Identified(NodeId key, int height) {}

    /** One set of keys per query/deduplication scope; independent equal graphs share a shallow ID. */
    public static final class Keys {
        private final int maxSteps, maxDepth;
        private final Runnable checkpoint;
        private final Object scope = new Object();
        private final Map<Node, Identified> memo = new IdentityHashMap<>();
        private final Map<Shallow, Integer> ids = new HashMap<>();

        public Keys(int maxSteps, int maxDepth, Runnable checkpoint) {
            if (maxSteps < 1 || maxDepth < 1) throw new IllegalArgumentException("Graph budgets must be positive");
            this.maxSteps = maxSteps; this.maxDepth = maxDepth; this.checkpoint = Objects.requireNonNull(checkpoint);
        }

        public Object key(ValueTrace trace) {
            return key(trace, new Work(maxSteps, checkpoint));
        }
        public Object key(Node node) { return key(node, new Work(maxSteps, checkpoint)); }
        public Object key(ValueTrace trace, Work work) {
            return new TraceId(trace.variable(), trace.callChain(), identify(trace.root(), work).key());
        }
        public Object key(Node node, Work work) { return identify(node, work).key(); }
        public boolean equivalent(Node first, Node second) { return key(first).equals(key(second)); }
        public boolean equivalent(ValueTrace first, ValueTrace second) { return key(first).equals(key(second)); }

        private Identified identify(Node root, Work work) {
            Deque<KeyFrame> pending = new ArrayDeque<>();
            Set<Node> active = Collections.newSetFromMap(new IdentityHashMap<>());
            pending.addLast(new KeyFrame(root, 0));
            while (!pending.isEmpty()) {
                KeyFrame frame = pending.peekLast();
                if (!frame.entered) {
                    work.step();
                    Identified cached = memo.get(frame.node);
                    if (cached != null) {
                        depth(frame.depth, cached.height(), maxDepth); pending.removeLast(); continue;
                    }
                    depth(frame.depth, 0, maxDepth);
                    if (!active.add(frame.node)) throw new Limit("Cyclic value graph");
                    frame.entered = true;
                }
                if (frame.next < frame.node.inputs().size()) {
                    work.step();
                    Node child = frame.node.inputs().get(frame.next++);
                    Identified cached = memo.get(child);
                    if (cached == null) pending.addLast(new KeyFrame(child, frame.depth + 1));
                    else depth(frame.depth + 1, cached.height(), maxDepth);
                } else {
                    List<Integer> children = new ArrayList<>(frame.node.inputs().size());
                    int height = 0;
                    for (Node child : frame.node.inputs()) {
                        work.step(); Identified value = memo.get(child);
                        children.add(value.key().id()); height = Math.max(height, value.height() + 1);
                    }
                    depth(frame.depth, height, maxDepth);
                    var shallow = new Shallow(frame.node.variable(), frame.node.kind(), frame.node.detail(), frame.node.computation(), List.copyOf(children));
                    int id = ids.computeIfAbsent(shallow, ignored -> ids.size());
                    memo.put(frame.node, new Identified(new NodeId(scope, id), height));
                    active.remove(frame.node); pending.removeLast();
                }
            }
            return memo.get(root);
        }
        private static final class KeyFrame {
            final Node node; final int depth; boolean entered; int next;
            KeyFrame(Node node, int depth) { this.node = node; this.depth = depth; }
        }
    }

    private static void depth(int depth, int height, int maxDepth) {
        if ((long) depth + height > maxDepth) throw new Limit("Value graph depth limit exceeded");
    }

    /** Selector may filter/replace a node before its inputs are transformed; each source identity is selected once. */
    public static Node transform(Node root, int maxSteps, int maxDepth, Runnable checkpoint,
                                 BiFunction<Node, Work, Node> selector) {
        Work work = new Work(maxSteps, checkpoint);
        Map<Node, Transformed> memo = new IdentityHashMap<>();
        Set<Node> active = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<TransformFrame> pending = new ArrayDeque<>(); pending.addLast(new TransformFrame(root, 0));
        while (!pending.isEmpty()) {
            TransformFrame frame = pending.peekLast();
            if (frame.selected == null) {
                work.step();
                Transformed cached = memo.get(frame.original);
                if (cached != null) { depth(frame.depth, cached.height(), maxDepth); pending.removeLast(); continue; }
                depth(frame.depth, 0, maxDepth);
                if (!active.add(frame.original)) throw new Limit("Cyclic value graph");
                frame.selected = Objects.requireNonNull(selector.apply(frame.original, work));
            }
            if (frame.next < frame.selected.inputs().size()) {
                work.step(); Node child = frame.selected.inputs().get(frame.next++);
                Transformed cached = memo.get(child);
                if (cached == null) pending.addLast(new TransformFrame(child, frame.depth + 1));
                else depth(frame.depth + 1, cached.height(), maxDepth);
            } else {
                List<Node> children = new ArrayList<>(frame.selected.inputs().size()); int height = 0;
                for (Node child : frame.selected.inputs()) {
                    work.step(); Transformed value = memo.get(child);
                    children.add(value.node()); height = Math.max(height, value.height() + 1);
                }
                depth(frame.depth, height, maxDepth);
                Node selected = frame.selected;
                Node transformed = children.isEmpty() ? selected : new Node(selected.variable(), selected.kind(), selected.detail(), children, selected.computation());
                memo.put(frame.original, new Transformed(transformed, height));
                active.remove(frame.original); pending.removeLast();
            }
        }
        return memo.get(root).node();
    }
    private record Transformed(Node node, int height) {}
    private static final class TransformFrame {
        final Node original; final int depth; Node selected; int next;
        TransformFrame(Node original, int depth) { this.original = original; this.depth = depth; }
    }

    /** Public record value semantics also avoid recursive graph expansion; query callers use bounded Keys. */
    static boolean equalNodes(Node first, Object other) {
        if (first == other) return true;
        if (!(other instanceof Node second)) return false;
        record Pair(Node left, Node right) {}
        Deque<Pair> pending = new ArrayDeque<>(); pending.add(new Pair(first, second));
        Map<Node, Set<Node>> seen = new IdentityHashMap<>();
        while (!pending.isEmpty()) {
            Pair pair = pending.removeLast(); Node left = pair.left(), right = pair.right();
            if (left == right || !seen.computeIfAbsent(left, ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(right)) continue;
            if (!left.variable().equals(right.variable()) || left.kind() != right.kind() || !left.detail().equals(right.detail())
                    || !left.computation().equals(right.computation()) || left.inputs().size() != right.inputs().size()) return false;
            for (int i = 0; i < left.inputs().size(); i++) pending.addLast(new Pair(left.inputs().get(i), right.inputs().get(i)));
        }
        return true;
    }

    static int hashNode(Node root) {
        record HashFrame(Node node, boolean expanded) {}
        Map<Node, Integer> memo = new IdentityHashMap<>();
        Deque<HashFrame> pending = new ArrayDeque<>(); pending.add(new HashFrame(root, false));
        while (!pending.isEmpty()) {
            HashFrame frame = pending.removeLast(); Node node = frame.node();
            if (memo.containsKey(node)) continue;
            if (!frame.expanded()) {
                pending.addLast(new HashFrame(node, true));
                for (int i = node.inputs().size() - 1; i >= 0; i--)
                    if (!memo.containsKey(node.inputs().get(i))) pending.addLast(new HashFrame(node.inputs().get(i), false));
            } else {
                int children = 1;
                for (Node child : node.inputs()) children = 31 * children + memo.get(child);
                memo.put(node, 31 * Objects.hash(node.variable(), node.kind(), node.detail(), node.computation()) + children);
            }
        }
        return memo.get(root);
    }
}
