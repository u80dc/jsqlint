package org.u80dc.jsqlint.core.dataflow;

import sootup.core.jimple.common.constant.*;
import sootup.core.jimple.common.expr.JNewExpr;
import sootup.core.jimple.common.ref.*;
import sootup.core.jimple.common.stmt.AbstractDefinitionStmt;
import sootup.core.signatures.FieldSignature;
import sootup.core.types.ClassType;
import sootup.java.core.JavaSootMethod;
import java.util.*;

import static org.u80dc.jsqlint.core.dataflow.DataFlowAnalysis.*;
import static org.u80dc.jsqlint.core.dataflow.FlowPoint.ValuePoint;

/** 实例属性的需求驱动到达写入查询；确定对象身份、字段声明及写入顺序后才覆盖历史。 */
final class ObjectProperties {
    private final DataFlowAnalysis analysis;
    private final Map<ValuePoint, Origins> origins = new HashMap<>();

    ObjectProperties(DataFlowAnalysis analysis) { this.analysis = analysis; }

    static Member member(ProgramIndex program, ClassType owner, String property) {
        return member(program,owner,property,PropertyAccess.JAVA_BEAN);
    }
    static Member member(ProgramIndex program, ClassType owner, String property,PropertyAccess access) {
        String suffix = Character.toUpperCase(property.charAt(0)) + property.substring(1);
        Optional<FieldSignature> field = Optional.empty();
        var getters = new ArrayList<JavaSootMethod>();
        var signatures = new HashSet<sootup.core.signatures.MethodSubSignature>();
        Set<ClassType> visited = new HashSet<>();
        ClassType current = owner;
        while (current != null && visited.add(current)) {
            var clazz = program.view().getClass(current);
            if (clazz.isEmpty()) break;
            for (String name : access==PropertyAccess.FIELD?List.<String>of():access==PropertyAccess.ACCESSOR?List.of(property):List.of("get" + suffix, "is" + suffix)) {
                var methods = clazz.get().getMethods().stream().filter(method -> method.getName().equals(name)
                        && method.getParameterCount() == 0 && !method.getReturnType().toString().equals("void")).toList();
                for (var method : methods) if (signatures.add(method.getSubSignature())) getters.add(method);
            }
            if (field.isEmpty()) field = clazz.get().getField(property).filter(value -> !value.isStatic()).map(value -> value.getSignature());
            current = clazz.get().getSuperclass().orElse(null);
        }
        if (getters.isEmpty()) return new Member(Optional.empty(), access==PropertyAccess.ACCESSOR?Optional.empty():field, Optional.empty());
        // JavaBean boolean isX 优先，协变桥优先实际返回类型。
        var booleanGetter = getters.stream().filter(method -> method.getName().equals("is" + suffix)
                && method.getReturnType().toString().equals("boolean")).findFirst();
        if (booleanGetter.isPresent() && getters.stream().allMatch(method -> method.getReturnType().toString().equals("boolean")))
            return selected(booleanGetter.get(), field);
        JavaSootMethod winner = getters.getFirst();
        for (int i = 1; i < getters.size(); i++) {
            var candidate = getters.get(i);
            String left = winner.getReturnType().toString(), right = candidate.getReturnType().toString();
            if (left.equals(right) || !subtype(program, left, right) && !subtype(program, right, left))
                return new Member(Optional.empty(), field, Optional.of("Ambiguous object getter for property: " + property));
            if (subtype(program, right, left)) winner = candidate;
        }
        return selected(winner, field);
    }

    private static Member selected(JavaSootMethod method, Optional<FieldSignature> field) {
        if (!method.isPublic() || method.isStatic()) return new Member(Optional.empty(), field,
                Optional.of("Non-public or static object getter is not supported: " + method.getSignature()));
        return new Member(Optional.of(method), field, Optional.empty());
    }

    private static boolean subtype(ProgramIndex program, String child, String parent) {
        if (child.equals(parent)) return true;
        if (!(program.view().getIdentifierFactory().getType(child) instanceof ClassType type)) return false;
        var visited = new HashSet<ClassType>(); var pending = new ArrayDeque<ClassType>(); pending.add(type);
        while (!pending.isEmpty()) {
            var current = pending.removeFirst(); if (!visited.add(current)) continue;
            if (current.toString().equals(parent)) return true;
            program.view().getClass(current).ifPresent(clazz -> {
                clazz.getSuperclass().ifPresent(pending::add); pending.addAll(clazz.getInterfaces());
            });
        }
        return false;
    }

    Read reaching(ValuePoint input, JInstanceFieldRef field) {
        FieldSignature declaration = declaration(analysis.program(), field.getFieldSignature());
        Origins bases = origins(input.point().value(field.getBase()));
        var sources = new ArrayList<Source>();
        var identityIssues = new ArrayList<String>();
        if (!bases.complete() || bases.values().size() != 1) identityIssues.add("Instance field receiver identity is unresolved");
        if (bases.complete() && bases.values().size() == 1 && bases.values().getFirst().value() instanceof NullConstant)
            return new Read(List.of(new Source(Optional.empty(), List.of("Instance field receiver is null"), List.of())));
        analysis.backward(input.point(), new State(false, HeapExposure.EMPTY, List.of(), List.copyOf(identityIssues)), (point, state) -> {
            var values = new ArrayList<>(state.values());
            var issues = new ArrayList<>(state.issues());
            boolean unmodeled = !state.found();
            var site = point.invocation(analysis.program());
            boolean objectInitializer = site.isPresent() && site.get().invocation().getMethodSignature().getDeclClassType().toString().equals("java.lang.Object")
                    && site.get().invocation().getMethodSignature().getName().equals("<init>")
                    && site.get().invocation().getArgCount() == 0;
            // Object 的无参构造器不会修改子类字段；其余库调用继续按未知堆效果处理。
            var exposure = state.exposure().before(analysis, point, bases, unmodeled && !objectInitializer);
            if (point.statement() instanceof AbstractDefinitionStmt definition) {
                if (!state.found() && definition.getLeftOp() instanceof JInstanceFieldRef written
                        && declaration(analysis.program(), written.getFieldSignature()).equals(declaration)) {
                    Alias alias = bases.aliasTo(origins(point.value(written.getBase())));
                    if (alias != Alias.NONE) {
                        values.add(point.value(definition.getRightOp()));
                        if (alias == Alias.MUST) return Decision.next(new State(true, exposure, List.copyOf(values), List.copyOf(issues)));
                        issues.add("Instance field write may target another object");
                    }
                }
                ValuePoint right = point.value(definition.getRightOp());
                if (bases.complete() && bases.values().size() == 1 && bases.values().contains(right)) {
                    if (right.value() instanceof JNewExpr) {
                        if (!state.found()) values.add(point.value(defaultValue(field.getType().toString())));
                        return Decision.stop(new State(true, exposure, List.copyOf(values), List.copyOf(issues)));
                    }
                    if (right.value() instanceof JParameterRef || right.value() instanceof JThisRef) {
                        if (!state.found()) issues.add("Instance property content belongs to an unresolved input object");
                        return Decision.stop(new State(true, exposure, List.copyOf(values), List.copyOf(issues)));
                    }
                }
            }
            var next = new State(state.found(), exposure, List.copyOf(values), List.copyOf(issues));
            return objectInitializer ? Decision.skipCall(next) : Decision.next(next);
        }, end -> {
            var issues = new ArrayList<>(end.state().issues());
            issues.addAll(end.issues()); issues.addAll(end.state().exposure().issues());
            if (!end.state().found() || end.end() == End.CYCLE || end.end() == End.LIMIT)
                issues.add("Instance field history ended at " + end.end());
            if (end.state().values().isEmpty()) sources.add(new Source(Optional.empty(), distinct(issues), end.path()));
            else for (ValuePoint value : end.state().values()) sources.add(new Source(Optional.of(value), distinct(issues), end.path()));
        });
        return new Read(List.copyOf(sources));
    }

    private Origins origins(ValuePoint input) {
        return origins.computeIfAbsent(input, point -> analysis.origins(point, ignored -> false));
    }

    /** 字节码可用子类 owner 引用继承字段，需定位真实声明后比较，不能把该写入误判为其他字段。 */
    private static FieldSignature declaration(ProgramIndex program, FieldSignature signature) {
        var current = signature.getDeclClassType();
        var visited = new HashSet<ClassType>();
        while (current != null && visited.add(current)) {
            var clazz = program.view().getClass(current);
            if (clazz.isEmpty()) break;
            var field = clazz.get().getField(signature.getSubSignature());
            if (field.isPresent()) return field.get().getSignature();
            current = clazz.get().getSuperclass().orElse(null);
        }
        return signature;
    }

    private static Constant defaultValue(String type) {
        return switch (type) {
            case "boolean", "byte", "short", "char", "int" -> IntConstant.getInstance(0);
            case "long" -> LongConstant.getInstance(0);
            case "float" -> FloatConstant.getInstance(0);
            case "double" -> DoubleConstant.getInstance(0);
            default -> NullConstant.getInstance();
        };
    }
    private static List<String> distinct(List<String> issues) { return issues.stream().distinct().toList(); }
    record Member(Optional<JavaSootMethod> getter, Optional<FieldSignature> field, Optional<String> issue) {}
    record Read(List<Source> sources) {}
    record Source(Optional<ValuePoint> value, List<String> issues, List<ControlFlowEdge> path) {}
    private record State(boolean found, HeapExposure exposure, List<ValuePoint> values, List<String> issues) {}
}
