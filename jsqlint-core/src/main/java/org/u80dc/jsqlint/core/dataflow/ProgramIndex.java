package org.u80dc.jsqlint.core.dataflow;

import org.u80dc.jsqlint.core.spi.InvocationSite;
import org.u80dc.jsqlint.core.spi.SqlCallSite;
import sootup.core.jimple.common.expr.*;
import sootup.core.jimple.common.stmt.*;
import sootup.core.jimple.common.ref.JFieldRef;
import sootup.core.signatures.*;
import sootup.core.types.ClassType;
import sootup.java.core.*;
import sootup.java.core.views.JavaView;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * 共享程序索引：静态/特殊调用精确解析，虚调用按应用类层次 CHA 保守分派。
 * 不从 main 限制可达性，因而未被调用的顶层方法仍可作为分析入口。
 * @author u80dc
 */
public final class ProgramIndex {
    private final JavaView view;
    private final List<JavaSootClass> classes;
    private final Runnable checkpoint;
    private final Map<Dispatch, CallResolution> targets = new HashMap<>();
    private final Map<MethodSignature, List<InvocationSite>> callers;
    private final Map<SqlCallSite, InvocationSite> invocations = new LinkedHashMap<>();
    private final Map<InvocationKey, InvocationSite> invocationKeys = new HashMap<>();
    private final Map<JavaSootMethod, Map<Stmt, Integer>> statementPositions = new IdentityHashMap<>();
    private final Map<ClassType, Set<ClassType>> ancestors = new HashMap<>();
    private final Map<FieldSignature, List<FlowPoint>> writes = new HashMap<>();
    private boolean fieldsIndexed;

    public ProgramIndex(JavaView view, List<JavaSootClass> classes, Collection<InvocationSite> calls, Runnable checkpoint) {
        this.view = view; this.classes = List.copyOf(classes); this.checkpoint = checkpoint;
        Map<MethodSignature, List<InvocationSite>> callerIndex = new LinkedHashMap<>();
        for (InvocationSite call : calls) {
            checkpoint.run();
            indexInvocation(call);
            Set<MethodSignature> signatures = new LinkedHashSet<>();
            signatures.add(call.invocation().getMethodSignature());
            targets(call).forEach(method -> signatures.add(method.getSignature()));
            for (MethodSignature signature : signatures) callerIndex.computeIfAbsent(signature, ignored -> new ArrayList<>()).add(call);
        }
        callerIndex.replaceAll((signature, sites) -> List.copyOf(sites));
        callers = Collections.unmodifiableMap(callerIndex);
    }
    /** 仅当被追踪的值读取字段时建立写入索引，普通局部变量分析不额外扫描方法体。 */
    private void indexFields() {
        if (fieldsIndexed) return;
        for (JavaSootClass clazz : classes) for (JavaSootMethod method : clazz.getMethods()) {
            checkpoint.run();
            if (!method.hasBody()) continue;
            for (Stmt stmt : method.getBody().getStmts()) {
                if (stmt instanceof AbstractDefinitionStmt definition && definition.getLeftOp() instanceof JFieldRef field)
                    writes.computeIfAbsent(field.getFieldSignature(), ignored -> new ArrayList<>()).add(new FlowPoint(method, stmt, CallContext.EMPTY));
            }
        }
        fieldsIndexed = true;
    }
    public JavaView view() { return view; }
    /** 保存框架声明的隐式调用，仅用于本次分析，不改变目标字节码或实际调用者索引。 */
    public void registerImplicitInvocation(InvocationSite site) { indexInvocation(site); }
    public Optional<InvocationSite> invocation(SqlCallSite site) { return Optional.ofNullable(invocations.get(site)); }
    public Optional<InvocationSite> invocation(MethodSignature caller, int statement, MethodSignature target) {
        return Optional.ofNullable(invocationKeys.get(new InvocationKey(caller, statement, target)));
    }
    private void indexInvocation(InvocationSite site) {
        invocations.put(SqlCallSite.invocation(site), site);
        invocationKeys.put(new InvocationKey(site.method().getSignature(), site.statementIndex(),
                site.invocation().getMethodSignature()), site);
    }

    /** 实际语句按身份定位；同一方法只建立一次位置表。外部等价语句沿用旧 indexOf 的兼容行为。 */
    public int statementIndex(JavaSootMethod method, Stmt statement) {
        Objects.requireNonNull(method); Objects.requireNonNull(statement);
        Map<Stmt, Integer> positions = statementPositions.computeIfAbsent(method, owner -> {
            Map<Stmt, Integer> indexed = new IdentityHashMap<>();
            int i = 0;
            for (Stmt actual : owner.getBody().getStmts()) {
                checkpoint.run(); indexed.put(actual, i++);
            }
            return indexed;
        });
        Integer position = positions.get(statement);
        if (position != null) return position;
        int compatible = method.getBody().getStmts().indexOf(statement);
        positions.put(statement, compatible);
        return compatible;
    }
    /** 实际调用者索引在构造结束后冻结，包括每个目标签名对应的调用点列表。 */
    public Map<MethodSignature, List<InvocationSite>> callers() { return callers; }
    public List<FlowPoint> writes(FieldSignature field) { indexFields(); return List.copyOf(writes.getOrDefault(field, List.of())); }

    /** 使用共享调用索引和分派缓存选择入口及下游方法，不改变完整程序视图。 */
    public Set<MethodSignature> reachableMethods(JavaSootMethod entry, int maxCallDepth,
                                                BiConsumer<String, String> incomplete) {
        if (maxCallDepth < 1) throw new IllegalArgumentException("Call depth must be positive");
        Map<MethodSignature, List<InvocationSite>> outgoing = new LinkedHashMap<>();
        for (var site : invocations.values()) {
            checkpoint.run();
            outgoing.computeIfAbsent(site.method().getSignature(), ignored -> new ArrayList<>()).add(site);
        }
        record Pending(JavaSootMethod method, int depth) {}
        Deque<Pending> pending = new ArrayDeque<>();
        pending.add(new Pending(entry, 0));
        Set<MethodSignature> selected = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            checkpoint.run();
            var next = pending.removeFirst();
            if (!selected.add(next.method().getSignature())) continue;
            for (var site : outgoing.getOrDefault(next.method().getSignature(), List.of())) {
                checkpoint.run();
                for (var target : targets(site)) {
                    if (target.getSignature().equals(entry.getSignature()))
                        incomplete.accept("METHOD_SCOPE_RECURSION", "Recursive call to analysis entry " + entry.getSignature());
                    if (selected.contains(target.getSignature())) continue;
                    if (next.depth() >= maxCallDepth)
                        incomplete.accept("METHOD_SCOPE_LIMIT", "Call depth limit from " + entry.getSignature() + " at " + site.location());
                    else pending.addLast(new Pending(target, next.depth() + 1));
                }
            }
        }
        return Collections.unmodifiableSet(selected);
    }

    public List<JavaSootMethod> targets(InvocationSite site) {
        return resolution(site).applicationTargets();
    }
    /** 只按已确定的接收者类型解析，不扩展到其他 CHA 实现。 */
    public Optional<JavaSootMethod> resolveMethod(ClassType receiver, MethodSubSignature signature) {
        return classMethod(receiver, signature).or(() -> interfaceMethod(receiver, signature));
    }

    /** 来源候选和分派完备性分别保存；应用声明体可以可分析，却不能证明没有库子类覆盖。 */
    public CallResolution resolution(InvocationSite site) {
        AbstractInvokeExpr invocation = site.invocation();
        if (invocation instanceof JDynamicInvokeExpr) return new CallResolution(List.of(), true);
        boolean exact = invocation instanceof JStaticInvokeExpr || invocation instanceof JSpecialInvokeExpr;
        return targets.computeIfAbsent(new Dispatch(invocation.getMethodSignature(), exact), key -> {
            Set<JavaSootMethod> found = new LinkedHashSet<>();
            boolean unresolvedReceiver = false;
            Optional<JavaSootMethod> declaration = resolveDeclaration(key.signature().getDeclClassType(), key.signature().getSubSignature());
            if (exact) declaration.ifPresent(found::add);
            else {
                boolean concreteReceiver = false;
                for (JavaSootClass clazz : classes) {
                    checkpoint.run();
                    // 声明只用于符号解析；抽象类和接口本身不是可实例化的接收者。
                    if (!clazz.isInterface() && !clazz.isAbstract()
                            && ancestors(clazz.getType()).contains(key.signature().getDeclClassType())) {
                        concreteReceiver = true;
                        Optional<JavaSootMethod> selected = declaration.flatMap(method -> selectVirtual(clazz.getType(), method));
                        if (selected.isPresent()) found.add(selected.get()); else unresolvedReceiver = true;
                    }
                }
                // 顶层参数可以由未列出的实现实例承载。没有项目具体接收者时，仍可追踪其
                // 继承的具体应用方法体；这与把抽象类型自身作为接收者枚举是两件事。
                // 已有具体接收者时只保留其实际选择，不能重新加入被覆盖的父类/默认方法。
                if (!concreteReceiver) declaration.filter(method -> !method.isAbstract() && method.hasBody()
                        && view.getClass(method.getDeclClassType()).map(JavaSootClass::isApplicationClass).orElse(false)).ifPresent(found::add);
            }
            List<JavaSootMethod> applicationTargets = new ArrayList<>();
            // 依赖库的接口/可继承类型不是封闭的实现集合；只枚举项目实现无法证明不存在库实现。
            // 无需为此加载所有依赖方法体，保留外部分派边界即可避免漏报。
            var declaringClass = view.getClass(key.signature().getDeclClassType());
            boolean external = unresolvedReceiver || found.isEmpty() || !exact && declaringClass
                    .map(clazz -> clazz.isLibraryClass() && !clazz.isFinal()).orElse(true);
            for (JavaSootMethod method : found) {
                // 抽象声明不代表可执行分支；没有任何具体实现时由下面的空目标检查处理。
                if (method.isAbstract()) continue;
                if (method.hasBody() && view.getClass(method.getDeclClassType())
                        .map(JavaSootClass::isApplicationClass).orElse(false)) applicationTargets.add(method);
                else external = true;
            }
            boolean complete = !unresolvedReceiver && declaration.isPresent() && (exact
                    || declaration.get().isPrivate() || declaration.get().isFinal()
                    || declaringClass.map(JavaSootClass::isFinal).orElse(false));
            return new CallResolution(applicationTargets, external || applicationTargets.isEmpty(), complete);
        });
    }

    /** 接收者来源已确定为一个实际分配类型时，只选择该类型的合法虚调用目标。 */
    public CallResolution resolution(InvocationSite site, ClassType receiver) {
        if (!(site.invocation() instanceof AbstractInstanceInvokeExpr)
                || site.invocation() instanceof JSpecialInvokeExpr) return resolution(site);
        var signature = site.invocation().getMethodSignature();
        var selected = resolveDeclaration(signature.getDeclClassType(), signature.getSubSignature())
                .flatMap(declaration -> selectVirtual(receiver, declaration));
        if (selected.isEmpty()) return new CallResolution(List.of(), true, false);
        var method = selected.get();
        boolean application = method.hasBody() && view.getClass(method.getDeclClassType())
                .map(JavaSootClass::isApplicationClass).orElse(false);
        return new CallResolution(application ? List.of(method) : List.of(), !application, true);
    }
    private Optional<JavaSootMethod> resolveDeclaration(ClassType type, MethodSubSignature signature) {
        return classMethod(type, signature).or(() -> interfaceMethod(type, signature));
    }

    /** 类方法查找先完成整条 superclass 链，接口默认方法不能抢在祖先类方法之前。 */
    private Optional<JavaSootMethod> classMethod(ClassType type, MethodSubSignature signature) {
        Set<ClassType> visited = new HashSet<>();
        ClassType current = type;
        while (current != null && visited.add(current)) {
            checkpoint.run();
            var clazz = view.getClass(current);
            if (clazz.isEmpty()) break;
            var method = clazz.get().getMethod(signature);
            if (method.isPresent()) return method;
            current = clazz.get().getSuperclass().orElse(null);
        }
        return Optional.empty();
    }

    private Optional<JavaSootMethod> selectVirtual(ClassType receiver, JavaSootMethod declaration) {
        if (declaration.isPrivate() || declaration.isFinal()) return Optional.of(declaration);
        Set<ClassType> visited = new HashSet<>();
        ClassType current = receiver;
        while (current != null && visited.add(current)) {
            checkpoint.run();
            var clazz = view.getClass(current);
            if (clazz.isEmpty()) break;
            var candidate = clazz.get().getMethod(declaration.getSubSignature());
            if (candidate.isPresent() && overrides(candidate.get(), declaration))
                return candidate.filter(method -> !method.isAbstract());
            current = clazz.get().getSuperclass().orElse(null);
        }
        return interfaceMethod(receiver, declaration.getSubSignature()).filter(method -> !method.isAbstract());
    }

    /** 同签名不等于覆盖：跨包 package-private 方法和 private/static 方法必须排除。 */
    private boolean overrides(JavaSootMethod candidate, JavaSootMethod declaration) {
        if (candidate.equals(declaration)) return !candidate.isStatic();
        if (candidate.isPrivate() || candidate.isStatic() || declaration.isPrivate() || declaration.isStatic()) return false;
        var declaringClass = view.getClass(declaration.getDeclClassType());
        if (declaringClass.map(JavaSootClass::isInterface).orElse(false)) return candidate.isPublic();
        if (!ancestors(candidate.getDeclClassType()).contains(declaration.getDeclClassType())) return false;
        if (declaration.isPublic() || declaration.isProtected()
                || packageName(candidate.getDeclClassType()).equals(packageName(declaration.getDeclClassType()))) return true;
        // package-private 覆盖可以经同包的 public/protected 中间覆盖继续传递到其他包。
        for (ClassType ancestor : ancestors(candidate.getDeclClassType())) {
            if (ancestor.equals(candidate.getDeclClassType()) || ancestor.equals(declaration.getDeclClassType())) continue;
            var bridge = view.getClass(ancestor).flatMap(clazz -> clazz.getMethod(declaration.getSubSignature()));
            if (bridge.isPresent() && (bridge.get().isPublic() || bridge.get().isProtected())
                    && overrides(bridge.get(), declaration)) return true;
        }
        return false;
    }

    /** 先消除被子接口重新声明的成员，再选择唯一的非抽象默认方法。 */
    private Optional<JavaSootMethod> interfaceMethod(ClassType receiver, MethodSubSignature signature) {
        List<JavaSootMethod> methods = new ArrayList<>();
        for (ClassType ancestor : ancestors(receiver)) {
            checkpoint.run();
            var clazz = view.getClass(ancestor);
            if (clazz.isPresent() && clazz.get().isInterface()) clazz.get().getMethod(signature)
                    .filter(method -> !method.isStatic() && !method.isPrivate()).ifPresent(methods::add);
        }
        List<JavaSootMethod> maximal = methods.stream().filter(method -> methods.stream().noneMatch(other ->
                !other.getDeclClassType().equals(method.getDeclClassType())
                        && ancestors(other.getDeclClassType()).contains(method.getDeclClassType()))).toList();
        List<JavaSootMethod> defaults = maximal.stream().filter(method -> !method.isAbstract()).toList();
        if (defaults.size() == 1) return Optional.of(defaults.getFirst());
        return defaults.isEmpty() && !maximal.isEmpty() ? Optional.of(maximal.getFirst()) : Optional.empty();
    }

    private static String packageName(ClassType type) {
        String name = type.toString(); int separator = name.lastIndexOf('.');
        return separator < 0 ? "" : name.substring(0, separator);
    }
    private Set<ClassType> ancestors(ClassType type) {
        return ancestors.computeIfAbsent(type, ignored -> {
            Set<ClassType> visited = new HashSet<>();
            Deque<ClassType> pending = new ArrayDeque<>(); pending.add(type);
            while (!pending.isEmpty()) {
                ClassType current = pending.removeFirst();
                if (!visited.add(current)) continue;
                view.getClass(current).ifPresent(clazz -> {
                    clazz.getSuperclass().ifPresent(pending::add); pending.addAll(clazz.getInterfaces());
                });
            }
            return visited;
        });
    }
    private record Dispatch(MethodSignature signature, boolean exact) {}
    private record InvocationKey(MethodSignature caller, int statement, MethodSignature target) {}

    public record CallResolution(List<JavaSootMethod> applicationTargets, boolean hasExternalTargets,
                                 boolean targetsComplete) {
        public CallResolution { applicationTargets = List.copyOf(applicationTargets); }
        /** 兼容既有显式目标/模型构造，未声明外部目标时沿用原来的完整分派约定。 */
        public CallResolution(List<JavaSootMethod> applicationTargets, boolean hasExternalTargets) {
            this(applicationTargets, hasExternalTargets, !hasExternalTargets);
        }
    }
}
