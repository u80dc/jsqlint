package org.u80dc.jsqlint.core;

import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.core.node.SqlNodeRenderer.BoundSql;
import org.u80dc.jsqlint.core.parameter.*;
import org.u80dc.jsqlint.core.spi.SourceLocation;
import org.u80dc.jsqlint.core.node.SqlValueSnapshots;
import java.util.*;

/** 保存模板输入、别名和联合模型；渲染阶段无需重新访问代码或组合独立候选。 @author u80dc */
public record TemplateInferenceResult(Map<String, ValueTrace> sources,
                                      Map<String, ParameterValueInferenceResult> parameters,
                                      Map<String, String> aliases, Optional<String> fallback,
                                      Optional<String> parameterMapName, Map<String, Object> constants,
                                      Optional<JointSqlInferenceResult> jointInference, List<BoundModel> models, List<String> issues,
                                      Map<String, String> renderAliases) {
    public TemplateInferenceResult {
        sources = immutable(sources); parameters = immutable(parameters); aliases = immutable(aliases);
        Objects.requireNonNull(fallback); Objects.requireNonNull(parameterMapName); constants = SqlValueSnapshots.parameters(constants);
        Objects.requireNonNull(jointInference); models = List.copyOf(models); issues = List.copyOf(issues);
        renderAliases = immutable(renderAliases);
        for (String name : renderAliases.keySet())
            if (aliases.containsKey(name) || constants.containsKey(name) || parameterMapName.filter(name::equals).isPresent())
                throw new IllegalArgumentException("Template render alias conflicts with a business or reserved name: " + name);
    }
    @Override public Map<String, Object> constants() { return SqlValueSnapshots.parameters(constants); }
    public TemplateInferenceResult(Map<String, ValueTrace> sources, Map<String, ParameterValueInferenceResult> parameters,
                                   Map<String, String> aliases, Optional<String> fallback, Optional<String> parameterMapName,
                                   Map<String, Object> constants, Optional<JointSqlInferenceResult> jointInference,
                                   List<BoundModel> models, List<String> issues) {
        this(sources, parameters, aliases, fallback, parameterMapName, constants, jointInference, models, issues, Map.of());
    }
    private static <T> Map<String, T> immutable(Map<String, T> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
    public boolean sourcesComplete() {
        return sources.values().stream().allMatch(ValueTrace::complete)
                && models.stream().flatMap(model -> model.bindings().stream()).allMatch(SqlExtraction.Binding::complete);
    }
    public boolean valuesComplete() {
        return !models.isEmpty() && models.stream().allMatch(model -> model.bound().valuesComplete()
                && model.bindings().stream().allMatch(binding -> binding.inference().complete())
                && model.combination().map(combination -> combination.parameters().values().stream()
                    .allMatch(InferredParameterValue::complete)).orElse(parameters.isEmpty()));
    }

    public record BoundModel(BoundSql bound, List<SqlExtraction.Binding> bindings,
                             Optional<JointSqlInferenceResult.Combination> combination, List<String> issues,
                             Optional<RawSql.Variant> rawSql) {
        public BoundModel {
            Objects.requireNonNull(bound); bindings = List.copyOf(bindings);
            Objects.requireNonNull(combination); issues = List.copyOf(issues);
            Objects.requireNonNull(rawSql);
            SqlBindingSnapshots.validateBoundEvidence(bound, bindings);
            SqlBindingSnapshots.validateBoundCombination(bound, bindings, combination);
            if (rawSql.isPresent()) {
                var variant = rawSql.get();
                if (bound.rendered().sql().filter(variant.text()::equals).isEmpty()
                        || !variant.placeholders().isEmpty() || !variant.bindings().equals(bindings)
                        || variant.bindingSnapshots().size() != 1)
                    throw new IllegalArgumentException("The raw SQL candidate must preserve its bound model");
                var snapshot = variant.bindingSnapshots().getFirst();
                if (!snapshot.combination().equals(combination))
                    throw new IllegalArgumentException("The raw SQL binding snapshot must preserve its joint combination");
                SqlBindingSnapshots.validateBoundSnapshot(bound, bindings, snapshot.bindings());
            }
        }

        /** 兼容尚未关联 raw SQL 的模型；其样例绑定可从保存的渲染及证据派生。 */
        public BoundModel(BoundSql bound, List<SqlExtraction.Binding> bindings,
                          Optional<JointSqlInferenceResult.Combination> combination, List<String> issues) {
            this(bound, bindings, combination, issues, Optional.empty());
        }

        public BoundModel withRawSql(RawSql.Variant variant) {
            return new BoundModel(bound, bindings, combination, issues, Optional.of(variant));
        }

        /** 已关联模型以 raw SQL 中唯一的最终快照为准；旧构造器仍保留无关联派生路径。 */
        List<SqlSample.Binding> bindingSnapshot(SourceLocation location) {
            return rawSql.map(variant -> variant.bindingSnapshots().getFirst().bindings())
                    .orElseGet(() -> SqlBindingSnapshots.bound(bound, location, bindings));
        }
    }

    /** 回退只处理一个名称；属性和下标访问仍交给表达式适配器。 */
    public Map<String, Object> environment(Map<String, InferredParameterValue> model) {
        var values = new LinkedHashMap<String, Object>();
        aliases.forEach((alias, key) -> values.put(alias, value(model, key)));
        // 根参数 Map 在加入保留名称前复制，避免自引用或别名覆盖根对象。
        parameterMapName.ifPresent(name -> values.put(name, immutable(values)));
        values.putAll(constants());
        renderAliases.forEach((alias, key) -> values.put(alias, value(model, key)));
        if (fallback.isEmpty()) return Collections.unmodifiableMap(values);
        Object single = value(model, fallback.get());
        return Collections.unmodifiableMap(new AbstractMap<>() {
            @Override public Set<Entry<String, Object>> entrySet() { return values.entrySet(); }
            @Override public boolean containsKey(Object key) { return values.containsKey(key) || identifier(key); }
            @Override public Object get(Object key) { return values.containsKey(key) ? values.get(key) : identifier(key) ? single : null; }
        });
    }
    private static boolean identifier(Object key) {
        return key instanceof String name && name.matches("[\\p{L}_$][\\p{L}\\p{N}_$]*");
    }
    private static Object value(Map<String, InferredParameterValue> model, String key) {
        var inferred = model.get(key);
        if (inferred == null) return new SqlValue.Unknown("Template parameter inference was not requested: " + key);
        return inferred.value() instanceof SqlValue.Known known ? known.value() : inferred.value();
    }
}
