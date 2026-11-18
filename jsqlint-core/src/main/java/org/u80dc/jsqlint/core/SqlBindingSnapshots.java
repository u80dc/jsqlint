package org.u80dc.jsqlint.core;

import org.u80dc.jsqlint.core.node.SqlNodeRenderer.BoundSql;
import org.u80dc.jsqlint.core.node.SqlNodeRenderer.BoundParameter;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.core.parameter.JointSqlInferenceResult;
import org.u80dc.jsqlint.core.spi.SourceLocation;
import java.util.*;

/** 将已保存的参数化渲染或联合模型转为同一绑定快照，不推断参数或访问代码输入。 @author u80dc */
final class SqlBindingSnapshots {
    private SqlBindingSnapshots() {}

    static List<SqlSample.Binding> bound(BoundSql bound, SourceLocation location, List<SqlExtraction.Binding> evidence) {
        validateBoundEvidence(bound, evidence);
        var snapshots = new ArrayList<SqlSample.Binding>();
        for (int i = 0; i < bound.bindings().size(); i++) {
            var slot = bound.bindings().get(i);
            var source = evidence.isEmpty() ? Optional.<SqlExtraction.Binding>empty() : Optional.of(evidence.get(i));
            String type = source.map(value -> value.inference().type()).orElseGet(() -> valueType(slot));
            snapshots.add(new SqlSample.Binding(Integer.toString(slot.position()), type, new SqlValue.Known(slot.position()),
                    slot.value(), source.map(SqlExtraction.Binding::location).orElse(location), slot.options(),
                    source.map(SqlExtraction.Binding::value)));
        }
        return List.copyOf(snapshots);
    }

    static void validateBoundEvidence(BoundSql bound, List<SqlExtraction.Binding> evidence) {
        if (!evidence.isEmpty() && evidence.size() != bound.bindings().size())
            throw new IllegalArgumentException("Binding evidence must match the saved rendering order");
        for (int i = 0; i < evidence.size(); i++) {
            var slot = bound.bindings().get(i);
            var source = evidence.get(i);
            if (!source.key().equals(Integer.toString(slot.position()))
                    || !source.inference().name().equals(slot.expression()) || !source.options().equals(slot.options())
                    || !slot.type().isEmpty() && !source.inference().type().equals(slot.type())
                    || source.inference().possibleValues().stream().noneMatch(value ->
                        value.value().equals(slot.value()) && value.propagationPath().equals(source.value())))
                throw new IllegalArgumentException("Binding evidence must preserve the rendered position, expression and value");
        }
    }

    static void validateBoundCombination(BoundSql bound, List<SqlExtraction.Binding> evidence,
                                         Optional<JointSqlInferenceResult.Combination> combination) {
        if (combination.isEmpty()) return;
        var values = combination.get().bindings();
        if (values.size() != bound.bindings().size())
            throw new IllegalArgumentException("Joint bindings must match the bound model");
        for (int i = 0; i < values.size(); i++) {
            var slot = bound.bindings().get(i); var value = values.get(i);
            if (!value.key().equals(Integer.toString(slot.position()))
                    || !value.keyValue().value().equals(new SqlValue.Known(slot.position()))
                    || !value.value().value().equals(slot.value())
                    || !evidence.isEmpty() && (!value.keyValue().propagationPath().equals(evidence.get(i).keyTrace())
                        || !value.value().propagationPath().equals(evidence.get(i).value())))
                throw new IllegalArgumentException("Joint bindings must preserve the bound position, value and evidence");
        }
    }

    static void validateBoundSnapshot(BoundSql bound, List<SqlExtraction.Binding> evidence, List<SqlSample.Binding> snapshots) {
        if (snapshots.size() != bound.bindings().size())
            throw new IllegalArgumentException("Final bindings must match the bound model");
        for (int i = 0; i < snapshots.size(); i++) {
            var slot = bound.bindings().get(i); var snapshot = snapshots.get(i);
            if (!snapshot.key().equals(Integer.toString(slot.position()))
                    || !snapshot.keyValue().equals(new SqlValue.Known(slot.position())) || !snapshot.value().equals(slot.value())
                    || !snapshot.options().equals(slot.options())
                    || evidence.isEmpty() && (!snapshot.type().equals(valueType(slot)) || snapshot.trace().isPresent())
                    || !evidence.isEmpty() && (!snapshot.type().equals(evidence.get(i).inference().type())
                        || !snapshot.location().equals(evidence.get(i).location())
                        || !snapshot.trace().equals(Optional.of(evidence.get(i).value()))))
                throw new IllegalArgumentException("Final bindings must preserve their saved values and evidence");
        }
    }

    private static String valueType(BoundParameter slot) {
        return !slot.type().isEmpty() ? slot.type() : slot.value() instanceof SqlValue.Known known && known.value() != null
                ? known.value().getClass().getName() : "java.lang.Object";
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
