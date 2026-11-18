package org.u80dc.jsqlint.mybatis;

import org.u80dc.jsqlint.core.dataflow.*;
import org.u80dc.jsqlint.core.dataflow.FlowPoint.ValuePoint;
import org.u80dc.jsqlint.core.spi.TemplateSqlInput;
import org.u80dc.jsqlint.core.spi.InvocationSite;
import org.u80dc.jsqlint.core.semantics.JavaValueOperations;
import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr;
import sootup.core.jimple.common.constant.StringConstant;
import sootup.core.jimple.common.ref.JParameterRef;
import sootup.core.jimple.common.ref.JThisRef;
import sootup.core.jimple.common.expr.JSpecialInvokeExpr;
import sootup.java.core.JavaSootMethod;
import java.util.*;
import java.util.function.Function;

import static org.u80dc.jsqlint.core.dataflow.ValueTrace.*;

/** 将 MyBatis 参数对象与 ProviderContext 转换为通用值来源，不创建或调用目标对象。 @author u80dc */
final class ProviderArgumentModel implements ValueFlowModel {
    sealed interface Argument {
        record Value(TemplateSqlInput.Parameter parameter) implements Argument {}
        record Named(Map<String, TemplateSqlInput.Parameter> values) implements Argument {
            public Named { values = Collections.unmodifiableMap(new LinkedHashMap<>(values)); }
        }
        record Context() implements Argument {}
    }
    private final ProviderMethodResolver.Method provider;
    private final List<Argument> arguments;
    private final DataFlowAnalysis flow;
    private final CallContext parent;
    private final boolean called;
    private final String mapperType, mapperMethod;
    private final Optional<String> databaseId;
    private final Map<ValuePoint, List<String>> mapIssues = new HashMap<>();
    ProviderArgumentModel(ProviderMethodResolver.Method provider, List<Argument> arguments, DataFlowAnalysis flow,
                          CallContext parent, boolean called, String mapperType, String mapperMethod, Optional<String> databaseId) {
        this.provider = provider; this.arguments = List.copyOf(arguments); this.flow = flow; this.parent = parent; this.called = called;
        this.mapperType = mapperType; this.mapperMethod = mapperMethod; this.databaseId = databaseId;
    }
    private boolean root(ValuePoint input) {
        if (!input.point().method().getSignature().equals(provider.method().getSignature())) return false;
        var calls = input.point().context().calls();
        return called ? calls.size() == parent.calls().size() + 1 && calls.subList(0, parent.calls().size()).equals(parent.calls())
                && calls.getLast().invocation().getMethodSignature().equals(provider.method().getSignature()) : calls.isEmpty();
    }
    @Override public Optional<JavaSootMethod> invocation(InvocationSite site, CallContext context) {
        if (!(site.invocation() instanceof AbstractInstanceInvokeExpr instance) || instance instanceof JSpecialInvokeExpr
                || provider.method().isStatic()) return Optional.empty();
        var receiver = flow.origins(new FlowPoint(site.method(), site.statement(), context).value(instance.getBase()), ignored -> false);
        if (!receiver.complete() || receiver.values().size() != 1 || !(receiver.values().getFirst().value() instanceof JThisRef)
                || !root(receiver.values().getFirst())) return Optional.empty();
        var declared = flow.program().view().getMethod(site.invocation().getMethodSignature());
        if (declared.filter(method -> method.isPrivate() || method.isFinal()).isPresent()) return declared;
        return flow.program().resolveMethod(provider.providerType(), site.invocation().getMethodSignature().getSubSignature());
    }
    @Override public Optional<Node> resolve(ValuePoint input, Function<ValuePoint, Node> dependency) {
        if (input.value() instanceof JParameterRef parameter && root(input)) {
            var argument = arguments.get(parameter.getIndex());
            if (argument instanceof Argument.Value value)
                return Optional.of(node(input, Kind.PARAMETER, Operation.IDENTITY, "MyBatis Provider argument", List.of(source(value.parameter(), dependency))));
            if (argument instanceof Argument.Named named) {
                var values = new ArrayList<Node>();
                for (var entry : named.values().entrySet()) {
                    flow.checkpoint(); values.add(literal(input, entry.getKey())); values.add(source(entry.getValue(), dependency));
                }
                return Optional.of(node(input, Kind.PARAMETER, Operation.MAP_LITERAL, "MyBatis named parameter snapshot", values));
            }
        }
        if (!(input.value() instanceof AbstractInstanceInvokeExpr invoke)) return Optional.empty();
        String owner = invoke.getMethodSignature().getDeclClassType().toString(), name = invoke.getMethodSignature().getName();
        if (MapperParameterResolver.subtype(owner, "java.util.Map", flow.program().view())) {
            var receiver = flow.origins(input.point().value(invoke.getBase()), ignored -> false);
            for (var origin : receiver.values()) {
                if (!(origin.value() instanceof JParameterRef parameter) || !root(origin)
                        || !(arguments.get(parameter.getIndex()) instanceof Argument.Named named)) continue;
                var issues = mapIssues.computeIfAbsent(input, ignored -> mapIssues(input, receiver));
                if (!issues.isEmpty()) return Optional.of(new Node(input.snapshot(), Kind.UNRESOLVED, String.join("; ", issues), List.of()));
                if ((name.equals("get") || name.equals("getOrDefault") || name.equals("containsKey"))
                        && invoke.getArgCount() >= 1 && invoke.getArg(0) instanceof StringConstant key) {
                    var source = named.values().get(key.getValue());
                    if (name.equals("containsKey")) return Optional.of(literal(input, source != null));
                    if (source != null) return Optional.of(node(input, Kind.EXPRESSION, Operation.IDENTITY,
                            "MyBatis named argument: " + key.getValue(), List.of(source(source, dependency))));
                    if (name.equals("getOrDefault") && invoke.getArgCount() == 2) return Optional.of(node(input, Kind.EXPRESSION,
                            Operation.IDENTITY, "Map default argument", List.of(dependency.apply(input.point().value(invoke.getArg(1))))));
                    return Optional.of(new Node(input.snapshot(), Kind.UNRESOLVED, "Missing MyBatis Provider argument: " + key.getValue(), List.of()));
                }
                if (invoke.getArgCount() == 0 && (name.equals("size") || name.equals("isEmpty")))
                    return Optional.of(literal(input, name.equals("size") ? named.values().size() : named.values().isEmpty()));
            }
        }
        if (owner.equals("org.apache.ibatis.builder.annotation.ProviderContext") && invoke.getArgCount() == 0
                && context(input.point().value(invoke.getBase()))) {
            if (name.equals("getDatabaseId")) return Optional.of(literal(input, databaseId.orElse(null)));
        }
        if (invoke.getArgCount() == 0 && (owner.equals("java.lang.Class") || owner.equals("java.lang.reflect.Method"))) {
            for (var origin : flow.origins(input.point().value(invoke.getBase()), ignored -> false).values()) {
                if (!(origin.value() instanceof AbstractInstanceInvokeExpr metadata) || metadata.getArgCount() != 0
                        || !context(origin.point().value(metadata.getBase()))) continue;
                if (metadata.getMethodSignature().getName().equals("getMapperType")) {
                    if (name.equals("getName")) return Optional.of(literal(input, mapperType));
                    if (name.equals("getSimpleName")) return Optional.of(literal(input, mapperType.substring(Math.max(mapperType.lastIndexOf('.'), mapperType.lastIndexOf('$')) + 1)));
                }
                if (metadata.getMethodSignature().getName().equals("getMapperMethod") && name.equals("getName"))
                    return Optional.of(literal(input, mapperMethod));
            }
        }
        return Optional.empty();
    }
    private List<String> mapIssues(ValuePoint input, DataFlowAnalysis.Origins receiver) {
        if (!receiver.complete() || receiver.values().size() != 1) return List.of("MyBatis Provider parameter Map identity is uncertain");
        var issues = new LinkedHashSet<String>();
        flow.backward(input.point(), ObjectExposure.EMPTY, (point, exposure) -> {
            if (point.equals(receiver.values().getFirst().point())) return DataFlowAnalysis.Decision.stop(exposure);
            var site = point.invocation(flow.program().view()); boolean read = false;
            if (site.isPresent()) {
                var signature = site.get().invocation().getMethodSignature();
                String owner = signature.getDeclClassType().toString(), name = signature.getName();
                read = JavaValueOperations.supports(signature) || JavaValueOperations.scalarStringConcat(site.get().invocation())
                        || owner.equals("org.apache.ibatis.builder.annotation.ProviderContext")
                        || owner.equals("java.lang.Class") && Set.of("getName", "getSimpleName").contains(name)
                        || owner.equals("java.lang.reflect.Method") && name.equals("getName")
                        || Set.of("org.apache.ibatis.jdbc.SQL", "org.apache.ibatis.jdbc.AbstractSQL").contains(owner);
            }
            var history = exposure.before(flow, point, receiver, !read);
            return read ? DataFlowAnalysis.Decision.skipCall(history) : DataFlowAnalysis.Decision.next(history);
        }, end -> {
            issues.addAll(end.state().issues()); issues.addAll(end.issues());
            if (end.end() != DataFlowAnalysis.End.FOUND) issues.add("MyBatis Provider parameter Map history ended at " + end.end());
        });
        return List.copyOf(issues);
    }
    private boolean context(ValuePoint receiver) {
        return flow.origins(receiver, ignored -> false).values().stream().anyMatch(origin -> origin.value() instanceof JParameterRef parameter
                && root(origin) && arguments.get(parameter.getIndex()) instanceof Argument.Context);
    }
    private static Node source(TemplateSqlInput.Parameter parameter, Function<ValuePoint, Node> dependency) {
        return switch (parameter.source()) { case TemplateSqlInput.Source.At at -> dependency.apply(at.value());
            case TemplateSqlInput.Source.Trace trace -> trace.trace().root(); };
    }
    static Node node(ValuePoint point, Kind kind, Operation operation, String text, List<Node> inputs) {
        return new Node(point.snapshot(), kind, text, inputs, new Computation(operation, text));
    }
    static Node literal(ValuePoint point, Object value) {
        var variable = point.snapshot().withType(value == null ? "java.lang.Object" : value instanceof Boolean ? "boolean"
                : value instanceof Integer ? "int" : "java.lang.String");
        return new Node(variable, Kind.CONSTANT, "Provider metadata: " + value, List.of(),
                new Computation(value == null ? Operation.NULL : Operation.LITERAL, String.valueOf(value)));
    }
}
