package org.u80dc.jsqlint.core;

import org.u80dc.jsqlint.core.node.SqlNodeRenderer.BoundSql;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.core.parameter.JointSqlInferenceResult;
import org.u80dc.jsqlint.core.spi.SourceLocation;
import java.util.*;

/** 将已保存的参数化渲染或联合模型转为同一绑定快照，不推断参数或访问代码输入。 @author u80dc */
final class SqlBindingSnapshots {
    private SqlBindingSnapshots() {}

    static List<SqlSample.Binding> bound(BoundSql bound, SourceLocation location, List<SqlExtraction.Binding> evidence) {
        if (!evidence.isEmpty() && evidence.size() != bound.bindings().size())
            throw new IllegalArgumentException("Binding evidence must match the saved rendering order");
        var snapshots = new ArrayList<SqlSample.Binding>();
        for (int i = 0; i < bound.bindings().size(); i++) {
            var slot = bound.bindings().get(i);
            var source = evidence.isEmpty() ? Optional.<SqlExtraction.Binding>empty() : Optional.of(evidence.get(i));
            String type = source.map(value -> value.inference().type()).orElseGet(() ->
                    !slot.type().isEmpty() ? slot.type() : slot.value() instanceof SqlValue.Known known && known.value() != null
                            ? known.value().getClass().getName() : "java.lang.Object");
            snapshots.add(new SqlSample.Binding(Integer.toString(slot.position()), type, new SqlValue.Known(slot.position()),
                    slot.value(), source.map(SqlExtraction.Binding::location).orElse(location), slot.options(),
                    source.map(SqlExtraction.Binding::value)));
        }
        return List.copyOf(snapshots);
    }

    static List<RawSql.BindingSnapshot> models(List<SqlExtraction.Binding> sources, Optional<JointSqlInferenceResult> joint) {
        if (joint.isPresent() && joint.get().status() == JointSqlInferenceResult.Status.UNSATISFIABLE) return List.of();
        if (joint.isEmpty() || joint.get().combinations().isEmpty()) {
            String reason = joint.isEmpty() ? "Joint path inference was not requested" : "No joint parameter combination is available";
            return List.of(new RawSql.BindingSnapshot(unknown(sources, reason), Optional.empty()));
        }
        return joint.get().combinations().stream()
                .filter(combination -> combination.status() != JointSqlInferenceResult.Status.UNSATISFIABLE)
                .map(combination -> new RawSql.BindingSnapshot(selected(sources, combination), Optional.of(combination))).toList();
    }

    private static List<SqlSample.Binding> selected(List<SqlExtraction.Binding> sources, JointSqlInferenceResult.Combination combination) {
        var snapshots = new ArrayList<SqlSample.Binding>();
        for (int i = 0; i < sources.size(); i++) {
            var source = sources.get(i);
            if (i >= combination.bindings().size()) snapshots.add(unknown(source, "Joint binding value is unavailable"));
            else {
                var value = combination.bindings().get(i);
                snapshots.add(new SqlSample.Binding(source.key(), source.inference().type(), value.keyValue().value(),
                        value.value().value(), source.location(), source.options(), Optional.of(value.value().propagationPath())));
            }
        }
        return List.copyOf(snapshots);
    }

    static List<SqlSample.Binding> unknown(List<SqlExtraction.Binding> sources, String reason) {
        return sources.stream().map(source -> unknown(source, reason)).toList();
    }

    private static SqlSample.Binding unknown(SqlExtraction.Binding source, String reason) {
        return new SqlSample.Binding(source.key(), source.inference().type(), new SqlValue.Unknown(reason),
                new SqlValue.Unknown(reason), source.location(), source.options(), Optional.of(source.value()));
    }
}
