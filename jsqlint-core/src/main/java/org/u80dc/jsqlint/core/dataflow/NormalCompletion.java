package org.u80dc.jsqlint.core.dataflow;

import sootup.core.jimple.common.Value;
import sootup.core.jimple.common.constant.*;
import sootup.core.jimple.common.expr.*;
import sootup.core.jimple.common.ref.JArrayRef;
import sootup.core.jimple.common.ref.JParameterRef;
import sootup.core.jimple.common.stmt.*;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootMethod;
import org.u80dc.jsqlint.core.semantics.JavaValueOperations;
import java.util.*;

import static org.u80dc.jsqlint.core.dataflow.FlowPoint.ValuePoint;
import static org.u80dc.jsqlint.core.dataflow.ValueTrace.*;

/** 正常完成的保守证明与数组安全条件；未知调用和递归不会被证明为必然失败。 */
final class NormalCompletion {
    enum Status { POSSIBLE, IMPOSSIBLE, UNKNOWN, OPEN_TARGETS }
    private enum Reason { EXTERNAL_CALL, RECURSION, DEPTH_LIMIT, STEP_LIMIT, MISSING_BODY, CONDITIONAL_FAILURE, CONDITIONAL_PREREQUISITES, GUARDED_DISPATCH, EXCEPTIONAL_FLOW }
    /** 外部库假设与尚未完成的应用证明分开；直线方法的安全前提可沿摘要复用。 */
    private record Summary(Status status, Set<Reason> reasons, List<FlowPoint> prerequisites) {
        Summary { reasons = Set.copyOf(reasons); prerequisites = List.copyOf(prerequisites); }
        static Summary known(Status status) { return new Summary(status, Set.of(), List.of()); }
        static Summary unknown(Reason reason) { return new Summary(Status.UNKNOWN, Set.of(reason), List.of()); }
        static Summary guarded(FlowPoint point) { return new Summary(Status.POSSIBLE, Set.of(), List.of(point)); }
        boolean needsProof() { return status == Status.OPEN_TARGETS || reasons.stream().anyMatch(reason -> reason != Reason.EXTERNAL_CALL); }
    }
    private record Frame(MethodSignature method, CallContext context) {}
    private final DataFlowAnalysis analysis;
    private final int maxDepth;
    private final Map<Frame, Summary> returns = new LinkedHashMap<>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Frame, Summary> entry) { return size() > 256; }
    };

    NormalCompletion(DataFlowAnalysis analysis, int maxDepth) { this.analysis = analysis; this.maxDepth = maxDepth; }

    Status statement(FlowPoint point) { return summary(point).status(); }

    private Summary summary(FlowPoint point) { return statement(point, new HashSet<>(), 0, new int[]{0}); }

    private Summary statement(FlowPoint point, Set<MethodSignature> active, int depth, int[] steps) {
        analysis.checkpoint();
        if (++steps[0] > DataFlowAnalysis.MAX_STEPS) return Summary.unknown(Reason.STEP_LIMIT);
        if (depth > maxDepth) return Summary.unknown(Reason.DEPTH_LIMIT);
        Stmt stmt = point.statement();
        if (stmt instanceof JThrowStmt) return Summary.known(Status.IMPOSSIBLE);
        if (stmt.containsArrayRef() && arrayFails(point.value(stmt.getArrayRef()), stmt.getArrayRef())) return Summary.known(Status.IMPOSSIBLE);
        if (stmt instanceof AbstractDefinitionStmt definition && allocationFails(point.value(definition.getRightOp()))) return Summary.known(Status.IMPOSSIBLE);
        var invocation = point.invocation(analysis.program());
        if (invocation.isEmpty()) return stmt.containsArrayRef()
                || stmt instanceof AbstractDefinitionStmt definition && (!sizes(definition.getRightOp()).isEmpty()
                || JavaValueOperations.nonZeroDivisor(definition.getRightOp()).isPresent())
                ? Summary.guarded(point) : Summary.known(Status.POSSIBLE);
        if (JavaValueOperations.invocation(invocation.get().invocation()).isPresent()) return Summary.guarded(point);
        var resolution = analysis.resolution(invocation.get(), point.context());
        boolean unknown = false, open = false;
        Set<Reason> reasons = EnumSet.noneOf(Reason.class);
        List<FlowPoint> prerequisites = new ArrayList<>();
        List<Summary> possible = new ArrayList<>();
        for (JavaSootMethod target : resolution.applicationTargets()) {
            Summary result = method(target, point.context().push(invocation.get()), active, depth + 1, steps);
            if (result.status() == Status.POSSIBLE && result.prerequisites().isEmpty()) return result;
            if (result.status() == Status.POSSIBLE) possible.add(result);
            unknown |= result.status() == Status.UNKNOWN;
            open |= result.status() == Status.OPEN_TARGETS;
            reasons.addAll(result.reasons()); prerequisites.addAll(result.prerequisites());
        }
        boolean unique = resolution.applicationTargets().size() == 1 && resolution.targetsComplete() && !resolution.hasExternalTargets();
        if (possible.size() == 1 && unique) return possible.getFirst();
        if (!possible.isEmpty()) return Summary.unknown(Reason.GUARDED_DISPATCH);
        if (open) return Summary.known(Status.OPEN_TARGETS);
        if (unknown) {
            if (!unique && !prerequisites.isEmpty()) reasons.add(Reason.GUARDED_DISPATCH);
            return new Summary(Status.UNKNOWN, reasons, unique ? prerequisites : List.of());
        }
        if (!resolution.applicationTargets().isEmpty()
                && (!resolution.targetsComplete() || resolution.hasExternalTargets())) return Summary.known(Status.OPEN_TARGETS);
        return resolution.hasExternalTargets() || !resolution.targetsComplete()
                ? Summary.unknown(Reason.EXTERNAL_CALL) : Summary.known(Status.IMPOSSIBLE);
    }

    private Summary method(JavaSootMethod method, CallContext context, Set<MethodSignature> active, int depth, int[] steps) {
        if (!method.hasBody()) return Summary.unknown(Reason.MISSING_BODY);
        if (depth > maxDepth) return Summary.unknown(Reason.DEPTH_LIMIT);
        if (!active.add(method.getSignature())) return Summary.unknown(Reason.RECURSION);
        Frame key = new Frame(method.getSignature(), context);
        try {
            Summary cached = returns.get(key);
            if (cached != null) return cached;
            var graph = method.getBody().getControlFlowGraph();
            Set<Stmt> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            Deque<Stmt> pending = new ArrayDeque<>(); pending.add(graph.getStartingStmt());
            boolean unknown = false, open = false, returned = false, branches = false, uncaughtFailure = false;
            Set<Reason> reasons = EnumSet.noneOf(Reason.class);
            Set<FlowPoint> prerequisites = new LinkedHashSet<>();
            while (!pending.isEmpty()) {
                analysis.checkpoint();
                if (++steps[0] > DataFlowAnalysis.MAX_STEPS) return Summary.unknown(Reason.STEP_LIMIT);
                Stmt stmt = pending.removeFirst();
                if (!visited.add(stmt)) continue;
                branches |= graph.successors(stmt).size() > 1;
                Summary completion = statement(new FlowPoint(method, stmt, context), active, depth, steps);
                // 异常可以被本方法捕获并正常返回；只有 normal successors 要求语句正常完成。
                pending.addAll(graph.exceptionalSuccessors(stmt).values());
                if (completion.status() == Status.IMPOSSIBLE) {
                    uncaughtFailure |= graph.exceptionalSuccessors(stmt).isEmpty();
                    continue;
                }
                unknown |= completion.status() == Status.UNKNOWN;
                open |= completion.status() == Status.OPEN_TARGETS;
                reasons.addAll(completion.reasons());
                if (!completion.prerequisites().isEmpty() && !graph.exceptionalSuccessors(stmt).isEmpty())
                    reasons.add(Reason.EXCEPTIONAL_FLOW);
                else prerequisites.addAll(completion.prerequisites());
                if (stmt instanceof ReturnStmt) returned = true;
                else pending.addAll(graph.successors(stmt));
            }
            // 不将互斥分支的前提拼成合取，也不把存在返回节点当作条件 throw 的路径证明。
            if (branches && (uncaughtFailure || !prerequisites.isEmpty())) {
                reasons.add(uncaughtFailure ? Reason.CONDITIONAL_FAILURE : Reason.CONDITIONAL_PREREQUISITES);
                prerequisites.clear();
            }
            Status status = !returned ? Status.IMPOSSIBLE : open ? Status.OPEN_TARGETS
                    : unknown || reasons.stream().anyMatch(reason -> reason != Reason.EXTERNAL_CALL) ? Status.UNKNOWN : Status.POSSIBLE;
            Summary result = new Summary(status, reasons, prerequisites.stream().toList());
            // 递归、未知目标和预算未完成的结论依赖当前栈，不缓存为后续上下文的确定摘要。
            if (status == Status.POSSIBLE || status == Status.IMPOSSIBLE) returns.put(key, result);
            return result;
        } finally { active.remove(method.getSignature()); }
    }

    boolean allocationFails(ValuePoint point) {
        for (Value size : sizes(point.value())) {
            OptionalInt length = integer(point.point().value(size));
            if (length.isPresent() && length.getAsInt() < 0) return true;
        }
        return false;
    }

    boolean arrayFails(ValuePoint input, JArrayRef array) {
        OptionalInt index = integer(input.point().value(array.getIndex()));
        if (index.isPresent() && index.getAsInt() < 0) return true;
        var bases = analysis.origins(input.point().value(array.getBase()), ignored -> false);
        if (!bases.complete() || bases.values().isEmpty()) return false;
        for (ValuePoint base : bases.values()) {
            if (base.value() instanceof NullConstant || allocationFails(base)) continue;
            List<Value> sizes = sizes(base.value());
            if (sizes.isEmpty() || index.isEmpty()) return false;
            OptionalInt length = integer(base.point().value(sizes.getFirst()));
            if (length.isEmpty() || index.getAsInt() < length.getAsInt()) return false;
        }
        return true;
    }

    Optional<ValuePoint> arrayLength(FlowPoint point, JArrayRef array) {
        var bases = analysis.origins(point.value(array.getBase()), ignored -> false);
        if (bases.complete() && bases.values().size() == 1 && bases.values().getFirst().value() instanceof JNewArrayExpr allocation)
            return Optional.of(bases.values().getFirst().point().value(allocation.getSize()));
        return Optional.empty();
    }

    /** 正常路径保留方法、长度与下标前提；返回值被丢弃也不能省略这些条件。 */
    List<ValueTrace> conditions(FlowPoint point) {
        List<ValueTrace> result = new ArrayList<>();
        Summary completion = summary(point);
        if (completion.status() == Status.IMPOSSIBLE) {
            Variable variable = point.value(IntConstant.getInstance(0)).snapshot(analysis.program()).withType("boolean");
            result.add(new ValueTrace(variable, variable.callContext(), new Node(variable, Kind.CONSTANT,
                    "Statement cannot complete normally", List.of(), new Computation(Operation.LITERAL, "false"))));
        }
        for (FlowPoint prerequisite : completion.prerequisites()) safetyConditions(prerequisite, result);
        if (!completion.prerequisites().contains(point)) safetyConditions(point, result);
        // 未知的原因决定是否缺少应用路径证明；外部库的既有假设不因增加 wrapper 而退化。
        if (completion.status() == Status.OPEN_TARGETS || completion.status() == Status.UNKNOWN && completion.needsProof()) {
            Optional<ValueTrace> returnRequirement = returnRequirement(point, completion);
            if (returnRequirement.isPresent()) {
                result.add(returnRequirement.get());
                return List.copyOf(result);
            }
            Variable variable = point.value(IntConstant.getInstance(0)).snapshot(analysis.program()).withType("boolean");
            Node returned = new Node(variable, Kind.UNRESOLVED, completion.status() == Status.OPEN_TARGETS
                    ? "Normal return depends on unresolved dispatch targets" : "Application normal return could not be proved: " + completion.reasons(),
                    List.of(), new Computation(Operation.OPAQUE, ""));
            result.add(new ValueTrace(variable, variable.callContext(), returned));
        }
        return List.copyOf(result);
    }

    /** 被调用方法的完整路径由联合选择器证明后，只需保留当前语句本身的安全条件。 */
    List<ValueTrace> statementSafetyConditions(FlowPoint point) {
        List<ValueTrace> result = new ArrayList<>();
        safetyConditions(point, result);
        return List.copyOf(result);
    }

    /** 分支前提按既有返回依赖闭包选择；完成证明只消费真实返回点身份，不重演返回值内容。 */
    private Optional<ValueTrace> returnRequirement(FlowPoint point, Summary completion) {
        if (!completion.reasons().contains(Reason.CONDITIONAL_PREREQUISITES)
                || completion.reasons().stream().anyMatch(reason -> reason != Reason.EXTERNAL_CALL
                && reason != Reason.CONDITIONAL_PREREQUISITES)) return Optional.empty();
        var invocation = point.invocation(analysis.program());
        if (invocation.isEmpty()) return Optional.empty();
        var resolution = analysis.resolution(invocation.get(), point.context());
        if (!resolution.targetsComplete() || resolution.hasExternalTargets() || resolution.applicationTargets().size() != 1)
            return Optional.empty();
        JavaSootMethod target = resolution.applicationTargets().getFirst();
        if (!target.hasBody()) return Optional.empty();
        CallContext context = point.context().push(invocation.get());
        List<Node> returned = new ArrayList<>();
        int steps = 0;
        for (Stmt statement : target.getBody().getStmts()) {
            analysis.checkpoint();
            if (++steps > DataFlowAnalysis.MAX_STEPS) return Optional.empty();
            if (!(statement instanceof JReturnStmt)) continue;
            Variable variable = new FlowPoint(target, statement, context).value(IntConstant.getInstance(1))
                    .snapshot(analysis.program()).withType("boolean");
            returned.add(new Node(variable, Kind.CONSTANT, "Selected application return completes normally", List.of(),
                    new Computation(Operation.LITERAL, "true")));
        }
        if (returned.isEmpty()) return Optional.empty();
        Variable variable = point.value(invocation.get().invocation()).snapshot(analysis.program()).withType("boolean");
        Node requirement = new Node(variable, Kind.RETURN, "Application normal return requires a selected callee path", returned,
                new Computation(Operation.ALTERNATIVES, "normal return paths", Optional.of(invocation.get().invocation().getMethodSignature())));
        return Optional.of(new ValueTrace(variable, variable.callContext(), requirement));
    }

    private void safetyConditions(FlowPoint point, List<ValueTrace> result) {
        point.invocation(analysis.program()).ifPresent(site -> JavaValueOperations.invocation(site.invocation()).ifPresent(semantic -> {
            Variable variable = point.value(site.invocation()).snapshot(analysis.program()).withType("boolean");
            boolean mapRead = site.invocation().getMethodSignature().getDeclClassType().toString().equals("java.util.Map");
            // 非空检查消费引用身份，不要求先重演新建数组或对象的完整内容。
            if (mapRead || semantic.computation().operation() == Operation.REQUIRE_NON_NULL) {
                ValuePoint reference = point.value(semantic.operands().getFirst());
                if (allocatedReference(reference)) {
                    nonNullCondition(variable, result);
                    return;
                }
                Node source = analysis.trace(reference).root();
                if (modeledNonNull(source)) {
                    nonNullCondition(variable, result);
                    return;
                }
                // 支持域内的 Map 读取只需接收者非空；不能以内容是否可重演或 get 返回值是否已知代替完成前提。
                if (mapRead) {
                    Node absent = new Node(variable.withType("null"), Kind.CONSTANT, "null reference", List.of(), new Computation(Operation.NULL, "null"));
                    Node present = new Node(variable, Kind.EXPRESSION, "Map receiver is non-null", List.of(source, absent), new Computation(Operation.BINARY, "!="));
                    result.add(new ValueTrace(variable, variable.callContext(), present));
                    return;
                }
            }
            List<Node> inputs = semantic.operands().stream().map(operand -> analysis.trace(point.value(operand)).root()).toList();
            Node completed = new Node(variable, Kind.EXPRESSION, "Normal completion of " + site.invocation().getMethodSignature(),
                    inputs, new Computation(Operation.NORMAL_COMPLETION, "", Optional.of(site.invocation().getMethodSignature())));
            result.add(new ValueTrace(variable, variable.callContext(), completed));
        }));
        if (point.statement() instanceof AbstractDefinitionStmt definition) {
            allocationConditions(point.value(definition.getRightOp()), result);
            JavaValueOperations.nonZeroDivisor(definition.getRightOp()).ifPresent(value -> {
                ValueTrace divisor = analysis.trace(point.value(value));
                Node zero = new Node(divisor.variable(), Kind.CONSTANT, "zero divisor", List.of(), new Computation(Operation.LITERAL, "0"));
                result.add(compare(divisor, zero, "!="));
            });
        }
        if (point.statement().containsArrayRef()) {
            JArrayRef array = point.statement().getArrayRef();
            ValueTrace index = analysis.trace(point.value(array.getIndex()));
            result.add(compare(index, literal(index.root().variable(), 0), ">="));
            var bases = analysis.origins(point.value(array.getBase()), ignored -> false);
            if (bases.complete() && bases.values().size() == 1) {
                ValuePoint base = bases.values().getFirst();
                if (base.value() instanceof NullConstant) {
                    result.add(falseCondition(point));
                    return;
                }
                List<Value> dimensions = sizes(base.value());
                if (!dimensions.isEmpty()) {
                    allocationConditions(base, result);
                    result.add(compare(index, analysis.trace(base.point().value(dimensions.getFirst())).root(), "<"));
                    return;
                }
                // 单一入口数组继续使用既有元素模型；具体数组模型的 null/上界由元素重演核对。
                if (base.value() instanceof JParameterRef) return;
            }
            // 来源有分支时保留相同定义身份，由联合路径选择正确分配；无法证明的长度不能省略。
            result.add(compare(index, arrayLengthTrace(point, array, bases), "<"));
        }
    }

    /** 数组身份投影到分配长度，保留 Local/RETURN 选择身份，且不消费数组内容。 */
    private Node arrayLengthTrace(FlowPoint point, JArrayRef array, DataFlowAnalysis.Origins bases) {
        Variable variable = point.value(array.getBase()).snapshot(analysis.program()).withType("int");
        if (!bases.complete() || bases.values().isEmpty()) return unknownArrayLength(variable);
        Map<Variable, Node> lengths = new HashMap<>();
        int steps = 0;
        for (ValuePoint base : bases.values()) {
            analysis.checkpoint();
            if (++steps > DataFlowAnalysis.MAX_STEPS) return unknownArrayLength(variable);
            Variable source = base.snapshot(analysis.program());
            Node length;
            if (base.value() instanceof NullConstant) {
                length = new Node(source.withType("int"), Kind.CONSTANT, "Null array has no accessible index", List.of(),
                        new Computation(Operation.LITERAL, "-1"));
            } else {
                List<Value> dimensions = sizes(base.value());
                length = dimensions.isEmpty() ? unknownArrayLength(source.withType("int"))
                        : new Node(source.withType("int"), Kind.ALLOCATION, "Array allocation first dimension", List.of(
                        analysis.trace(base.point().value(dimensions.getFirst())).root()), new Computation(Operation.IDENTITY, "array allocation length"));
            }
            lengths.put(source, length);
        }
        try {
            return ValueTraceGraphs.transform(analysis.trace(point.value(array.getBase())).root(),
                    DataFlowAnalysis.MAX_STEPS, DataFlowAnalysis.MAX_STEPS, analysis::checkpoint, (node, work) -> {
                        Node source = lengths.get(node.variable());
                        if (source != null) return source;
                        // 被替换分配节点新增的整数依赖沿原值模型求值，不再当作数组身份处理。
                        if (!node.variable().type().endsWith("[]")) return node;
                        if ((node.computation().operation() == Operation.IDENTITY || node.computation().operation() == Operation.ALTERNATIVES)
                                && !node.inputs().isEmpty() && Set.of(Kind.DEFINITION, Kind.PARAMETER, Kind.RECEIVER, Kind.RETURN).contains(node.kind()))
                            return new Node(node.variable().withType("int"), node.kind(), "Selected array allocation length",
                                    node.inputs(), node.computation());
                        return unknownArrayLength(node.variable().withType("int"));
                    });
        } catch (ValueTraceGraphs.Limit limit) {
            return unknownArrayLength(variable);
        }
    }

    private static Node unknownArrayLength(Variable variable) {
        return new Node(variable, Kind.UNRESOLVED, "Array receiver or allocation bounds could not be proved", List.of(),
                new Computation(Operation.OPAQUE, ""));
    }

    private static void nonNullCondition(Variable variable, List<ValueTrace> result) {
        result.add(new ValueTrace(variable, variable.callContext(), new Node(variable, Kind.CONSTANT,
                "Allocated or modeled reference is non-null", List.of(), new Computation(Operation.LITERAL, "true"))));
    }

    private boolean modeledNonNull(Node root) {
        Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Node> pending = new ArrayDeque<>(); pending.add(root);
        int steps = 0;
        while (!pending.isEmpty()) {
            analysis.checkpoint();
            if (++steps > DataFlowAnalysis.MAX_STEPS) return false;
            Node node = pending.removeLast();
            if (!seen.add(node)) continue;
            if (node.computation().operation() == Operation.MAP_LITERAL || node.computation().operation() == Operation.LITERAL) continue;
            if ((node.computation().operation() == Operation.IDENTITY || node.computation().operation() == Operation.ALTERNATIVES)
                    && !node.inputs().isEmpty()) pending.addAll(node.inputs());
            else return false;
        }
        return true;
    }

    private boolean allocatedReference(ValuePoint input) {
        var origins = analysis.origins(input, ignored -> false);
        return origins.complete() && !origins.values().isEmpty() && origins.values().stream().allMatch(source ->
                source.value() instanceof JNewExpr || source.value() instanceof JNewArrayExpr || source.value() instanceof JNewMultiArrayExpr);
    }

    private void allocationConditions(ValuePoint allocation, List<ValueTrace> result) {
        for (Value size : sizes(allocation.value())) {
            ValueTrace length = analysis.trace(allocation.point().value(size));
            result.add(compare(length, literal(length.root().variable(), 0), ">="));
        }
    }

    private OptionalInt integer(ValuePoint point) {
        var origins = analysis.origins(point, ignored -> false);
        if (origins.complete() && origins.values().size() == 1 && origins.values().getFirst().value() instanceof IntConstant value)
            return OptionalInt.of(value.getValue());
        return OptionalInt.empty();
    }

    private static List<Value> sizes(Value value) {
        if (value instanceof JNewArrayExpr array) return List.of(array.getSize());
        if (value instanceof JNewMultiArrayExpr array) return new ArrayList<>(array.getSizes());
        return List.of();
    }

    private static Node literal(Variable variable, int value) {
        return new Node(variable.withType("int"), Kind.CONSTANT, "array bound", List.of(), new Computation(Operation.LITERAL, Integer.toString(value)));
    }

    private static ValueTrace compare(ValueTrace left, Node right, String operator) {
        Node predicate = new Node(left.variable().withType("boolean"), Kind.EXPRESSION, "Java normal completion",
                List.of(left.root(), right), new Computation(Operation.BINARY, operator));
        return new ValueTrace(predicate.variable(), left.callChain(), predicate);
    }

    private ValueTrace falseCondition(FlowPoint point) {
        Variable variable = point.value(IntConstant.getInstance(0)).snapshot(analysis.program()).withType("boolean");
        Node node = new Node(variable, Kind.CONSTANT, "null array cannot complete normally", List.of(), new Computation(Operation.LITERAL, "false"));
        return new ValueTrace(variable, variable.callContext(), node);
    }
}
