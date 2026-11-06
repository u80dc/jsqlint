package org.u80dc.jsqlint.core.dataflow;

import sootup.core.jimple.common.constant.*;
import sootup.core.jimple.common.expr.JNewArrayExpr;
import sootup.core.jimple.common.ref.*;
import sootup.core.jimple.common.stmt.AbstractDefinitionStmt;
import java.util.*;

import static org.u80dc.jsqlint.core.dataflow.DataFlowAnalysis.*;
import static org.u80dc.jsqlint.core.dataflow.FlowPoint.ValuePoint;

/** 数组读取的过程间到达写入查询；只有确定别名且下标相同的写入才能覆盖历史。 @author u80dc */
final class ArrayElements {
    private final DataFlowAnalysis analysis;
    private final Map<ValuePoint, Origins> origins = new HashMap<>();

    ArrayElements(DataFlowAnalysis analysis) { this.analysis = analysis; }

    Read reaching(ValuePoint input, JArrayRef array) {
        Origins bases = origins(input.point().value(array.getBase()));
        Optional<IntConstant> index = index(input.point().value(array.getIndex()));
        Set<ValuePoint> values = new LinkedHashSet<>();
        Set<String> issues = new LinkedHashSet<>();
        if (!bases.complete()) issues.add("Array identity is unresolved");
        analysis.backward(input.point(), new State(false, HeapExposure.EMPTY), (point, state) -> {
            HeapExposure exposure = state.exposure().before(analysis, point, bases, !state.found());
            if (point.statement() instanceof AbstractDefinitionStmt definition) {
                if (!state.found() && definition.getLeftOp() instanceof JArrayRef written) {
                    Alias alias = bases.aliasTo(origins(point.value(written.getBase())));
                    Optional<IntConstant> writtenIndex = index(point.value(written.getIndex()));
                    boolean knownIndexes = index.isPresent() && writtenIndex.isPresent();
                    if (alias != Alias.NONE && (!knownIndexes || index.equals(writtenIndex))) {
                        values.add(point.value(definition.getRightOp()));
                        // 找到值以后还需查询更早的容器存储：容器可能在本次写入前就持有数组，
                        // 后续外部调用仍能修改它。此后只追踪逃逸，不再收集已覆盖的旧值或旧调用。
                        if (alias == Alias.MUST && knownIndexes) return exposure.externalCall()
                                ? Decision.next(new State(true, exposure)) : Decision.stop(new State(true, exposure));
                        issues.add("Array write may target another array or element");
                    }
                }
                ValuePoint right = point.value(definition.getRightOp());
                if (bases.complete() && bases.values().size() == 1 && bases.values().contains(right)) {
                    if (right.value() instanceof JParameterRef || right.value() instanceof JThisRef) {
                        // 到达入口数组时，元素内容属于外部输入；保留参数入口而不是数组长度。
                        if (!state.found()) values.add(right);
                        return Decision.stop(new State(true, exposure));
                    }
                    if (right.value() instanceof JNewArrayExpr) {
                        if (!state.found()) values.add(point.value(defaultValue(array)));
                        return Decision.stop(new State(true, exposure));
                    }
                }
            }
            return Decision.next(new State(state.found(), exposure));
        }, end -> {
            issues.addAll(end.issues());
            issues.addAll(end.state().exposure().issues());
            if (!end.state().found() || end.end() == End.CYCLE || end.end() == End.LIMIT)
                issues.add("Array history ended at " + end.end());
        });
        if (values.isEmpty()) issues.add("No array element source is available");
        return new Read(List.copyOf(values), List.copyOf(issues));
    }

    private Origins origins(ValuePoint point) {
        return origins.computeIfAbsent(point, value -> analysis.origins(value, ignored -> false));
    }

    private Optional<IntConstant> index(ValuePoint point) {
        Origins result = origins(point);
        if (result.complete() && result.values().size() == 1 && result.values().getFirst().value() instanceof IntConstant constant)
            return Optional.of(constant);
        return Optional.empty();
    }

    private Constant defaultValue(JArrayRef array) {
        return switch (array.getType().toString()) {
            case "boolean", "byte", "short", "char", "int" -> IntConstant.getInstance(0);
            case "long" -> LongConstant.getInstance(0);
            case "float" -> FloatConstant.getInstance(0);
            case "double" -> DoubleConstant.getInstance(0);
            default -> NullConstant.getInstance();
        };
    }

    record Read(List<ValuePoint> values, List<String> issues) {}
    private record State(boolean found, HeapExposure exposure) {}
}
