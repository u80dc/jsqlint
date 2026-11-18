package org.u80dc.jsqlint.core.dataflow;

import sootup.core.jimple.common.constant.StringConstant;
import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr;
import java.util.*;

import static org.u80dc.jsqlint.core.dataflow.DataFlowAnalysis.*;
import static org.u80dc.jsqlint.core.dataflow.FlowPoint.ValuePoint;

/** 对象分配仅代表身份；内容需要关联构造调用，未建模的对象明确保留未知内容。 @author u80dc */
final class ObjectContents {
    private final DataFlowAnalysis analysis;

    ObjectContents(DataFlowAnalysis analysis) { this.analysis = analysis; }

    Contents reaching(FlowPoint use, ValuePoint allocation) {
        Set<ValuePoint> values = new LinkedHashSet<>();
        Set<String> issues = new LinkedHashSet<>();
        Origins allocated = new Origins(List.of(allocation), true);
        analysis.backward(use, false, (point, state) -> {
            if (point.equals(allocation.point())) return Decision.stop(false);
            var site = point.invocation(analysis.program());
            if (site.isEmpty() || !site.get().invocation().getMethodSignature().getName().equals("<init>")
                    || !(site.get().invocation() instanceof AbstractInstanceInvokeExpr invoke)) return Decision.next(state);
            Origins receiver = analysis.origins(point.value(invoke.getBase()), ignored -> false);
            if (!receiver.values().contains(allocation)) return Decision.next(state);
            var signature = invoke.getMethodSignature();
            var parameters = signature.getParameterTypes().stream().map(Object::toString).toList();
            boolean string = signature.getDeclClassType().toString().equals("java.lang.String")
                    && (parameters.isEmpty() || parameters.equals(List.of("java.lang.String")));
            if (string && parameters.isEmpty()) values.add(point.value(new StringConstant("", signature.getDeclClassType())));
            else invoke.getArgs().forEach(argument -> values.add(point.value(argument)));
            if (!string) issues.add("Object content model unavailable: " + signature);
            if (allocated.aliasTo(receiver) == Alias.MUST) return Decision.stop(true);
            issues.add("Constructor receiver identity is uncertain");
            return Decision.skipCall(state);
        }, end -> {
            issues.addAll(end.issues());
            if (!end.state()) issues.add("Object initialization history ended at " + end.end());
        });
        return new Contents(List.copyOf(values), List.copyOf(issues));
    }

    record Contents(List<ValuePoint> values, List<String> issues) {}
}
