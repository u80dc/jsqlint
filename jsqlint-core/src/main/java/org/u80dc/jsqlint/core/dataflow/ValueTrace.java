package org.u80dc.jsqlint.core.dataflow;

import org.u80dc.jsqlint.core.spi.SqlCallSite;
import sootup.core.signatures.MethodSignature;
import java.util.*;

/** 可持久保留的逆向数据依赖树。分支和多个操作数全部保留，不以一个字符串替代未知来源。 @author u80dc */
public record ValueTrace(Variable variable, List<SqlCallSite> callChain, Node root) {
    public ValueTrace { Objects.requireNonNull(variable); callChain = List.copyOf(callChain); Objects.requireNonNull(root); }
    public boolean complete() { return root.complete(); }

    /** 使用位置与调用上下文共同定位变量；同一方法的两次调用不能共用内部值或分支选择。 */
    public record Variable(MethodSignature method, int statementIndex, String expression, String type,
                           List<SqlCallSite> callContext) {
        public Variable { callContext = List.copyOf(callContext); }
        public Variable(MethodSignature method, int statementIndex, String expression, String type) {
            this(method, statementIndex, expression, type, List.of());
        }
        /** 合成类型转换节点时保留原位置和调用上下文。 */
        public Variable withType(String type) { return new Variable(method, statementIndex, expression, type, callContext); }
    }
    public record Node(Variable variable, Kind kind, String detail, List<Node> inputs, Computation computation) {
        public Node { Objects.requireNonNull(variable); Objects.requireNonNull(kind); Objects.requireNonNull(detail); inputs = List.copyOf(inputs); Objects.requireNonNull(computation); }
        public Node(Variable variable, Kind kind, String detail, List<Node> inputs) {
            this(variable, kind, detail, inputs, new Computation(Operation.OPAQUE, ""));
        }
        public boolean complete() {
            Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            Deque<Node> pending = new ArrayDeque<>(); pending.addLast(this);
            while (!pending.isEmpty()) {
                Node node = pending.removeLast();
                if (!seen.add(node)) continue;
                switch (node.kind()) {
                    case CYCLE, LIMIT, UNRESOLVED, FIELD, EXTERNAL_CALL -> { return false; }
                    default -> pending.addAll(node.inputs());
                }
            }
            return true;
        }
    }
    /** 值计算语义独立于依赖节点类型；下游无需解析 Jimple 的 toString 来恢复 SQL。 */
    public record Computation(Operation operation, String text, Optional<MethodSignature> method, List<ControlEdge> history) {
        public Computation {
            Objects.requireNonNull(operation); Objects.requireNonNull(text); Objects.requireNonNull(method); history = List.copyOf(history);
            // 旧扩展曾把这些运算写为 IDENTITY/CONCAT 加固定文案；在输入边界规范化，消费端只判断操作种类。
            if (operation == Operation.IDENTITY) operation = switch (text) {
                case "Objects.requireNonNull" -> Operation.REQUIRE_NON_NULL;
                case "String constructor" -> Operation.STRING_COPY;
                case "String cast" -> Operation.STRING_CAST;
                default -> operation;
            };
            else if (operation == Operation.CONCAT && text.equals("String.concat")) operation = Operation.STRING_CONCAT;
        }
        public Computation(Operation operation, String text, Optional<MethodSignature> method) { this(operation, text, method, List.of()); }
        public Computation(Operation operation, String text) { this(operation, text, Optional.empty(), List.of()); }
    }
    /** 值快照所依赖的控制流选择；不保留活动方法体或语句对象。 */
    public record ControlEdge(Variable from, Variable to, boolean exceptional) {
        public ControlEdge { Objects.requireNonNull(from); Objects.requireNonNull(to); }
    }
    /** ARRAY_INPUT 的两个输入依次为选中的下标和入口数组，重演时不能将元素与数组模型分离。 */
    public enum Operation { OPAQUE, LITERAL, NULL, IDENTITY, ALTERNATIVES, CONCAT, STRINGIFY, ARRAY_READ, ARRAY_INPUT, REPLACE,
        BINARY, NEGATE, CAST, CALL, MAP_LITERAL, REQUIRE_NON_NULL, STRING_COPY, STRING_CAST, STRING_CONCAT }
    public enum Kind {
        DEFINITION, PARAMETER, RECEIVER, EXPRESSION, RETURN, CONSTANT, ENTRY, NO_CALLER,
        FIELD, ARRAY_ELEMENT, EXTERNAL_CALL, ALLOCATION, UNRESOLVED, CYCLE, LIMIT
    }
}
