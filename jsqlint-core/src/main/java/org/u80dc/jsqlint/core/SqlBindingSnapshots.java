package org.u80dc.jsqlint.core;

import org.u80dc.jsqlint.core.node.SqlNodeRenderer.BoundSql;
import org.u80dc.jsqlint.core.node.SqlNodeRenderer.BoundParameter;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.core.dataflow.ValueTraceGraphs;
import org.u80dc.jsqlint.core.parameter.InferredParameterValue;
import org.u80dc.jsqlint.core.parameter.JointSqlInferenceResult;
import org.u80dc.jsqlint.core.spi.SourceLocation;
import java.util.*;
import java.util.function.Consumer;

/** 将已保存的参数化渲染或联合模型转为同一绑定快照，不推断参数或访问代码输入。 @author u80dc */
final class SqlBindingSnapshots {
    private SqlBindingSnapshots() {}

    /** 最终快照是一个模型的值视图；来源和联合摘要不能赋予它另一套参数值。 */
    static void validateVariant(List<SqlExtraction.Binding> sources, Optional<JointSqlInferenceResult> joint,
                                List<RawSql.BindingSnapshot> snapshots) {
        if (joint.filter(result -> result.status() == JointSqlInferenceResult.Status.UNSATISFIABLE).isPresent()
                && !snapshots.isEmpty())
            throw new IllegalArgumentException("An unsatisfiable SQL candidate cannot have binding models");
        var combinations = new ArrayList<JointSqlInferenceResult.Combination>();
        for (var snapshot : snapshots) {
            var combination = snapshot.combination();
            if (combination.isPresent()) {
                var value = combination.get();
                if (value.status() == JointSqlInferenceResult.Status.UNSATISFIABLE)
                    throw new IllegalArgumentException("An unsatisfiable combination cannot have a final binding snapshot");
                if (combinations.contains(value))
                    throw new IllegalArgumentException("A SQL candidate cannot have duplicate binding models");
                combinations.add(value);
                if (joint.isPresent() && !joint.get().combinations().contains(value))
                    throw new IllegalArgumentException("The final binding model must belong to its joint result");
                validateCombinationSnapshot(value, snapshot.bindings());
            } else if (joint.isPresent() && !joint.get().combinations().isEmpty()) {
                throw new IllegalArgumentException("A solved SQL candidate must associate its final binding model");
            }
            if (!sources.isEmpty()) {
                if (snapshot.bindings().size() != sources.size())
                    throw new IllegalArgumentException("Final bindings must match their source order");
                if (combination.isPresent()) {
                    if (combination.get().bindings().size() != sources.size()
                            || !snapshot.bindings().equals(selected(sources, combination.get())))
                        throw new IllegalArgumentException("Final bindings must preserve their joint values and source evidence");
                } else {
                    for (int i = 0; i < sources.size(); i++) {
                        var source = sources.get(i); var binding = snapshot.bindings().get(i);
                        if (!binding.key().equals(source.key()) || !binding.type().equals(source.inference().type())
                                || !binding.location().equals(source.location()) || !binding.options().equals(source.options()))
                            throw new IllegalArgumentException("Final bindings must preserve their source metadata");
                        if (binding.value() instanceof SqlValue.Known && source.inference().possibleValues().stream()
                                .noneMatch(value -> value.value().equals(binding.value())
                                    && binding.trace().map(value.propagationPath()::equals).orElse(true)))
                            throw new IllegalArgumentException("A known final binding must preserve an inferred source value");
                    }
                }
            }
        }
    }

    private static void validateCombinationSnapshot(JointSqlInferenceResult.Combination combination,
                                                   List<SqlSample.Binding> bindings) {
        if (bindings.size() != combination.bindings().size())
            throw new IllegalArgumentException("Final bindings must match their joint combination");
        for (int i = 0; i < bindings.size(); i++) {
            var binding = bindings.get(i); var selected = combination.bindings().get(i);
            if (!binding.key().equals(selected.key()) || !binding.keyValue().equals(selected.keyValue().value())
                    || !binding.value().equals(selected.value().value())
                    || binding.trace().filter(trace -> !trace.equals(selected.value().propagationPath())).isPresent())
                throw new IllegalArgumentException("Final bindings must preserve their joint positions, values and evidence");
        }
    }

    /** 普通语句没有另一个 BoundModel 读取路径，必须完整关联每个可输出的联合组合。 */
    static void validateStatementBindings(List<SqlExtraction.Binding> bindings, RawSql rawSql) {
        for (var variant : rawSql.variants()) {
            if (!variant.bindings().equals(bindings))
                throw new IllegalArgumentException("Statement bindings must match their raw SQL sources");
            variant.jointInference().ifPresent(joint -> {
                for (var combination : joint.combinations()) {
                    if (combination.status() != JointSqlInferenceResult.Status.UNSATISFIABLE
                            && combination.bindings().size() != bindings.size())
                        throw new IllegalArgumentException("Joint bindings must match their statement source order");
                    if (combination.status() != JointSqlInferenceResult.Status.UNSATISFIABLE
                            && variant.bindingSnapshots().stream().noneMatch(snapshot ->
                                snapshot.combination().filter(combination::equals).isPresent()))
                        throw new IllegalArgumentException("The statement must preserve each joint binding model");
                }
            });
        }
    }

    /** 去重只是组装优化；容量不足时保留完整证据列表。 */
    static List<SqlExtraction.Binding> modelBindings(List<TemplateInferenceResult.BoundModel> models,
            ReplayBudgets budgets, Runnable checkpoint, Consumer<String> limits) {
        checkpoint.run();
        var all = models.stream().flatMap(model -> model.bindings().stream()).toList();
        // 同一模型的位置连续且唯一；只有多个模型的证据并集需要结构去重。
        if (models.size() < 2) return all;
        try {
            return distinctBindings(all, new ValueTraceGraphs.Keys(budgets.steps(), budgets.depth(), checkpoint),
                    new ValueTraceGraphs.Work(budgets.steps(), checkpoint));
        } catch (ValueTraceGraphs.Limit limit) {
            limits.accept(limit.getMessage());
            return all;
        }
    }

    static void validateModelBindings(List<SqlExtraction.Binding> bindings, List<TemplateInferenceResult.BoundModel> models) {
        var all = models.stream().flatMap(model -> model.bindings().stream()).toList();
        // 未去重的列表仍完整保存全部模型，身份比较不启动深图遍历。
        if (sameBindings(bindings, all)) return;
        var distinct = distinctBindings(all, new ValueTraceGraphs.Keys(Integer.MAX_VALUE, Integer.MAX_VALUE, () -> {}),
                new ValueTraceGraphs.Work(Integer.MAX_VALUE, () -> {}));
        if (!sameBindings(bindings, distinct))
            throw new IllegalArgumentException("Statement bindings must preserve their template models");
    }

    private static boolean sameBindings(List<SqlExtraction.Binding> first, List<SqlExtraction.Binding> second) {
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) if (!first.get(i).equals(second.get(i))) return false;
        return true;
    }

    private record ValueKey(SqlValue value, Object trace, List<InferredParameterValue.InitialValue> initials, List<String> issues) {}
    private record InferenceKey(String name, String type, List<ValueKey> values, List<String> issues, boolean truncated) {}
    private record BindingKey(String key, Object keyTrace, Object trace, SourceLocation location, InferenceKey inference, String options) {}

    private static List<SqlExtraction.Binding> distinctBindings(List<SqlExtraction.Binding> bindings,
            ValueTraceGraphs.Keys keys, ValueTraceGraphs.Work work) {
        Map<BindingKey, SqlExtraction.Binding> distinct = new LinkedHashMap<>();
        for (var binding : bindings) {
            work.step();
            List<ValueKey> values = new ArrayList<>();
            for (var value : binding.inference().possibleValues()) {
                work.step();
                values.add(new ValueKey(value.value(), keys.key(value.propagationPath(), work), value.initialValues(), value.issues()));
            }
            var inference = binding.inference();
            var key = new BindingKey(binding.key(), keys.key(binding.keyTrace(), work), keys.key(binding.value(), work), binding.location(),
                    new InferenceKey(inference.name(), inference.type(), List.copyOf(values), inference.issues(), inference.truncated()), binding.options());
            distinct.putIfAbsent(key, binding);
        }
        return List.copyOf(distinct.values());
    }

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
