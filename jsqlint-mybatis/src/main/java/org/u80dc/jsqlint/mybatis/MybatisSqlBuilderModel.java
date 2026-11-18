package org.u80dc.jsqlint.mybatis;

import org.apache.ibatis.jdbc.SQL;
import org.u80dc.jsqlint.core.dataflow.*;
import org.u80dc.jsqlint.core.dataflow.FlowPoint.ValuePoint;
import sootup.core.jimple.common.Local;
import sootup.core.jimple.common.constant.IntConstant;
import sootup.core.jimple.common.expr.*;
import sootup.core.jimple.common.ref.JArrayRef;
import sootup.core.types.ClassType;
import org.u80dc.jsqlint.core.spi.InvocationSite;
import org.u80dc.jsqlint.core.semantics.JavaValueOperations;
import sootup.java.core.JavaSootMethod;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

import static org.u80dc.jsqlint.core.dataflow.DataFlowAnalysis.*;
import static org.u80dc.jsqlint.core.dataflow.ValueTrace.*;

/** MyBatis SQL 构造器的显式语义模型；只调用分析器自己的框架构造器，不执行目标代码。 @author u80dc */
final class MybatisSqlBuilderModel implements ValueFlowModel {
    private static final String SQL_TYPE = "org.apache.ibatis.jdbc.SQL", ABSTRACT_SQL = "org.apache.ibatis.jdbc.AbstractSQL";
    private static final Pattern ARGUMENT = Pattern.compile("__JSQLINT_BUILDER_ARGUMENT_\\d+__");
    private static final Set<String> CLAUSES = Set.of("SELECT", "SELECT_DISTINCT", "FROM", "WHERE", "JOIN", "INNER_JOIN",
            "LEFT_OUTER_JOIN", "RIGHT_OUTER_JOIN", "OUTER_JOIN", "GROUP_BY", "HAVING", "ORDER_BY", "UPDATE", "SET",
            "INSERT_INTO", "VALUES", "INTO_COLUMNS", "INTO_VALUES", "DELETE_FROM", "AND", "OR", "ADD_ROW",
            "LIMIT", "OFFSET", "OFFSET_ROWS", "FETCH_FIRST_ROWS_ONLY");
    private final DataFlowAnalysis flow;
    MybatisSqlBuilderModel(DataFlowAnalysis flow) { this.flow = flow; }

    @Override public Optional<Node> resolve(ValuePoint input, Function<ValuePoint, Node> dependency) {
        if (!(input.value() instanceof AbstractInstanceInvokeExpr invocation) || invocation.getArgCount() != 0
                || !invocation.getMethodSignature().getName().equals("toString")
                || !builder(invocation.getBase().getType().toString()) || overridden(invocation)) return Optional.empty();
        var identity = origins(input.point().value(invocation.getBase()));
        var issues = new ArrayList<String>();
        if (!identity.complete() || identity.values().size() != 1 || !(identity.values().getFirst().value() instanceof JNewExpr))
            issues.add("MyBatis SQL builder identity is unresolved");
        issues.addAll(subclassIssues(identity));
        var alternatives = new ArrayList<Node>();
        flow.backward(input.point(), new State(List.of(), issues, ObjectExposure.EMPTY), (point, state) -> {
            if (identity.values().size() == 1 && point.equals(identity.values().getFirst().point())) return Decision.stop(state);
            var site = point.invocation(flow.program().view());
            if (site.isEmpty()) return Decision.next(state.exposure(state.exposure().before(flow, point, identity, false)));
            var call = site.get().invocation();
            if (call instanceof AbstractInstanceInvokeExpr instance && builder(instance.getBase().getType().toString())) {
                var receiver = origins(point.value(instance.getBase()));
                Alias alias = identity.aliasTo(receiver);
                if (alias == Alias.NONE) return Decision.next(state);
                String name = call.getMethodSignature().getName();
                if (name.equals("<init>")) {
                    String owner = call.getMethodSignature().getDeclClassType().toString();
                    if (owner.equals(SQL_TYPE) || owner.equals(ABSTRACT_SQL)) return Decision.skipCall(state);
                    return flow.program().targets(site.get()).isEmpty()
                            ? Decision.skipCall(state.issue("MyBatis SQL subclass constructor body is unavailable")) : Decision.next(state);
                }
                if (CLAUSES.contains(name) && !overridden(instance)) {
                    if (state.clauses().size() >= 256) return Decision.stop(state.issue("MyBatis SQL builder clause budget exceeded"));
                    try {
                        var values = arguments(point, call, dependency);
                        var clauses = new ArrayList<>(state.clauses()); clauses.add(new Clause(name, values));
                        var next = new State(clauses, state.issues(), state.exposure());
                        return Decision.skipCall(alias == Alias.MUST ? next : next.issue("MyBatis SQL builder mutation may target another object"));
                    } catch (IllegalArgumentException error) { return Decision.skipCall(state.issue(error.getMessage())); }
                }
                if (name.equals("toString")) return Decision.skipCall(state);
                return Decision.next(state.issue("Unmodeled MyBatis SQL builder mutation: " + call.getMethodSignature()));
            }
            String owner = call.getMethodSignature().getDeclClassType().toString(), name = call.getMethodSignature().getName();
            boolean pure = JavaValueOperations.supports(call.getMethodSignature()) || JavaValueOperations.scalarStringConcat(call)
                    || owner.equals("org.apache.ibatis.builder.annotation.ProviderContext")
                    || owner.equals("java.lang.Class") && Set.of("getName", "getSimpleName").contains(name)
                    || owner.equals("java.lang.reflect.Method") && name.equals("getName");
            return Decision.next(state.exposure(state.exposure().before(flow, point, identity, !pure)));
        }, end -> {
            var warnings = new ArrayList<>(end.state().issues()); warnings.addAll(end.issues());
            warnings.addAll(end.state().exposure().issues());
            if (end.end() != End.FOUND) warnings.add("MyBatis SQL builder history ended at " + end.end());
            alternatives.add(render(input, end.state().clauses(), warnings, end.path()));
        });
        if (alternatives.isEmpty()) alternatives.add(unresolved(input, "MyBatis SQL builder history is unavailable"));
        return Optional.of(ProviderArgumentModel.node(input, Kind.EXPRESSION, Operation.ALTERNATIVES,
                "MyBatis SQL builder path alternatives", alternatives));
    }

    private List<Node> arguments(FlowPoint point, AbstractInvokeExpr invocation, Function<ValuePoint, Node> dependency) {
        var result = new ArrayList<Node>();
        for (var argument : invocation.getArgs()) {
            if (argument.getType().toString().equals("java.lang.String[]")) {
                var array = flow.origins(point.value(argument), ignored -> false);
                if (!(argument instanceof Local local) || !array.complete() || array.values().size() != 1
                        || !(array.values().getFirst().value() instanceof JNewArrayExpr allocated)
                        || !(allocated.getSize() instanceof IntConstant size) || size.getValue() < 0 || size.getValue() > 128)
                    throw new IllegalArgumentException("MyBatis SQL builder varargs length is unresolved or exceeds the budget");
                for (int i = 0; i < size.getValue(); i++) {
                    flow.checkpoint(); result.add(dependency.apply(point.value(new JArrayRef(local, IntConstant.getInstance(i)))));
                }
            } else {
                var source = dependency.apply(point.value(argument));
                if (argument.getType().toString().equals("java.lang.String")) result.add(source);
                else if (Set.of("int", "long").contains(argument.getType().toString())) result.add(new Node(source.variable().withType("java.lang.String"),
                        Kind.EXPRESSION, "Numeric SQL builder argument", List.of(source), new Computation(Operation.STRINGIFY, argument.getType().toString())));
                else throw new IllegalArgumentException("Unsupported MyBatis SQL builder argument: " + argument.getType());
            }
        }
        return List.copyOf(result);
    }

    private Node render(ValuePoint input, List<Clause> reversed, List<String> issues, List<ControlFlowEdge> path) {
        var sql = new SQL(); var sources = new LinkedHashMap<String, Node>();
        var clauses = new ArrayList<>(reversed); Collections.reverse(clauses);
        for (var clause : clauses) {
            flow.checkpoint();
            String[] arguments = new String[clause.arguments().size()];
            for (int i = 0; i < arguments.length; i++) {
                String marker = "__JSQLINT_BUILDER_ARGUMENT_" + sources.size() + "__";
                sources.put(marker, clause.arguments().get(i)); arguments[i] = marker;
            }
            try { apply(sql, clause.name(), arguments); }
            catch (RuntimeException error) { return unresolved(input, "Invalid MyBatis SQL builder clause: " + clause.name() + " (" + error.getMessage() + ")"); }
        }
        String text = sql.toString(); var parts = new ArrayList<Node>(); int start = 0;
        var markers = ARGUMENT.matcher(text);
        while (markers.find()) {
            flow.checkpoint();
            if (markers.start() > start) parts.add(ProviderArgumentModel.literal(input, text.substring(start, markers.start())));
            parts.add(sources.get(markers.group())); start = markers.end();
        }
        if (start < text.length()) parts.add(ProviderArgumentModel.literal(input, text.substring(start)));
        if (parts.isEmpty()) parts.add(ProviderArgumentModel.literal(input, ""));
        issues.stream().distinct().forEach(issue -> parts.add(unresolved(input, issue)));
        var history = path.stream().map(edge -> new ControlEdge(position(edge.from()), position(edge.to()), edge.exceptional())).toList();
        return new Node(input.snapshot().withType("java.lang.String"), Kind.EXPRESSION, "MyBatis SQL builder snapshot", parts,
                new Computation(Operation.CONCAT, "MyBatis SQL builder snapshot", Optional.empty(), history));
    }

    private static void apply(SQL sql, String name, String[] arguments) {
        switch (name) {
            case "SELECT" -> sql.SELECT(arguments); case "SELECT_DISTINCT" -> sql.SELECT_DISTINCT(arguments);
            case "FROM" -> sql.FROM(arguments); case "WHERE" -> sql.WHERE(arguments); case "JOIN" -> sql.JOIN(arguments);
            case "INNER_JOIN" -> sql.INNER_JOIN(arguments); case "LEFT_OUTER_JOIN" -> sql.LEFT_OUTER_JOIN(arguments);
            case "RIGHT_OUTER_JOIN" -> sql.RIGHT_OUTER_JOIN(arguments); case "OUTER_JOIN" -> sql.OUTER_JOIN(arguments);
            case "GROUP_BY" -> sql.GROUP_BY(arguments); case "HAVING" -> sql.HAVING(arguments); case "ORDER_BY" -> sql.ORDER_BY(arguments);
            case "UPDATE" -> sql.UPDATE(single(arguments)); case "SET" -> sql.SET(arguments);
            case "INSERT_INTO" -> sql.INSERT_INTO(single(arguments));
            case "VALUES" -> { if (arguments.length != 2) throw new IllegalArgumentException("VALUES requires two arguments"); sql.VALUES(arguments[0], arguments[1]); }
            case "INTO_COLUMNS" -> sql.INTO_COLUMNS(arguments); case "INTO_VALUES" -> sql.INTO_VALUES(arguments);
            case "DELETE_FROM" -> sql.DELETE_FROM(single(arguments)); case "AND" -> sql.AND(); case "OR" -> sql.OR(); case "ADD_ROW" -> sql.ADD_ROW();
            case "LIMIT" -> sql.LIMIT(single(arguments)); case "OFFSET" -> sql.OFFSET(single(arguments));
            case "OFFSET_ROWS" -> sql.OFFSET_ROWS(single(arguments)); case "FETCH_FIRST_ROWS_ONLY" -> sql.FETCH_FIRST_ROWS_ONLY(single(arguments));
            default -> throw new IllegalArgumentException("Unsupported clause: " + name);
        }
    }
    private static String single(String[] arguments) {
        if (arguments.length != 1) throw new IllegalArgumentException("Clause requires one argument"); return arguments[0];
    }
    private boolean builder(String type) { return type.equals(ABSTRACT_SQL) || MapperParameterResolver.subtype(type, SQL_TYPE, flow.program().view()); }
    private boolean overridden(AbstractInstanceInvokeExpr invocation) {
        String owner = invocation.getMethodSignature().getDeclClassType().toString();
        if (owner.equals(SQL_TYPE) || owner.equals(ABSTRACT_SQL)) return false;
        return flow.program().view().getMethod(invocation.getMethodSignature()).isPresent();
    }
    /** 调用可声明在 SQL/AbstractSQL 上，实际创建的子类仍可能重写这些方法。 */
    private List<String> subclassIssues(Origins identity) {
        var issues = new LinkedHashSet<String>();
        for (var origin : identity.values()) {
            if (!(origin.value() instanceof JNewExpr allocation)) continue;
            ClassType next = allocation.getType(); var visited = new HashSet<ClassType>();
            while (next != null && !Set.of(SQL_TYPE, ABSTRACT_SQL, "java.lang.Object").contains(next.toString())) {
                flow.checkpoint();
                if (!visited.add(next) || visited.size() > 256) {
                    issues.add("MyBatis SQL subclass hierarchy budget exceeded"); break;
                }
                var type = flow.program().view().getClass(next);
                if (type.isEmpty()) {
                    issues.add("MyBatis SQL subclass is unavailable: " + next); break;
                }
                for (var method : type.get().getMethods()) if (overridesFramework(method))
                    issues.add("MyBatis SQL subclass overrides framework behavior: " + method.getSignature());
                next = type.get().getSuperclass().orElse(null);
            }
        }
        return List.copyOf(issues);
    }
    private static boolean overridesFramework(JavaSootMethod method) {
        if (method.isStatic()) return false;
        String name = method.getName();
        var arguments = method.getParameterTypes().stream().map(Object::toString).toList();
        if (Set.of("toString", "getSelf").contains(name)) return arguments.isEmpty();
        if (!CLAUSES.contains(name)) return false;
        return switch (name) {
            case "AND", "OR", "ADD_ROW" -> arguments.isEmpty();
            case "VALUES" -> arguments.equals(List.of("java.lang.String", "java.lang.String"));
            case "LIMIT", "FETCH_FIRST_ROWS_ONLY" -> arguments.equals(List.of("java.lang.String")) || arguments.equals(List.of("int"));
            case "OFFSET", "OFFSET_ROWS" -> arguments.equals(List.of("java.lang.String")) || arguments.equals(List.of("long"));
            case "UPDATE", "INSERT_INTO", "DELETE_FROM" -> arguments.equals(List.of("java.lang.String"));
            default -> arguments.equals(List.of("java.lang.String")) || arguments.equals(List.of("java.lang.String[]"));
        };
    }
    /** fluent 方法返回自身，标准框架方法在引用身份追踪中无需进入依赖库。 */
    private Origins origins(ValuePoint receiver) {
        var pending = new ArrayDeque<ValuePoint>(); pending.add(receiver);
        var result = new LinkedHashSet<ValuePoint>(); var seen = new HashSet<ValuePoint>(); boolean complete = true;
        while (!pending.isEmpty()) {
            flow.checkpoint(); var next = pending.removeFirst();
            if (!seen.add(next)) { complete = false; continue; }
            if (seen.size() > 256) return new Origins(List.copyOf(result), false);
            var origins = flow.origins(next, site -> site.invocation() instanceof AbstractInstanceInvokeExpr instance
                    && builder(instance.getBase().getType().toString()) && CLAUSES.contains(instance.getMethodSignature().getName()) && !overridden(instance));
            complete &= origins.complete();
            for (var origin : origins.values()) {
                if (origin.value() instanceof AbstractInstanceInvokeExpr instance && CLAUSES.contains(instance.getMethodSignature().getName()) && !overridden(instance))
                    pending.add(origin.point().value(instance.getBase()));
                else result.add(origin);
            }
        }
        return new Origins(List.copyOf(result), complete);
    }
    private static Variable position(FlowPoint point) { return point.value(IntConstant.getInstance(0)).snapshot(); }
    private static Node unresolved(ValuePoint point, String issue) { return new Node(point.snapshot().withType("java.lang.String"), Kind.UNRESOLVED, issue, List.of()); }
    private record Clause(String name, List<Node> arguments) { Clause { arguments = List.copyOf(arguments); } }
    private record State(List<Clause> clauses, List<String> issues, ObjectExposure exposure) {
        State { clauses = List.copyOf(clauses); issues = List.copyOf(issues); }
        State issue(String issue) { var next = new ArrayList<>(issues); next.add(issue); return new State(clauses, next, exposure); }
        State exposure(ObjectExposure value) { return new State(clauses, issues, value); }
    }
}
