package cn.jnu.zhangcs.jsqlint.analysis.parameter.trace;

import sootup.callgraph.EnhancedCallGraph;
import sootup.core.jimple.basic.Value;
import sootup.core.jimple.common.expr.AbstractInvokeExpr;
import sootup.core.jimple.common.stmt.JAssignStmt;
import sootup.core.jimple.common.stmt.JInvokeStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.SootClass;
import sootup.core.model.SootMethod;
import sootup.core.signatures.MethodSignature;
import sootup.core.typehierarchy.TypeHierarchy;
import sootup.core.types.ClassType;
import sootup.core.views.View;
import sootup.java.core.JavaSootUtils;

import java.util.*;

/**
 * 追踪通过调用链传递给目标方法参数的实际参数.
 *
 * @author zhangcs
 */
public final class ParameterTraceAnalyzer {

    private final View view;
    private final EnhancedCallGraph callGraph;
    private final int maxDepth;

    public ParameterTraceAnalyzer(View view, EnhancedCallGraph callGraph) {
        this(view, callGraph, 8);
    }

    /**
     * 创建指定最大追踪深度的实例。
     *
     * @param view      SootUp 类视图
     * @param callGraph 调用图
     * @param maxDepth  最大追踪深度，小于 1 时按 1 处理
     */
    public ParameterTraceAnalyzer(View view, EnhancedCallGraph callGraph, int maxDepth) {
        this.view = Objects.requireNonNull(view, "view");
        this.callGraph = Objects.requireNonNull(callGraph, "callGraph");
        this.maxDepth = Math.max(1, maxDepth);
    }

    /**
     * 对目标方法的指定形式参数执行传播链追踪。
     *
     * @param targetMethod  目标方法（sink）签名
     * @param parameterIndex 形式参数下标，从 0 开始
     * @return 追踪结果，包含传播链与诊断信息
     */
    public ParameterTraceResult analyze(MethodSignature targetMethod, int parameterIndex) {
        Objects.requireNonNull(targetMethod, "targetMethod");

        // 公开入口总是从 sink/目标方法的形式参数出发，例如 JdbcTemplate.query(...)[0]。
        MethodParameterRef target = MethodParameterRef.of(targetMethod, parameterIndex);
        List<String> diagnostics = new ArrayList<>();
        if (!target.isValid()) {
            diagnostics.add("Invalid parameter index: " + target.displayName());
            return new ParameterTraceResult(target, List.of(), diagnostics);
        }

        List<CallTrace> traces = traceParameter(
                target,
                List.of(),
                new LinkedHashSet<>(),
                0,
                diagnostics);
        return new ParameterTraceResult(target, traces, diagnostics);
    }

    private List<CallTrace> traceParameter(
            MethodParameterRef target,
            List<TraceStep> prefix,
            Set<TraceKey> visiting,
            int depth,
            List<String> diagnostics) {

        // 递归沿被调用方到调用方的参数依赖方向展开。深度与访问集合
        // 双重防护，保证 Service/Repository 之间的循环调用有界。
        if (depth > maxDepth) {
            diagnostics.add("Max trace depth reached at " + target.displayName());
            return prefix.isEmpty()
                    ? List.of()
                    : List.of(new CallTrace(prefix, new ValueModel.UnknownValue("max depth")));
        }

        TraceKey key = new TraceKey(target.method(), target.index());
        if (!visiting.add(key)) {
            diagnostics.add("Cycle detected at " + target.displayName());
            return prefix.isEmpty()
                    ? List.of()
                    : List.of(new CallTrace(prefix, new ValueModel.UnknownValue("cycle")));
        }

        try {
            Set<MethodSignature> callers = callersOf(target.method());
            if (callers.isEmpty()) {
                if (!prefix.isEmpty()) {
                    return List.of(new CallTrace(prefix,
                            new ValueModel.ParameterValue(target.index(), target.name(), target.type())));
                }
                diagnostics.add("No callers found for " + target.displayName());
                return List.of();
            }

            List<CallTrace> traces = new ArrayList<>();
            for (MethodSignature callerSignature : callers) {
                // 调用图中可能包含没有方法体的库方法或接口方法。它们可以
                // 作为边存在，但无法在这里被检查。
                Optional<? extends SootMethod> optionalCaller = view.getMethod(callerSignature);
                if (optionalCaller.isEmpty() || !optionalCaller.get().hasBody()) {
                    diagnostics.add("Caller has no body: " + callerSignature);
                    continue;
                }
                collectCallerTraces(optionalCaller.get(), target, prefix, visiting, depth, diagnostics, traces);
            }
            return traces;
        } finally {
            visiting.remove(key);
        }
    }

    private void collectCallerTraces(
            SootMethod caller,
            MethodParameterRef target,
            List<TraceStep> prefix,
            Set<TraceKey> visiting,
            int depth,
            List<String> diagnostics,
            List<CallTrace> traces) {

        List<Stmt> statements = caller.getBody().getStmts();
        ValueModelResolver resolver = new ValueModelResolver(caller);
        for (int i = 0; i < statements.size(); i++) {
            Stmt stmt = statements.get(i);
            Optional<AbstractInvokeExpr> invokeOptional = invokeOf(stmt);
            if (invokeOptional.isEmpty()) {
                continue;
            }

            AbstractInvokeExpr invoke = invokeOptional.get();
            if (!matchesCallee(invoke.getMethodSignature(), target.method())) {
                continue;
            }

            // 到这里 stmt 已是目标方法的一个具体调用点。被追踪的形式参数
            // 对应相同下标的实际参数。
            if (target.index() >= invoke.getArgCount()) {
                diagnostics.add("Argument index out of range at " + caller.getSignature() + ": " + stmt);
                continue;
            }

            Value actualArgument = invoke.getArg(target.index());
            // 在调用点之前解析实际参数，使局部别名、常量、数组与简单表达式
            // 都能被显式表示。
            ValueModelResolver.ResolvedValue resolvedArgument = resolver.resolveWithTrace(actualArgument, i);
            ValueModel argument = resolvedArgument.value();
            List<ValueFlowStep> valueFlow = new ArrayList<>();
            valueFlow.add(new ValueFlowStep(
                    caller.getSignature(),
                    i,
                    stmt.toString(),
                    ValueFlowStep.Kind.ARGUMENT_USE,
                    actualArgument.toString(),
                    argument));
            valueFlow.addAll(resolvedArgument.flow());
            TraceStep step = new TraceStep(
                    caller.getSignature(),
                    stmt.toString(),
                    invoke.getMethodSignature(),
                    target.index(),
                    argument,
                    valueFlow);
            List<TraceStep> nextPrefix = append(prefix, step);

            // 仅当解析出的实际参数确实依赖调用方的某个形式参数时才递归。
            // 这样避免了旧的“种子展开所有参数”回退策略，保证调用链可解释。
            Set<ValueModel.ParameterValue> dependencies = argument.parameterDependencies();
            if (dependencies.isEmpty()) {
                traces.add(new CallTrace(nextPrefix, argument));
                continue;
            }

            boolean addedUpstreamTrace = false;
            for (ValueModel.ParameterValue dependency : dependencies) {
                MethodParameterRef callerParameter = MethodParameterRef.of(
                        caller.getSignature(),
                        dependency.index(),
                        dependency.name());
                if (!callerParameter.isValid()) {
                    diagnostics.add("Ignored invalid caller parameter dependency: "
                            + callerParameter.displayName());
                    continue;
                }

                List<CallTrace> upstream = traceParameter(
                        callerParameter,
                        nextPrefix,
                        visiting,
                        depth + 1,
                        diagnostics);
                if (!upstream.isEmpty()) {
                    traces.addAll(upstream);
                    addedUpstreamTrace = true;
                }
            }

            if (!addedUpstreamTrace) {
                traces.add(new CallTrace(nextPrefix, argument));
            }
        }
    }

    private Set<MethodSignature> callersOf(MethodSignature target) {
        Set<MethodSignature> callers = new LinkedHashSet<>(callGraph.callSourcesTo(target));

        // 调用图可能漏掉私有辅助方法、接口/实现边，以及未从选定入口
        // 可达的方法。这里始终扫描应用类方法体作为补充，使每个具体调用
        // 点都能形成独立的追踪路径。
        view.getClasses()
                .filter(SootClass::isApplicationClass)
                .forEach(clazz -> clazz.getMethods().forEach(method -> {
                    if (method.hasBody() && containsCallTo(method, target)) {
                        callers.add(method.getSignature());
                    }
                }));
        return callers;
    }

    private boolean containsCallTo(SootMethod method, MethodSignature target) {
        for (Stmt stmt : method.getBody().getStmts()) {
            Optional<AbstractInvokeExpr> invoke = invokeOf(stmt);
            if (invoke.isPresent() && matchesCallee(invoke.get().getMethodSignature(), target)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesCallee(MethodSignature invokeSignature, MethodSignature expected) {
        // 优先精确签名匹配。第二个分支处理常见的接口/实现边，但仅当
        // 声明类处于同一类型继承层次时才成立。这样可避免把 Mapper#selectById
        // 与 ServiceImpl#selectById 这类无关方法误判为同一调用目标。
        if (JavaSootUtils.equals(invokeSignature, expected)) {
            return true;
        }
        if (!invokeSignature.getName().equals(expected.getName())
                || !invokeSignature.getSubSignature().equals(expected.getSubSignature())) {
            return false;
        }
        return hasCompatibleDeclaringType(invokeSignature, expected);
    }

    private boolean hasCompatibleDeclaringType(MethodSignature invokeSignature, MethodSignature expected) {
        ClassType invokeType = invokeSignature.getDeclClassType();
        ClassType expectedType = expected.getDeclClassType();
        if (invokeType.getFullyQualifiedName().equals(expectedType.getFullyQualifiedName())) {
            return true;
        }

        try {
            TypeHierarchy hierarchy = view.getTypeHierarchy();
            if (!hierarchy.contains(invokeType) || !hierarchy.contains(expectedType)) {
                return false;
            }
            return hierarchy.isSubtype(invokeType, expectedType)
                    || hierarchy.isSubtype(expectedType, invokeType);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private Optional<AbstractInvokeExpr> invokeOf(Stmt stmt) {
        if (stmt instanceof JAssignStmt assign) {
            return assign.getInvokeExpr();
        }
        if (stmt instanceof JInvokeStmt invokeStmt) {
            return invokeStmt.getInvokeExpr();
        }
        return Optional.empty();
    }

    private List<TraceStep> append(List<TraceStep> prefix, TraceStep step) {
        List<TraceStep> result = new ArrayList<>(prefix);
        result.add(step);
        return List.copyOf(result);
    }

    private record TraceKey(MethodSignature method, int parameterIndex) {
    }
}
