package org.u80dc.jsqlint.core.dataflow;

import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr;
import sootup.core.jimple.common.ref.*;
import sootup.core.jimple.common.stmt.AbstractDefinitionStmt;
import java.util.*;

import static org.u80dc.jsqlint.core.dataflow.DataFlowAnalysis.*;

/** 沿逆向路径传播外部调用可访问的容器，保守识别字段/数组存储导致的间接逃逸。 @author u80dc */
record HeapExposure(List<Root> roots, List<Store> stores, boolean externalCall, int position, List<String> issues) {
    static final HeapExposure EMPTY = new HeapExposure(List.of(), List.of(), false, 0, List.of());

    HeapExposure { roots = List.copyOf(roots); stores = List.copyOf(stores); issues = List.copyOf(issues); }

    HeapExposure before(DataFlowAnalysis analysis, FlowPoint point, Origins tracked, boolean collectCalls) {
        List<Root> accessible = new ArrayList<>(roots);
        List<Store> pending = new ArrayList<>(stores);
        List<String> warnings = new ArrayList<>(issues);
        boolean external = externalCall;
        int at = position + 1;
        var site = point.invocation(analysis.program().view());
        if (collectCalls && site.isPresent() && analysis.program().resolution(site.get()).hasExternalTargets()) {
            external = true;
            var arguments = new ArrayList<>(site.get().invocation().getArgs());
            if (site.get().invocation() instanceof AbstractInstanceInvokeExpr instance) arguments.add(instance.getBase());
            for (var argument : arguments) {
                if (!(argument.getType() instanceof sootup.core.types.ReferenceType)) continue;
                Origins origin = analysis.origins(point.value(argument), ignored -> false);
                accessible.add(new Root(origin, at));
                if (tracked.values().isEmpty() || tracked.values().stream()
                        .anyMatch(value -> compatible(argument.getType(), value.value().getType())) && mayAlias(origin, tracked))
                    warnings.add("Unmodeled external object consumer: " + site.get().invocation().getMethodSignature());
            }
        }
        if (external && point.statement() instanceof AbstractDefinitionStmt assignment) {
            var left = assignment.getLeftOp();
            var container = left instanceof JInstanceFieldRef field ? field.getBase()
                    : left instanceof JArrayRef array ? array.getBase() : null;
            if ((container != null || left instanceof JStaticFieldRef)
                    && assignment.getRightOp().getType() instanceof sootup.core.types.ReferenceType) {
                pending.add(new Store(container == null ? Optional.empty()
                        : Optional.of(analysis.origins(point.value(container), ignored -> false)),
                        analysis.origins(point.value(assignment.getRightOp()), ignored -> false), at, point.value(left).snapshot().toString()));
            }
        }
        // 两层字段的赋值顺序可能相反，暂存未连通的存储边并迭代展开，不能只检查当前一条赋值。
        boolean changed;
        do {
            analysis.checkpoint();
            changed = false;
            for (var iterator = pending.iterator(); iterator.hasNext();) {
                Store store = iterator.next();
                // 逆序位置较小的外部调用才发生在存储之后，不能让更早的调用读取未来的存储。
                int callAt = store.container().isEmpty() ? 0 : accessible.stream()
                        .filter(root -> root.at() <= store.at() && mayAlias(root.value(), store.container().get()))
                        .mapToInt(Root::at).min().orElse(-1);
                if (callAt < 0) continue;
                Root root = new Root(store.value(), callAt);
                if (!accessible.contains(root)) accessible.add(root);
                if (mayAlias(store.value(), tracked)) warnings.add("Object escaped through heap storage before external call: " + store.location());
                iterator.remove(); changed = true;
            }
        } while (changed);
        return new HeapExposure(accessible, pending, external, at, warnings.stream().distinct().toList());
    }

    private static boolean mayAlias(Origins left, Origins right) {
        // 不同入口引用通常可能别名，但 String 等普通对象不可能就是被追踪的数组。
        // Object/Cloneable/Serializable 则可能以宽类型持有数组，必须保留。
        if (left.complete() && right.complete() && left.values().stream().noneMatch(a -> right.values().stream()
                .anyMatch(b -> compatible(a.value().getType(), b.value().getType())))) return false;
        return left.aliasTo(right) != Alias.NONE;
    }

    private static boolean compatible(sootup.core.types.Type left, sootup.core.types.Type right) {
        if (left instanceof sootup.core.types.ArrayType && !(right instanceof sootup.core.types.ArrayType)) return arraySupertype(right);
        if (right instanceof sootup.core.types.ArrayType && !(left instanceof sootup.core.types.ArrayType)) return arraySupertype(left);
        return true;
    }

    private static boolean arraySupertype(sootup.core.types.Type type) {
        return Set.of("java.lang.Object", "java.lang.Cloneable", "java.io.Serializable").contains(type.toString());
    }

    private record Root(Origins value, int at) {}
    private record Store(Optional<Origins> container, Origins value, int at, String location) {}
}
