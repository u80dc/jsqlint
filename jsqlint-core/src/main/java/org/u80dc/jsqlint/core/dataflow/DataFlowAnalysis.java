package org.u80dc.jsqlint.core.dataflow;

import org.u80dc.jsqlint.core.spi.InvocationSite;
import sootup.core.jimple.common.*;
import sootup.core.jimple.common.constant.*;
import sootup.core.jimple.common.expr.*;
import sootup.core.jimple.common.ref.*;
import sootup.core.jimple.common.stmt.*;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootMethod;
import java.util.*;
import java.util.function.*;

import static org.u80dc.jsqlint.core.dataflow.FlowPoint.ValuePoint;
import static org.u80dc.jsqlint.core.dataflow.ValueTrace.Kind;
import static org.u80dc.jsqlint.core.dataflow.ValueTrace.Operation;

/**
 * 可被各框架复用的需求驱动逆向分析：调用上下文、局部到达定义、返回值与对象来源。
 * 跨方法实参沿具体调用栈传递；CHA 的多个目标保留为候选，未解析边不冒充已知值。
 * @author u80dc
 */
public final class DataFlowAnalysis {
    public static final int MAX_PATHS = 256;
    public static final int MAX_STEPS = 10000;
    private final ProgramIndex program;
    private final LocalDefinitions definitions;
    private final int maxDepth;
    private final Runnable checkpoint;
    private final BiConsumer<String, String> incomplete;
    private final Set<String> reported = new HashSet<>();
    private final Map<MethodSignature, List<ContextPath>> contextCache = new HashMap<>();

    public DataFlowAnalysis(ProgramIndex program, int maxDepth, int cacheSize, Runnable checkpoint,
                            BiConsumer<String, String> incomplete) {
        this.program = program; this.maxDepth = maxDepth; this.checkpoint = checkpoint; this.incomplete = incomplete;
        definitions = new LocalDefinitions(cacheSize, checkpoint);
    }
    public ProgramIndex program() { return program; }
    public void checkpoint() { checkpoint.run(); }
    public void incomplete(String message) {
        if (reported.add(message)) incomplete.accept("DATA_FLOW_INCOMPLETE", message);
    }

    /** 穷举项目内调用路径，直到 main 或没有调用方的方法；不在 public 方法处提前停止。 */
    public List<ContextPath> contexts(JavaSootMethod method) {
        return contextCache.computeIfAbsent(method.getSignature(), ignored -> findContexts(method));
    }
    private List<ContextPath> findContexts(JavaSootMethod method) {
        record Pending(JavaSootMethod method, List<InvocationSite> reversed, Set<MethodSignature> active) {}
        Deque<Pending> pending = new ArrayDeque<>();
        pending.add(new Pending(method, List.of(), Set.of()));
        List<ContextPath> result = new ArrayList<>();
        while (!pending.isEmpty()) {
            checkpoint.run();
            Pending next = pending.removeFirst();
            End end = null;
            if (next.active().contains(next.method().getSignature())) end = End.CYCLE;
            else if (next.reversed().size() >= maxDepth || result.size() + pending.size() >= MAX_PATHS) end = End.LIMIT;
            else if (next.method().isMain(program.view().getIdentifierFactory())) end = End.ENTRY;
            var callers = program.callers().getOrDefault(next.method().getSignature(), List.of());
            if (end == null && callers.isEmpty()) end = End.NO_CALLER;
            if (end != null) {
                List<InvocationSite> calls = new ArrayList<>(next.reversed()); Collections.reverse(calls);
                result.add(new ContextPath(new CallContext(calls), end));
                if (end == End.CYCLE || end == End.LIMIT) incomplete(end + " in caller chain of " + method.getSignature());
                if (end == End.LIMIT && result.size() >= MAX_PATHS) break;
                continue;
            }
            Set<MethodSignature> active = new HashSet<>(next.active()); active.add(next.method().getSignature());
            for (InvocationSite call : callers) {
                var chain = new ArrayList<>(next.reversed()); chain.add(call);
                pending.addLast(new Pending(call.method(), chain, active));
            }
        }
        return List.copyOf(result);
    }

    public ValueTrace trace(ValuePoint input) {
        int[] steps = {0};
        return new ValueTrace(input.snapshot(), input.point().context().snapshot(), trace(input, new HashSet<>(), 0, steps));
    }
    private ValueTrace.Node trace(ValuePoint input, Set<ValuePoint> active, int depth, int[] steps) {
        checkpoint.run();
        if (depth >= maxDepth || ++steps[0] > MAX_STEPS) return leaf(input, Kind.LIMIT, "data dependency budget exceeded");
        if (!active.add(input)) return leaf(input, Kind.CYCLE, "cyclic data dependency");
        try {
            Value value = input.value();
            FlowPoint point = input.point();
            if (value instanceof Constant constant) {
                String text = switch (constant) {
                    case StringConstant textConstant -> textConstant.getValue();
                    case IntConstant number -> Integer.toString(number.getValue());
                    case LongConstant number -> Long.toString(number.getValue());
                    case FloatConstant number -> Float.toString(number.getValue());
                    case DoubleConstant number -> Double.toString(number.getValue());
                    case BooleanConstant bool -> Boolean.toString(bool.getValue());
                    case NullConstant ignored -> "null";
                    default -> null;
                };
                if (text == null)
                    return node(input, Kind.CONSTANT, "Constant string conversion is not modeled: " + constant, List.of());
                return computed(input, Kind.CONSTANT, constant instanceof NullConstant ? Operation.NULL : Operation.LITERAL, text, List.of());
            }
            if (value instanceof Local local) {
                var reaching = definitions.reaching(point.method(), local, point.statement());
                var children = new ArrayList<ValueTrace.Node>();
                for (Stmt stmt : reaching.statements()) {
                    ValuePoint definition = new FlowPoint(point.method(), stmt, point.context())
                            .value(((AbstractDefinitionStmt) stmt).getRightOp());
                    if (definition.value() instanceof JNewExpr) {
                        var contents = new ObjectContents(this).reaching(point, definition);
                        var inputs = new ArrayList<ValueTrace.Node>();
                        for (ValuePoint source : contents.values()) inputs.add(trace(source, active, depth + 1, steps));
                        for (String issue : contents.issues()) inputs.add(leaf(definition, Kind.UNRESOLVED, issue));
                        children.add(contents.issues().isEmpty() && inputs.size() == 1
                                ? computed(definition, Kind.ALLOCATION, Operation.IDENTITY, "String constructor", inputs)
                                : node(definition, Kind.ALLOCATION, "object content through constructor", inputs));
                    } else children.add(trace(definition, active, depth + 1, steps));
                }
                if (reaching.missing() || children.isEmpty()) children.add(leaf(input, Kind.UNRESOLVED, "no reaching definition"));
                return computed(input, Kind.DEFINITION, Operation.ALTERNATIVES, "all reaching definitions", children);
            }
            if (value instanceof JParameterRef || value instanceof JThisRef) {
                Optional<ValuePoint> argument = argument(input);
                if (argument.isPresent()) return computed(input, value instanceof JThisRef ? Kind.RECEIVER : Kind.PARAMETER,
                        Operation.IDENTITY, "actual argument at caller", List.of(trace(argument.get(), active, depth + 1, steps)));
                return leaf(input, point.method().isMain(program.view().getIdentifierFactory()) ? Kind.ENTRY : Kind.NO_CALLER,
                        "project input: " + value);
            }
            if (value instanceof JFieldRef field) {
                List<ValueTrace.Node> children = new ArrayList<>();
                // 堆对象尚未做精确 points-to：保留全部候选写入并标明 may-alias，不将其解释为唯一赋值。
                for (FlowPoint write : program.writes(field.getFieldSignature())) for (ContextPath context : contexts(write.method())) {
                    Value right = ((AbstractDefinitionStmt) write.statement()).getRightOp();
                    children.add(trace(new FlowPoint(write.method(), write.statement(), context.context()).value(right), active, depth + 1, steps));
                }
                return node(input, Kind.FIELD, "candidate field writes; heap alias/order unresolved", children);
            }
            if (value instanceof JArrayRef array) {
                var read = new ArrayElements(this).reaching(input, array);
                var children = new ArrayList<ValueTrace.Node>();
                children.add(trace(point.value(array.getIndex()), active, depth + 1, steps));
                for (ValuePoint source : read.values()) children.add(trace(source, active, depth + 1, steps));
                for (String issue : read.issues()) children.add(leaf(input, Kind.UNRESOLVED, issue));
                return computed(input, Kind.ARRAY_ELEMENT, Operation.ARRAY_READ, "reaching array element writes", children);
            }
            if (value instanceof JCastExpr cast && cast.getType().toString().equals("java.lang.String"))
                return computed(input, Kind.EXPRESSION, Operation.IDENTITY, "String cast",
                        List.of(trace(point.value(cast.getOp()), active, depth + 1, steps)));
            if (value instanceof AbstractInvokeExpr invoke) {
                String owner = invoke.getMethodSignature().getDeclClassType().toString();
                String name = invoke.getMethodSignature().getName();
                if (owner.equals("java.util.Objects") && name.equals("requireNonNull") && invoke.getArgCount() >= 1)
                    return computed(input, Kind.EXPRESSION, Operation.IDENTITY, "Objects.requireNonNull",
                            List.of(trace(point.value(invoke.getArg(0)), active, depth + 1, steps)));
                if (owner.equals("java.lang.String") && name.equals("valueOf") && invoke.getArgCount() == 1
                        && !invoke.getMethodSignature().getParameterTypes().getFirst().toString().equals("char[]"))
                    return computed(input, Kind.EXPRESSION, Operation.STRINGIFY,
                            invoke.getMethodSignature().getParameterTypes().getFirst().toString(),
                            List.of(trace(point.value(invoke.getArg(0)), active, depth + 1, steps)));
                if (owner.equals("java.lang.String") && name.equals("replace") && invoke instanceof AbstractInstanceInvokeExpr instance
                        && invoke.getArgCount() == 2 && invoke.getArg(0) instanceof StringConstant && invoke.getArg(1) instanceof StringConstant)
                    return computed(input, Kind.EXPRESSION, Operation.REPLACE, "String.replace", List.of(
                            trace(point.value(instance.getBase()), active, depth + 1, steps),
                            trace(point.value(invoke.getArg(0)), active, depth + 1, steps),
                            trace(point.value(invoke.getArg(1)), active, depth + 1, steps)));
                if (invoke instanceof JDynamicInvokeExpr dynamic && dynamic.getBootstrapMethodSignature()
                        .getDeclClassType().toString().equals("java.lang.invoke.StringConcatFactory"))
                    return concat(input, dynamic, active, depth, steps);
                if (invoke instanceof AbstractInstanceInvokeExpr instance && invoke.getMethodSignature().getDeclClassType()
                        .toString().equals("java.lang.String") && invoke.getMethodSignature().getName().equals("concat") && invoke.getArgCount() == 1) {
                    return computed(input, Kind.EXPRESSION, Operation.CONCAT, "String.concat", List.of(
                            trace(point.value(instance.getBase()), active, depth + 1, steps),
                            trace(point.value(invoke.getArg(0)), active, depth + 1, steps)));
                }
                InvocationSite site = invocation(input, invoke);
                var resolution = program.resolution(site);
                List<JavaSootMethod> targets = resolution.applicationTargets();
                if (!targets.isEmpty()) {
                    List<ValueTrace.Node> children = new ArrayList<>();
                    for (JavaSootMethod target : targets) for (Stmt stmt : target.getBody().getStmts()) {
                        if (stmt instanceof JReturnStmt returned) children.add(trace(new FlowPoint(target, stmt,
                                point.context().push(site)).value(returned.getOp()), active, depth + 1, steps));
                    }
                    if (resolution.hasExternalTargets()) children.add(node(input, Kind.EXTERNAL_CALL,
                            "external or unresolved dispatch alternative: " + invoke.getMethodSignature(),
                            operands(input, invoke.getUses(), active, depth, steps)));
                    if (!children.isEmpty()) return computed(input, Kind.RETURN, Operation.ALTERNATIVES, "return values (CHA candidates)", children);
                }
                List<ValueTrace.Node> children = operands(input, invoke.getUses(), active, depth, steps);
                boolean concat = invoke instanceof JDynamicInvokeExpr dynamic && dynamic.getBootstrapMethodSignature()
                        .getDeclClassType().toString().equals("java.lang.invoke.StringConcatFactory");
                boolean string = invoke.getMethodSignature().getDeclClassType().toString().equals("java.lang.String");
                return node(input, concat || string ? Kind.EXPRESSION : Kind.EXTERNAL_CALL,
                        invoke.getMethodSignature().toString(), children);
            }
            if (value instanceof JNewExpr)
                return node(input, Kind.ALLOCATION, value.toString(), List.of(leaf(input, Kind.UNRESOLVED,
                        "Allocation identity does not describe object content")));
            if (value instanceof JNewArrayExpr || value instanceof JNewMultiArrayExpr)
                return node(input, Kind.ALLOCATION, value.toString(), operands(input, value.getUses(), active, depth, steps));
            List<ValueTrace.Node> children = operands(input, value.getUses(), active, depth, steps);
            return node(input, children.isEmpty() ? Kind.UNRESOLVED : Kind.EXPRESSION, value.toString(), children);
        } finally { active.remove(input); }
    }
    private List<ValueTrace.Node> operands(ValuePoint input, List<Value> values, Set<ValuePoint> active, int depth, int[] steps) {
        return values.stream().distinct().filter(value -> !value.equals(input.value()))
                .map(value -> trace(input.point().value(value), active, depth + 1, steps)).toList();
    }
    private ValueTrace.Node computed(ValuePoint input, Kind kind, Operation operation, String text, List<ValueTrace.Node> children) {
        return new ValueTrace.Node(input.snapshot(), kind, text, children, new ValueTrace.Computation(operation, text));
    }

    /** 严格按 JVM 拼接配方保存常量与实参顺序，重复实参不能像普通依赖边一样去重。 */
    private ValueTrace.Node concat(ValuePoint input, JDynamicInvokeExpr invoke, Set<ValuePoint> active, int depth, int[] steps) {
        List<ValueTrace.Node> args = new ArrayList<>();
        for (int i = 0; i < invoke.getArgCount(); i++) {
            var argument = input.point().value(invoke.getArg(i));
            args.add(computed(argument, Kind.EXPRESSION, Operation.STRINGIFY,
                    invoke.getMethodSignature().getParameterTypes().get(i).toString(),
                    List.of(trace(argument, active, depth + 1, steps))));
        }
        if (invoke.getBootstrapMethodSignature().getName().equals("makeConcat"))
            return computed(input, Kind.EXPRESSION, Operation.CONCAT, "Java string concatenation", args);
        if (!invoke.getBootstrapMethodSignature().getName().equals("makeConcatWithConstants") || invoke.getBootstrapArgCount() == 0
                || !(invoke.getBootstrapArg(0) instanceof StringConstant recipe))
            return node(input, Kind.UNRESOLVED, "Unsupported string concat recipe", args);
        List<ValueTrace.Node> parts = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int argument = 0, constant = 1;
        for (char character : recipe.getValue().toCharArray()) {
            if (character != 1 && character != 2) { text.append(character); continue; }
            if (!text.isEmpty()) {
                parts.add(trace(input.point().value(new StringConstant(text.toString(), program.view().getIdentifierFactory()
                        .getClassType("java.lang.String"))), active, depth + 1, steps));
                text.setLength(0);
            }
            if (character == 1) {
                if (argument >= args.size()) return node(input, Kind.UNRESOLVED, "Missing concat argument", args);
                parts.add(args.get(argument++));
            } else {
                if (constant >= invoke.getBootstrapArgCount()) return node(input, Kind.UNRESOLVED, "Missing concat constant", args);
                var operand = input.point().value(invoke.getBootstrapArg(constant++));
                parts.add(computed(operand, Kind.EXPRESSION, Operation.STRINGIFY, operand.value().getType().toString(),
                        List.of(trace(operand, active, depth + 1, steps))));
            }
        }
        if (!text.isEmpty()) parts.add(trace(input.point().value(new StringConstant(text.toString(), program.view()
                .getIdentifierFactory().getClassType("java.lang.String"))), active, depth + 1, steps));
        if (argument != args.size() || constant != invoke.getBootstrapArgCount()) return node(input, Kind.UNRESOLVED, "Invalid concat recipe", args);
        return computed(input, Kind.EXPRESSION, Operation.CONCAT, "Java string concatenation", parts);
    }
    private ValueTrace.Node leaf(ValuePoint point, Kind kind, String detail) { return node(point, kind, detail, List.of()); }
    private ValueTrace.Node node(ValuePoint point, Kind kind, String detail, List<ValueTrace.Node> inputs) {
        return new ValueTrace.Node(point.snapshot(), kind, detail, inputs);
    }
    private InvocationSite invocation(ValuePoint input, AbstractInvokeExpr invocation) {
        FlowPoint point = input.point();
        return new InvocationSite(point.method(), point.statement(), point.method().getBody().getStmts().indexOf(point.statement()), invocation, program.view());
    }
    private Optional<ValuePoint> argument(ValuePoint input) {
        FlowPoint point = input.point();
        if (point.context().calls().isEmpty()) return Optional.empty();
        InvocationSite caller = point.context().calls().getLast();
        FlowPoint at = new FlowPoint(caller.method(), caller.statement(), point.context().parent());
        if (input.value() instanceof JParameterRef parameter && parameter.getIndex() < caller.invocation().getArgCount())
            return Optional.of(at.value(caller.invocation().getArg(parameter.getIndex())));
        if (input.value() instanceof JThisRef && caller.invocation() instanceof AbstractInstanceInvokeExpr instance)
            return Optional.of(at.value(instance.getBase()));
        return Optional.empty();
    }

    /** 沿赋值、参数、返回值和 cast 追踪引用身份；框架可声明其工厂方法为终点。 */
    public Origins origins(ValuePoint input, Predicate<InvocationSite> factory) {
        List<ValuePoint> values = new ArrayList<>();
        boolean complete = origins(input, factory, new HashSet<>(), 0, new int[]{0}, values);
        return new Origins(values.stream().distinct().toList(), complete);
    }
    private boolean origins(ValuePoint input, Predicate<InvocationSite> factory, Set<ValuePoint> active,
                            int depth, int[] steps, List<ValuePoint> values) {
        checkpoint.run();
        if (depth >= maxDepth || ++steps[0] > MAX_STEPS || !active.add(input)) return false;
        try {
            FlowPoint point = input.point();
            if (input.value() instanceof Local local) {
                var reaching = definitions.reaching(point.method(), local, point.statement());
                boolean complete = !reaching.missing() && !reaching.statements().isEmpty();
                for (Stmt stmt : reaching.statements()) complete &= origins(new FlowPoint(point.method(), stmt, point.context())
                        .value(((AbstractDefinitionStmt) stmt).getRightOp()), factory, active, depth + 1, steps, values);
                return complete;
            }
            if (input.value() instanceof JCastExpr cast) return origins(point.value(cast.getOp()), factory, active, depth + 1, steps, values);
            if (input.value() instanceof JParameterRef || input.value() instanceof JThisRef) {
                var argument = argument(input);
                if (argument.isPresent()) return origins(argument.get(), factory, active, depth + 1, steps, values);
                values.add(input); return true;
            }
            if (input.value() instanceof JFieldRef field) {
                int before = values.size();
                for (FlowPoint write : program.writes(field.getFieldSignature())) for (ContextPath context : contexts(write.method())) {
                    origins(new FlowPoint(write.method(), write.statement(), context.context())
                                    .value(((AbstractDefinitionStmt) write.statement()).getRightOp()),
                            factory, active, depth + 1, steps, values);
                }
                if (values.size() == before) values.add(input);
                // 可以继续发现候选工厂，但字段实例别名与写入顺序仍需由上层保守处理。
                return false;
            }
            if (input.value() instanceof AbstractInvokeExpr invoke) {
                InvocationSite site = invocation(input, invoke);
                if (!factory.test(site)) {
                    var resolution = program.resolution(site);
                    List<JavaSootMethod> targets = resolution.applicationTargets();
                    if (!targets.isEmpty()) {
                        boolean complete = !resolution.hasExternalTargets(), returned = false;
                        // 未解析分支保留调用本身作为来源，不能只保留项目实现返回的对象。
                        if (resolution.hasExternalTargets()) values.add(input);
                        for (JavaSootMethod target : targets) for (Stmt stmt : target.getBody().getStmts()) if (stmt instanceof JReturnStmt result) {
                            returned = true;
                            complete &= origins(new FlowPoint(target, stmt, point.context().push(site)).value(result.getOp()),
                                    factory, active, depth + 1, steps, values);
                        }
                        return complete && returned;
                    }
                    values.add(input); return false;
                }
                values.add(input); return true;
            }
            values.add(input);
            return !(input.value() instanceof JFieldRef || input.value() instanceof JArrayRef);
        } finally { active.remove(input); }
    }

    /**
     * 从使用点之前逆向遍历过程间控制流。state 必须不可变；分支各自持有状态。
     * 自动进入应用方法的返回点、退出到对应实参调用点；框架仅解释它关心的操作。
     */
    public <S> void backward(FlowPoint start, S initial, Transfer<S> transfer, Consumer<FlowEnd<S>> sink) {
        record Work<S>(FlowPoint point, S state, Set<FlowPoint> visited, List<String> issues, boolean exceptional) {}
        Deque<Work<S>> work = new ArrayDeque<>();
        Consumer<Work<S>> previous = new Consumer<>() {
            public void accept(Work<S> item) {
                FlowPoint point = item.point();
                var graph = point.method().getBody().getControlFlowGraph();
                var normal = graph.predecessors(point.statement());
                var exceptional = graph.exceptionalPredecessors(point.statement());
                if (normal.isEmpty() && exceptional.isEmpty()) {
                    if (point.context().calls().isEmpty()) sink.accept(new FlowEnd<>(item.state(), End.NO_CALLER, item.issues()));
                    else {
                        var caller = point.context().calls().getLast();
                        accept(new Work<>(new FlowPoint(caller.method(), caller.statement(), point.context().parent()),
                                item.state(), item.visited(), item.issues(), false));
                    }
                }
                for (Stmt stmt : normal) work.addLast(new Work<>(new FlowPoint(point.method(), stmt, point.context()), item.state(), item.visited(), item.issues(), false));
                for (Stmt stmt : exceptional) {
                    var issues = new ArrayList<>(item.issues()); issues.add("exceptional predecessor: effects may not have completed");
                    work.addLast(new Work<>(new FlowPoint(point.method(), stmt, point.context()), item.state(), item.visited(), issues, true));
                }
            }
        };
        previous.accept(new Work<>(start, initial, Set.of(), List.of(), false));
        int steps = 0;
        while (!work.isEmpty()) {
            checkpoint.run();
            Work<S> item = work.removeLast();
            if (++steps > MAX_STEPS || work.size() > MAX_PATHS) {
                sink.accept(new FlowEnd<>(item.state(), End.LIMIT, item.issues()));
                incomplete("interprocedural control-flow budget exceeded at " + start.method().getSignature());
                break;
            }
            if (item.visited().contains(item.point())) {
                sink.accept(new FlowEnd<>(item.state(), End.CYCLE, item.issues())); continue;
            }
            Set<FlowPoint> visited = new HashSet<>(item.visited()); visited.add(item.point());
            Decision<S> decision = item.exceptional() ? Decision.skipCall(item.state()) : transfer.visit(item.point(), item.state());
            if (decision.stop()) { sink.accept(new FlowEnd<>(decision.state(), End.FOUND, item.issues())); continue; }
            var call = item.point().invocation(program.view());
            List<JavaSootMethod> targets = !decision.skipCall() && call.isPresent() ? program.targets(call.get()) : List.of();
            if (!targets.isEmpty()) {
                if (item.point().context().calls().size() >= maxDepth) {
                    sink.accept(new FlowEnd<>(decision.state(), End.LIMIT, item.issues())); continue;
                }
                boolean returned = false;
                for (JavaSootMethod target : targets) for (Stmt stmt : target.getBody().getStmts()) {
                    if (stmt instanceof ReturnStmt) {
                        returned = true;
                        work.addLast(new Work<>(new FlowPoint(target, stmt, item.point().context().push(call.orElseThrow())),
                                decision.state(), visited, item.issues(), false));
                    }
                }
                // 虚调用可能同时落到项目和依赖库。外部分支继续回溯调用前的候选状态，
                // 由框架 transfer 对相关对象的未知副作用作标记，不能被项目分支覆盖。
                if (returned && !program.resolution(call.orElseThrow()).hasExternalTargets()) continue;
            }
            previous.accept(new Work<>(item.point(), decision.state(), visited, item.issues(), false));
        }
    }
    public record ContextPath(CallContext context, End end) {
        public boolean complete() { return end == End.ENTRY || end == End.NO_CALLER; }
    }
    public enum Alias { NONE, MAY, MUST }

    public record Origins(List<ValuePoint> values, boolean complete) {
        public Origins { values = List.copyOf(values); }

        /** 基于来源集合的别名关系；信息不完整时不能证明两个对象不相交。 */
        public Alias aliasTo(Origins other) {
            boolean intersects = values.stream().anyMatch(other.values()::contains);
            if (complete && other.complete && intersects && values.size() == 1 && other.values.size() == 1)
                return Alias.MUST;
            if (!complete || !other.complete || intersects) return Alias.MAY;
            // 两个不同的入口引用也可能指向同一对象，尚无 points-to 约束时保留可能性。
            boolean input = isInput(values) && isInput(other.values);
            return input ? Alias.MAY : Alias.NONE;
        }
        private static boolean isInput(List<ValuePoint> values) {
            return values.stream().anyMatch(value -> value.value() instanceof JParameterRef || value.value() instanceof JThisRef);
        }
    }
    public enum End { ENTRY, NO_CALLER, FOUND, CYCLE, LIMIT }
    public record FlowEnd<S>(S state, End end, List<String> issues) { public FlowEnd { issues = List.copyOf(issues); } }
    @FunctionalInterface public interface Transfer<S> { Decision<S> visit(FlowPoint point, S state); }
    public record Decision<S>(S state, boolean stop, boolean skipCall) {
        public static <S> Decision<S> next(S state) { return new Decision<>(state, false, false); }
        public static <S> Decision<S> skipCall(S state) { return new Decision<>(state, false, true); }
        public static <S> Decision<S> stop(S state) { return new Decision<>(state, true, true); }
    }
}
