package org.u80dc.jsqlint.core;

import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.SqlNodeRenderer.BoundSql;
import org.u80dc.jsqlint.core.parameter.*;

import java.util.*;

/** 输入来源、独立候选与冻结模型；名称布局集中在 layout，默认结果读取不重演。 */
public record TemplateInferenceResult(Map<String, ValueTrace> sources,
                                      Map<String, ParameterValueInferenceResult> parameters,
                                      TemplateInputLayout layout,
                                      Optional<JointSqlInferenceResult> jointInference,
                                      List<BoundModel> models, List<String> issues) {
    public TemplateInferenceResult {
        sources = immutable(sources); parameters = immutable(parameters); Objects.requireNonNull(layout);
        Objects.requireNonNull(jointInference); models = List.copyOf(models); issues = List.copyOf(issues);
        if (jointInference.isPresent()) {
            var joint = jointInference.get();
            if (joint.status() == JointSqlInferenceResult.Status.UNSATISFIABLE && !models.isEmpty())
                throw new IllegalArgumentException("An unsatisfiable template result cannot have rendering models");
            if (joint.status() != JointSqlInferenceResult.Status.UNSATISFIABLE && models.isEmpty())
                throw new IllegalArgumentException("A joint template result requires a rendering model unless proven unsatisfiable");
            for (var model : models) {
                if (model.combination().isPresent()) {
                    if (!joint.combinations().contains(model.combination().get()))
                        throw new IllegalArgumentException("A template model must belong to its saved joint result");
                } else if (joint.status() != JointSqlInferenceResult.Status.UNKNOWN || !joint.combinations().isEmpty()) {
                    throw new IllegalArgumentException("An unassociated template model requires an unknown joint result without combinations");
                }
            }
            for (var combination : joint.combinations())
                if (combination.status() != JointSqlInferenceResult.Status.UNSATISFIABLE
                        && models.stream().noneMatch(model -> model.combination().filter(combination::equals).isPresent()))
                    throw new IllegalArgumentException("A template result must preserve every available joint combination");
        }
    }
    /** 迁移构造器仅组装名称布局，不再分别保存五组布局字段。 */
    public TemplateInferenceResult(Map<String, ValueTrace> sources, Map<String, ParameterValueInferenceResult> parameters,
                                   Map<String, String> aliases, Optional<String> fallback, Optional<String> parameterMapName,
                                   Map<String, Object> constants, Optional<JointSqlInferenceResult> jointInference,
                                   List<BoundModel> models, List<String> issues, Map<String, String> renderAliases) {
        this(sources, parameters, new TemplateInputLayout(aliases, fallback, parameterMapName, constants, renderAliases),
                jointInference, models, issues);
    }
    public TemplateInferenceResult(Map<String, ValueTrace> sources, Map<String, ParameterValueInferenceResult> parameters,
                                   Map<String, String> aliases, Optional<String> fallback, Optional<String> parameterMapName,
                                   Map<String, Object> constants, Optional<JointSqlInferenceResult> jointInference,
                                   List<BoundModel> models, List<String> issues) {
        this(sources, parameters, aliases, fallback, parameterMapName, constants, jointInference, models, issues, Map.of());
    }
    private static <T> Map<String, T> immutable(Map<String, T> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
    public Map<String, String> aliases() { return layout.aliases(); }
    public Optional<String> fallback() { return layout.fallback(); }
    public Optional<String> parameterMapName() { return layout.parameterMapName(); }
    public Map<String, Object> constants() { return layout.constants(); }
    public Map<String, String> renderAliases() { return layout.renderAliases(); }
    public Map<String, Object> environment(Map<String, InferredParameterValue> model) { return layout.environment(model); }
    public boolean sourcesComplete() {
        return sources.values().stream().allMatch(ValueTrace::complete)
                && models.stream().flatMap(model -> model.bindings().stream()).allMatch(SqlExtraction.Binding::complete);
    }
    public boolean valuesComplete() { return SqlAssessmentPolicy.templateValues(this); }

    /** 一份权威的参数化渲染与 Java 绑定证据；文本候选在所属语句中由此模型导出。 */
    public record BoundModel(BoundSql bound, List<SqlExtraction.Binding> bindings,
                             Optional<JointSqlInferenceResult.Combination> combination, List<String> issues) {
        public BoundModel {
            Objects.requireNonNull(bound); bindings = List.copyOf(bindings);
            Objects.requireNonNull(combination); issues = List.copyOf(issues);
            SqlBindingSnapshots.validateBoundEvidence(bound, bindings);
            SqlBindingSnapshots.validateBoundCombination(bound, bindings, combination);
        }
    }
}
