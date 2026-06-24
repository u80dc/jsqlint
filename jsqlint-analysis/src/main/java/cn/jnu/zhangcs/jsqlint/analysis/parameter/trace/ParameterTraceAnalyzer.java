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

    public ParameterTraceAnalyzer(View view, EnhancedCallGraph callGraph, int maxDepth) {
        this.view = Objects.requireNonNull(view, "view");
        this.callGraph = Objects.requireNonNull(callGraph, "callGraph");
        this.maxDepth = Math.max(1, maxDepth);
    }

    public ParameterTraceResult analyze(MethodSignature targetMethod, int parameterIndex) {
        Objects.requireNonNull(targetMethod, "targetMethod");

        // The public entry point always starts from a formal parameter of the
        // sink/target method, for example JdbcTemplate.query(...)[0].
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

        // Recursion follows parameter dependencies from callee to caller. Depth
        // and visiting guards keep recursive service/repository cycles bounded.
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
                // A call graph may include library/interface methods without
                // bodies. They are useful as edges but cannot be inspected here.
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

            // At this point stmt is a concrete call site of the target method.
            // The tracked formal parameter maps to the same actual argument index.
            if (target.index() >= invoke.getArgCount()) {
                diagnostics.add("Argument index out of range at " + caller.getSignature() + ": " + stmt);
                continue;
            }

            Value actualArgument = invoke.getArg(target.index());
            // Resolve the actual argument before the call site, so local aliases,
            // constants, arrays, and simple expressions are represented explicitly.
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

            // Only recurse when the resolved actual argument proves a dependency on a
            // formal parameter of the caller. This avoids the old "seed all args"
            // fallback and keeps call chains explainable.
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

        // The call graph can miss private helper methods, interface/implementation
        // edges, or methods that are not reachable from the chosen entry points.
        // Always scan application bodies as a supplement so every concrete call
        // site can become its own trace path.
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
        // Exact signature match is preferred. The second branch handles common
        // interface/implementation edges, but only when declaring classes are in
        // the same type hierarchy. This avoids treating unrelated methods such as
        // Mapper#selectById and ServiceImpl#selectById as the same call target.
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
