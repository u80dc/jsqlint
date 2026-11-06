package org.u80dc.jsqlint.core.dataflow;

import org.u80dc.jsqlint.core.spi.InvocationSite;
import sootup.core.jimple.common.expr.*;
import sootup.core.jimple.common.stmt.*;
import sootup.core.jimple.common.ref.JFieldRef;
import sootup.core.signatures.*;
import sootup.core.types.ClassType;
import sootup.java.core.*;
import sootup.java.core.views.JavaView;
import java.util.*;

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
    private final Map<MethodSignature, List<InvocationSite>> callers = new LinkedHashMap<>();
    private final Map<ClassType, Set<ClassType>> ancestors = new HashMap<>();
    private final Map<FieldSignature, List<FlowPoint>> writes = new HashMap<>();
    private boolean fieldsIndexed;

    public ProgramIndex(JavaView view, List<JavaSootClass> classes, Collection<InvocationSite> calls, Runnable checkpoint) {
        this.view = view; this.classes = List.copyOf(classes); this.checkpoint = checkpoint;
        for (InvocationSite call : calls) {
            checkpoint.run();
            Set<MethodSignature> signatures = new LinkedHashSet<>();
            signatures.add(call.invocation().getMethodSignature());
            targets(call).forEach(method -> signatures.add(method.getSignature()));
            for (MethodSignature signature : signatures) callers.computeIfAbsent(signature, ignored -> new ArrayList<>()).add(call);
        }
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
    public Map<MethodSignature, List<InvocationSite>> callers() { return Collections.unmodifiableMap(callers); }
    public List<FlowPoint> writes(FieldSignature field) { indexFields(); return List.copyOf(writes.getOrDefault(field, List.of())); }

    public List<JavaSootMethod> targets(InvocationSite site) {
        return resolution(site).applicationTargets();
    }

    /** 可分析目标与外部边界分开保留，避免“有一个已知实现”被当作“已知全部实现”。 */
    public CallResolution resolution(InvocationSite site) {
        AbstractInvokeExpr invocation = site.invocation();
        if (invocation instanceof JDynamicInvokeExpr) return new CallResolution(List.of(), true);
        boolean exact = invocation instanceof JStaticInvokeExpr || invocation instanceof JSpecialInvokeExpr;
        return targets.computeIfAbsent(new Dispatch(invocation.getMethodSignature(), exact), key -> {
            Set<JavaSootMethod> found = new LinkedHashSet<>();
            if (exact) resolve(key.signature().getDeclClassType(), key.signature().getSubSignature()).ifPresent(found::add);
            else {
                resolve(key.signature().getDeclClassType(), key.signature().getSubSignature()).ifPresent(found::add);
                for (JavaSootClass clazz : classes) {
                    checkpoint.run();
                    if (!clazz.isInterface() && ancestors(clazz.getType()).contains(key.signature().getDeclClassType()))
                        resolve(clazz.getType(), key.signature().getSubSignature()).ifPresent(found::add);
                }
            }
            List<JavaSootMethod> applicationTargets = new ArrayList<>();
            // 依赖库的接口/可继承类型不是封闭的实现集合；只枚举项目实现无法证明不存在库实现。
            // 无需为此加载所有依赖方法体，保留外部分派边界即可避免漏报。
            var declaringClass = view.getClass(key.signature().getDeclClassType());
            boolean external = found.isEmpty() || !exact && declaringClass
                    .map(clazz -> clazz.isLibraryClass() && !clazz.isFinal()).orElse(true);
            for (JavaSootMethod method : found) {
                // 抽象声明不代表可执行分支；没有任何具体实现时由下面的空目标检查处理。
                if (method.isAbstract()) continue;
                if (method.hasBody() && view.getClass(method.getDeclClassType())
                        .map(JavaSootClass::isApplicationClass).orElse(false)) applicationTargets.add(method);
                else external = true;
            }
            return new CallResolution(applicationTargets, external || applicationTargets.isEmpty());
        });
    }
    private Optional<JavaSootMethod> resolve(ClassType type, MethodSubSignature signature) {
        Deque<ClassType> pending = new ArrayDeque<>(); pending.add(type);
        Set<ClassType> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            ClassType current = pending.removeFirst();
            if (!visited.add(current)) continue;
            var clazz = view.getClass(current);
            if (clazz.isEmpty()) continue;
            var method = clazz.get().getMethod(signature);
            if (method.isPresent()) return method;
            clazz.get().getSuperclass().ifPresent(pending::addFirst);
            pending.addAll(clazz.get().getInterfaces());
        }
        return Optional.empty();
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

    public record CallResolution(List<JavaSootMethod> applicationTargets, boolean hasExternalTargets) {
        public CallResolution { applicationTargets = List.copyOf(applicationTargets); }
    }
}
