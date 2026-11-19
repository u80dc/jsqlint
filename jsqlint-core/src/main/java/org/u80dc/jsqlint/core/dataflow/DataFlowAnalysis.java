package org.u80dc.jsqlint.core.dataflow;

import org.u80dc.jsqlint.core.spi.InvocationSite;
import sootup.core.jimple.common.*;
import sootup.core.jimple.common.constant.*;
import sootup.core.jimple.common.expr.*;
import sootup.core.jimple.common.ref.*;
import sootup.core.jimple.common.stmt.*;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootMethod;
import org.u80dc.jsqlint.core.semantics.JavaValueOperations;
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
    private final NormalCompletion completion;
    private final int maxCallDepth, maxTraceDepth, maxTraceNodes;
    private final Runnable checkpoint;
    private final BiConsumer<String, String> incomplete;
    private final Optional<MethodSignature> entry;
    private final Set<MethodSignature> scopeMethods;
    private final Set<String> reported = new HashSet<>();
    private final Map<MethodSignature, List<ContextPath>> contextCache = new HashMap<>();
    private record ModeledCall(InvocationSite invocation, CallContext parent) {}
    private final Map<ModeledCall, ValueFlowModel> models = new HashMap<>();
    private final Map<ModeledCall, JavaSootMethod> implicitTargets = new HashMap<>();
    private final Set<ModeledCall> resolvingReceivers = new HashSet<>();
    private ValueFlowModel localModel;

    public DataFlowAnalysis(ProgramIndex program, int maxDepth, int cacheSize, Runnable checkpoint,
                            BiConsumer<String, String> incomplete) {
        this(program, maxDepth, maxDepth, MAX_STEPS, cacheSize, checkpoint, incomplete);
    }
    public DataFlowAnalysis(ProgramIndex program, int maxCallDepth, int maxTraceDepth, int maxTraceNodes,
                            int cacheSize, Runnable checkpoint, BiConsumer<String, String> incomplete) {
        this(program, maxCallDepth, maxTraceDepth, maxTraceNodes, cacheSize, checkpoint, incomplete,
                Optional.empty(), Set.of());
    }
    /** 指定入口时，仅保留入口范围内的调用上下文；旧构造器继续枚举全程序上游。 */
    public DataFlowAnalysis(ProgramIndex program, int maxCallDepth, int maxTraceDepth, int maxTraceNodes,
                            int cacheSize, Runnable checkpoint, BiConsumer<String, String> incomplete,
                            Optional<MethodSignature> entry, Set<MethodSignature> scopeMethods) {
        if (maxCallDepth < 1 || maxTraceDepth < 1 || maxTraceNodes < 1)
            throw new IllegalArgumentException("Trace budgets must be positive");
        this.program = program; this.maxCallDepth = maxCallDepth; this.maxTraceDepth = maxTraceDepth;
        this.maxTraceNodes = maxTraceNodes; this.checkpoint = checkpoint; this.incomplete = incomplete;
        this.entry = Objects.requireNonNull(entry); this.scopeMethods = Set.copyOf(scopeMethods);
        definitions = new LocalDefinitions(cacheSize, checkpoint);
        completion = new NormalCompletion(this, maxCallDepth);
    }
    private DataFlowAnalysis(DataFlowAnalysis source) {
        program = source.program; definitions = source.definitions; maxCallDepth = source.maxCallDepth;
        maxTraceDepth = source.maxTraceDepth; maxTraceNodes = source.maxTraceNodes; checkpoint = source.checkpoint;
        incomplete = source.incomplete; models.putAll(source.models); implicitTargets.putAll(source.implicitTargets);
        entry = source.entry; scopeMethods = source.scopeMethods;
        completion = new NormalCompletion(this, maxCallDepth);
    }
    public ProgramIndex program() { return program; }
    public void checkpoint() { checkpoint.run(); }
    /** 仅在已有证据证明语句不能正常完成时返回 true；未知目标和递归保留可能性。 */
    public boolean cannotCompleteNormally(FlowPoint point) { return completion.statement(point) == NormalCompletion.Status.IMPOSSIBLE; }
    /** 已知目标均无法返回，但未穷尽的运行时分派仍可能正常返回；联合可行性必须保留未知。 */
    public boolean requiresUnresolvedDispatch(FlowPoint point) { return completion.statement(point) == NormalCompletion.Status.OPEN_TARGETS; }
    /** 可重演的数组分配、读取和写入正常完成条件。 */
    public List<ValueTrace> normalCompletionConditions(FlowPoint point) { return completion.conditions(point); }
    /** 联合路径已证明调用进入或正常返回时，保留调用语句自身的局部安全前提。 */
    public List<ValueTrace> statementSafetyConditions(FlowPoint point) { return completion.statementSafetyConditions(point); }
    boolean arrayAccessFails(ValuePoint input, JArrayRef array) { return completion.arrayFails(input, array); }
    public void incomplete(String message) {
        if (reported.add(message)) incomplete.accept("DATA_FLOW_INCOMPLETE", message);
    }

    /** 穷举项目内调用路径，直到 main 或没有调用方的方法；不在 public 方法处提前停止。 */
    public List<ContextPath> contexts(JavaSootMethod method) {
        return contextCache.computeIfAbsent(method.getSignature(), ignored -> findContexts(method));
    }
    private List<ContextPath> findContexts(JavaSootMethod method) {
        if (entry.isPresent() && !scopeMethods.contains(method.getSignature())) return List.of();
        record Pending(JavaSootMethod method, List<InvocationSite> reversed, Set<MethodSignature> active) {}
        Deque<Pending> pending = new ArrayDeque<>();
        pending.add(new Pending(method, List.of(), Set.of()));
        List<ContextPath> result = new ArrayList<>();
        while (!pending.isEmpty()) {
            checkpoint.run();
            Pending next = pending.removeFirst();
            End end = null;
            var callers = program.callers().getOrDefault(next.method().getSignature(), List.of());
            if (entry.isPresent()) callers = callers.stream()
                    .filter(call -> scopeMethods.contains(call.method().getSignature())).toList();
            // 总路径容量耗尽时留一个显式截断结果；正常终点不消耗额外的调用展开深度。
            if (result.size() >= MAX_PATHS - 1 && !pending.isEmpty()) end = End.LIMIT;
            else if (entry.isPresent() && entry.get().equals(next.method().getSignature())) end = End.ENTRY;
            else if (entry.isEmpty() && next.method().isMain(program.view().getIdentifierFactory())) end = End.ENTRY;
            else if (entry.isPresent() && callers.isEmpty()) continue;
            else if (callers.isEmpty()) end = End.NO_CALLER;
            else if (next.active().contains(next.method().getSignature())) end = End.CYCLE;
            else if (next.reversed().size() >= maxCallDepth || result.size() + pending.size() >= MAX_PATHS) end = End.LIMIT;
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
        return new ValueTrace(input.snapshot(program), input.point().context().snapshot(), trace(input, new HashSet<>(), 0, steps));
    }
    /** 对独立定义应用只读模型，不改变其他调用上下文。 */
    public ValueTrace trace(ValuePoint input, ValueFlowModel model) { return withModel(ignored -> model).trace(input); }
    /** 独立定义的模型与其全部辅助查询共用同一个隔离上下文。 */
    public DataFlowAnalysis withModel(Function<DataFlowAnalysis, ValueFlowModel> factory) {
        var analysis = new DataFlowAnalysis(this); analysis.localModel = Objects.requireNonNull(factory.apply(analysis)); return analysis;
    }

    /** 显式目标和框架已知接收者优先；其他调用继续使用保守 CHA 分派。 */
    public ProgramIndex.CallResolution resolution(InvocationSite site, CallContext context) {
        var selected = implicitTargets.get(new ModeledCall(site, context));
        if (selected == null) {
            var calls = context.calls();
            for (int i = calls.size() - 1; i >= 0 && selected == null; i--) {
                var model = models.get(new ModeledCall(calls.get(i), new CallContext(calls.subList(0, i))));
                if (model != null) selected = model.invocation(site, context).orElse(null);
            }
            if (selected == null && localModel != null) selected = localModel.invocation(site, context).orElse(null);
        }
        if (selected != null) return selected.hasBody() ? new ProgramIndex.CallResolution(List.of(selected), false)
                : new ProgramIndex.CallResolution(List.of(), true);
        var indexed = program.resolution(site);
        if (indexed.targetsComplete() || !(site.invocation() instanceof AbstractInstanceInvokeExpr instance)) return indexed;
        var key = new ModeledCall(site, context);
        if (!resolvingReceivers.add(key)) return indexed;
        try {
            var receiver = origins(new FlowPoint(site.method(), site.statement(), context).value(instance.getBase()), ignored -> false);
            if (!receiver.complete() || receiver.values().isEmpty()
                    || receiver.values().stream().anyMatch(value -> !(value.value() instanceof JNewExpr))) return indexed;
            var targets = new LinkedHashSet<JavaSootMethod>();
            boolean external = false, complete = true;
            for (var value : receiver.values()) {
                var resolved = program.resolution(site, ((JNewExpr) value.value()).getType());
                targets.addAll(resolved.applicationTargets());
                external |= resolved.hasExternalTargets(); complete &= resolved.targetsComplete();
            }
            return new ProgramIndex.CallResolution(new ArrayList<>(targets), external, complete);
        } finally { resolvingReceivers.remove(key); }
    }

    /** 将框架隐式调用登记为逻辑调用；形参由 model 映射，目标方法无需实际执行。 */
    public InvocationSite implicitInvocation(FlowPoint caller, JavaSootMethod target, ValueFlowModel model) {
        var expression = new JStaticInvokeExpr(target.getSignature(), List.of());
        var site = new InvocationSite(caller.method(), caller.statement(), program.statementIndex(caller.method(), caller.statement()),
                expression, program.view());
        program.registerImplicitInvocation(site);
        var key = new ModeledCall(site, caller.context()); models.put(key, Objects.requireNonNull(model)); implicitTargets.put(key, target);
        return site;
    }

    private Optional<ValueTrace.Node> modeled(ValuePoint input, Function<ValuePoint, ValueTrace.Node> dependency) {
        var calls = input.point().context().calls();
        for (int i = calls.size() - 1; i >= 0; i--) {
            var model = models.get(new ModeledCall(calls.get(i), new CallContext(calls.subList(0, i))));
            if (model != null) {
                var result = model.resolve(input, dependency); if (result.isPresent()) return result;
            }
        }
        return localModel == null ? Optional.empty() : localModel.resolve(input, dependency);
    }
    private ValueTrace.Node trace(ValuePoint input, Set<ValuePoint> active, int depth, int[] steps) {
        checkpoint.run();
        if (++steps[0] > maxTraceNodes) return leaf(input, Kind.LIMIT, "data dependency node budget exceeded");
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
            var modeled = modeled(input, dependency -> depth >= maxTraceDepth ? leaf(input, Kind.LIMIT, "modeled value depth budget exceeded")
                    : trace(dependency, active, depth + 1, steps));
            if (modeled.isPresent()) {
                if (depth >= maxTraceDepth) return leaf(input, Kind.LIMIT, "modeled value depth budget exceeded");
                return modeled.get();
            }
            // 已知字面量或入口输入是正常终点，即使恰好处于深度边界也无需继续展开。
            if ((value instanceof JParameterRef || value instanceof JThisRef) && argument(input).isEmpty())
                return leaf(input, (entry.isPresent() && entry.get().equals(point.method().getSignature())
                        || entry.isEmpty() && point.method().isMain(program.view().getIdentifierFactory())) ? Kind.ENTRY : Kind.NO_CALLER,
                        "project input: " + value);
            if (depth >= maxTraceDepth) return leaf(input, Kind.LIMIT, "data dependency depth budget exceeded");
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
                                ? computed(definition, Kind.ALLOCATION, Operation.STRING_COPY, "String constructor", inputs)
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
                for (ValuePoint source : read.values()) {
                    ValueTrace.Node element = trace(source, active, depth + 1, steps);
                    if (source.point().statement() instanceof AbstractDefinitionStmt definition && definition.getLeftOp() instanceof JArrayRef written) {
                        Optional<ValuePoint> length = completion.arrayLength(source.point(), written);
                        if (length.isPresent()) element = computed(source, Kind.ARRAY_ELEMENT, Operation.ARRAY_ACCESS_CHECK,
                                "local array write", List.of(element, trace(source.point().value(written.getIndex()), active, depth + 1, steps),
                                        trace(length.get(), active, depth + 1, steps)));
                    }
                    if (read.length().isPresent()) element = computed(source, Kind.ARRAY_ELEMENT, Operation.ARRAY_ACCESS_CHECK,
                            "local array access", List.of(element, children.getFirst(), trace(read.length().get(), active, depth + 1, steps)));
                    children.add(element);
                }
                for (String issue : read.issues()) children.add(leaf(input, Kind.UNRESOLVED, issue));
                return computed(input, Kind.ARRAY_ELEMENT, Operation.ARRAY_READ, "reaching array element writes", children);
            }
            if (value instanceof JCastExpr cast && cast.getType().toString().equals("java.lang.String"))
                return computed(input, Kind.EXPRESSION, Operation.STRING_CAST, "String cast",
                        List.of(trace(point.value(cast.getOp()), active, depth + 1, steps)));
            if (value instanceof JCastExpr cast)
                return computed(input, Kind.EXPRESSION, Operation.CAST, cast.getType().toString(),
                        List.of(trace(point.value(cast.getOp()), active, depth + 1, steps)));
            if (value instanceof AbstractBinopExpr binary)
                return computed(input, Kind.EXPRESSION, Operation.BINARY, binary.getSymbol().trim(), List.of(
                        trace(point.value(binary.getOp1()), active, depth + 1, steps),
                        trace(point.value(binary.getOp2()), active, depth + 1, steps)));
            if (value instanceof JNegExpr negation)
                return computed(input, Kind.EXPRESSION, Operation.NEGATE, "negate",
                        List.of(trace(point.value(negation.getOp()), active, depth + 1, steps)));
            if (value instanceof AbstractInvokeExpr invoke) {
                if (cannotCompleteNormally(point)) return leaf(input, Kind.UNRESOLVED, "Invocation cannot return normally");
                if (invoke instanceof JDynamicInvokeExpr dynamic && dynamic.getBootstrapMethodSignature()
                        .getDeclClassType().toString().equals("java.lang.invoke.StringConcatFactory"))
                    return concat(input, dynamic, active, depth, steps);
                var modeledInvocation = JavaValueOperations.invocation(invoke);
                if (modeledInvocation.isPresent()) {
                    var semantic = modeledInvocation.get();
                    var inputs = semantic.operands().stream().map(operand -> trace(point.value(operand), active, depth + 1, steps)).toList();
                    var variable = input.snapshot(program);
                    if (semantic.computation().operation() == Operation.STRINGIFY) variable = variable.withType("java.lang.String");
                    String detail = semantic.computation().operation() == Operation.CALL
                            ? invoke.getMethodSignature().toString() : semantic.computation().text();
                    return new ValueTrace.Node(variable, Kind.EXPRESSION, detail, inputs, semantic.computation());
                }
                InvocationSite site = invocation(input, invoke);
                var resolution = resolution(site, point.context());
                List<JavaSootMethod> targets = resolution.applicationTargets();
                if (!targets.isEmpty()) {
                    if (point.context().calls().size() >= maxCallDepth)
                        return leaf(input, Kind.LIMIT, "return value call depth budget exceeded");
                    List<ValueTrace.Node> children = new ArrayList<>();
                    for (JavaSootMethod target : targets) for (Stmt stmt : target.getBody().getStmts()) {
                        if (stmt instanceof JReturnStmt returned) children.add(trace(new FlowPoint(target, stmt,
                                point.context().push(site)).value(returned.getOp()), active, depth + 1, steps));
                    }
                    if (resolution.hasExternalTargets()) children.add(node(input, Kind.EXTERNAL_CALL,
                            "external or unresolved dispatch alternative: " + invoke.getMethodSignature(),
                            operands(input, invoke.getUses(), active, depth, steps)));
                    if (!children.isEmpty()) return new ValueTrace.Node(input.snapshot(program), Kind.RETURN, "return values (CHA candidates)", children,
                            new ValueTrace.Computation(Operation.ALTERNATIVES, "return values (CHA candidates)", Optional.of(invoke.getMethodSignature())));
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
        var variable = input.snapshot(program);
        // 拼接配方中的 stringify 是合成操作，结果为 String；原始操作数类型保留在输入节点中。
        if (operation == Operation.STRINGIFY) variable = variable.withType("java.lang.String");
        return new ValueTrace.Node(variable, kind, text, children, new ValueTrace.Computation(operation, text));
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
        return new ValueTrace.Node(point.snapshot(program), kind, detail, inputs);
    }
    private InvocationSite invocation(ValuePoint input, AbstractInvokeExpr invocation) {
        FlowPoint point = input.point();
        return new InvocationSite(point.method(), point.statement(), program.statementIndex(point.method(), point.statement()), invocation, program.view());
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
        if (++steps[0] > maxTraceNodes || !active.add(input)) return false;
        try {
            FlowPoint point = input.point();
            // 引用身份的终点也先于展开预算检查；工厂调用本身无需进入方法体。
            if (input.value() instanceof Constant || input.value() instanceof JNewExpr
                    || input.value() instanceof JNewArrayExpr || input.value() instanceof JNewMultiArrayExpr
                    || (input.value() instanceof JParameterRef || input.value() instanceof JThisRef) && argument(input).isEmpty()
                    || input.value() instanceof AbstractInvokeExpr invoke && factory.test(invocation(input, invoke))) {
                values.add(input); return true;
            }
            if (depth >= maxTraceDepth) return false;
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
                    var resolution = resolution(site, point.context());
                    List<JavaSootMethod> targets = resolution.applicationTargets();
                    if (!targets.isEmpty()) {
                        if (point.context().calls().size() >= maxCallDepth) { values.add(input); return false; }
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
        record Work<S>(FlowPoint point, S state, Set<FlowPoint> visited, List<String> issues, boolean exceptional, List<ControlFlowEdge> path) {}
        Deque<Work<S>> work = new ArrayDeque<>();
        Consumer<Work<S>> previous = new Consumer<>() {
            public void accept(Work<S> item) {
                FlowPoint point = item.point();
                var graph = point.method().getBody().getControlFlowGraph();
                var normal = graph.predecessors(point.statement());
                var exceptional = graph.exceptionalPredecessors(point.statement());
                if (normal.isEmpty() && exceptional.isEmpty()) {
                    if (point.context().calls().isEmpty()) sink.accept(new FlowEnd<>(item.state(), End.NO_CALLER, item.issues(), item.path()));
                    else {
                        var caller = point.context().calls().getLast();
                        accept(new Work<>(new FlowPoint(caller.method(), caller.statement(), point.context().parent()),
                                item.state(), item.visited(), item.issues(), false, item.path()));
                    }
                }
                for (Stmt stmt : normal) {
                    FlowPoint predecessor = new FlowPoint(point.method(), stmt, point.context());
                    var path = new ArrayList<>(item.path()); path.add(new ControlFlowEdge(predecessor, point, false));
                    work.addLast(new Work<>(predecessor, item.state(), item.visited(), item.issues(), false, List.copyOf(path)));
                }
                for (Stmt stmt : exceptional) {
                    var issues = new ArrayList<>(item.issues()); issues.add("exceptional predecessor: effects may not have completed");
                    FlowPoint predecessor = new FlowPoint(point.method(), stmt, point.context());
                    var path = new ArrayList<>(item.path()); path.add(new ControlFlowEdge(predecessor, point, true));
                    work.addLast(new Work<>(predecessor, item.state(), item.visited(), issues, true, List.copyOf(path)));
                }
            }
        };
        previous.accept(new Work<>(start, initial, Set.of(), List.of(), false, List.of()));
        int steps = 0;
        while (!work.isEmpty()) {
            checkpoint.run();
            Work<S> item = work.removeLast();
            if (++steps > MAX_STEPS || work.size() > MAX_PATHS) {
                sink.accept(new FlowEnd<>(item.state(), End.LIMIT, item.issues(), item.path()));
                incomplete("interprocedural control-flow budget exceeded at " + start.method().getSignature());
                break;
            }
            if (item.visited().contains(item.point())) {
                sink.accept(new FlowEnd<>(item.state(), End.CYCLE, item.issues(), item.path())); continue;
            }
            if (!item.exceptional() && cannotCompleteNormally(item.point())) continue;
            Set<FlowPoint> visited = new HashSet<>(item.visited()); visited.add(item.point());
            Decision<S> decision = item.exceptional() ? Decision.skipCall(item.state()) : transfer.visit(item.point(), item.state());
            if (decision.stop()) { sink.accept(new FlowEnd<>(decision.state(), End.FOUND, item.issues(), item.path())); continue; }
            var call = item.point().invocation(program);
            List<JavaSootMethod> targets = !decision.skipCall() && call.isPresent() ? resolution(call.get(), item.point().context()).applicationTargets() : List.of();
            if (!targets.isEmpty()) {
                if (item.point().context().calls().size() >= maxCallDepth) {
                    sink.accept(new FlowEnd<>(decision.state(), End.LIMIT, item.issues(), item.path())); continue;
                }
                boolean returned = false;
                for (JavaSootMethod target : targets) for (Stmt stmt : target.getBody().getStmts()) {
                    if (stmt instanceof ReturnStmt) {
                        returned = true;
                        work.addLast(new Work<>(new FlowPoint(target, stmt, item.point().context().push(call.orElseThrow())),
                                decision.state(), visited, item.issues(), false, item.path()));
                    }
                }
                // 外部目标或尚未穷尽的分派仍可能正常返回，必须保留调用前的候选来源；
                // 联合路径检查会将依赖未知分派的返回标为 UNKNOWN。
                if (!resolution(call.orElseThrow(), item.point().context()).hasExternalTargets()
                        && !requiresUnresolvedDispatch(item.point())) {
                    if (!returned) {
                        var issues = new ArrayList<>(item.issues()); issues.add("Application normal return is unresolved");
                        sink.accept(new FlowEnd<>(decision.state(), End.LIMIT, issues, item.path()));
                    }
                    continue;
                }
            }
            previous.accept(new Work<>(item.point(), decision.state(), visited, item.issues(), false, item.path()));
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
    public record FlowEnd<S>(S state, End end, List<String> issues, List<ControlFlowEdge> path) {
        public FlowEnd { issues = List.copyOf(issues); path = List.copyOf(path); }
        public FlowEnd(S state, End end, List<String> issues) { this(state, end, issues, List.of()); }
    }
    @FunctionalInterface public interface Transfer<S> { Decision<S> visit(FlowPoint point, S state); }
    public record Decision<S>(S state, boolean stop, boolean skipCall) {
        public static <S> Decision<S> next(S state) { return new Decision<>(state, false, false); }
        public static <S> Decision<S> skipCall(S state) { return new Decision<>(state, false, true); }
        public static <S> Decision<S> stop(S state) { return new Decision<>(state, true, true); }
    }
}
