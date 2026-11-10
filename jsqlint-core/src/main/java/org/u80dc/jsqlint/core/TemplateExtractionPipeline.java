package org.u80dc.jsqlint.core;

import org.jspecify.annotations.Nullable;
import org.u80dc.jsqlint.core.dataflow.*;
import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.parameter.*;
import org.u80dc.jsqlint.core.spi.*;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** 将资源模板与真实调用实参接入通用溯源、联合求解和参数化渲染。 @author u80dc */
final class TemplateExtractionPipeline {
    private final DataFlowAnalysis flow;
    private final Runnable checkpoint;
    private final Consumer<ValueTrace> traceLimits;
    private final AnalysisErrorHandler errors;
    private final InferParameter inferParameter;
    private final BiConsumer<String, String> incomplete;

    @FunctionalInterface
    interface InferParameter {
        ParameterValueInferenceResult infer(@Nullable ParameterValueInferencer inference, String name, ValueTrace trace, String type);
    }

    TemplateExtractionPipeline(DataFlowAnalysis flow, Runnable checkpoint, Consumer<ValueTrace> traceLimits,
                               AnalysisErrorHandler errors, InferParameter inferParameter, BiConsumer<String, String> incomplete) {
        this.flow = flow; this.checkpoint = checkpoint; this.traceLimits = traceLimits;
        this.errors = errors; this.inferParameter = inferParameter;
        this.incomplete = incomplete;
    }

    SqlExtraction.Statement extract(SqlTraceSource source, FlowPoint point, @Nullable ParameterValueInferencer inference,
                                    @Nullable JointSqlValueInferencer joint, List<String> sourceIssues) {
        var input = source.templateInput().orElseThrow();
        var definition = input.definition();
        Map<TemplateSqlInput.Parameter, String> keys = new LinkedHashMap<>();
        Map<String, String> aliases = new LinkedHashMap<>();
        input.parameters().forEach((alias, parameter) -> aliases.put(alias, key(keys, parameter)));
        Optional<String> fallback = input.fallback().map(parameter -> key(keys, parameter));
        Map<String, ValueTrace> traces = new LinkedHashMap<>();
        Map<String, ParameterValueInferenceResult> parameters = new LinkedHashMap<>();
        keys.forEach((parameter, name) -> {
            checkpoint.run();
            var trace = flow.trace(parameter.value()); traceLimits.accept(trace);
            traces.put(name, trace);
            parameters.put(name, inferParameter.infer(inference, name, trace, parameter.type()));
        });
        var state = new TemplateInferenceResult(traces, parameters, aliases, fallback, input.parameterMapName(),
                input.constants(), Optional.empty(), List.of(), List.of());
        Optional<JointSqlInferenceResult> jointResult = Optional.empty();
        if (joint != null) {
            try { jointResult = Optional.of(joint.inferParameters(parameters.values(), point, source.controlFlowPaths(), sourceIssues)); }
            catch (Exception e) {
                errors.recover("JOINT_PATH_INFERENCE", definition.location().toString(), e, true);
                jointResult = Optional.of(new JointSqlInferenceResult(JointSqlInferenceResult.Status.UNKNOWN, List.of(),
                        List.of("Joint template inference failed: " + e.getMessage()), false));
            }
            // 模板条件尚未加入 Java 路径约束；一个已知模型不能证明覆盖所有模板分支。
            if (conditional(definition.template())) {
                var result = jointResult.orElseThrow();
                var issues = new ArrayList<>(result.issues()); issues.add("Template control conditions are not included in joint path solving");
                var combinations = result.combinations().stream().map(combination -> new JointSqlInferenceResult.Combination(
                        JointSqlInferenceResult.Status.UNKNOWN, combination.parameters(), combination.bindings(),
                        combination.conditions(), combination.inputs(), append(combination.issues(), issues.getLast()))).toList();
                jointResult = Optional.of(new JointSqlInferenceResult(JointSqlInferenceResult.Status.UNKNOWN, combinations, issues, false));
            }
        }
        List<TemplateInferenceResult.BoundModel> models = new ArrayList<>();
        if (jointResult.isPresent()) {
            var result = jointResult.get();
            for (var combination : result.combinations()) {
                checkpoint.run();
                if (combination.status() != JointSqlInferenceResult.Status.UNSATISFIABLE)
                    models.add(render(definition, state, point, Optional.of(combination), sourceIssues));
            }
            if (models.isEmpty() && result.status() != JointSqlInferenceResult.Status.UNSATISFIABLE)
                models.add(render(definition, state, point, Optional.empty(), append(sourceIssues, "No joint parameter combination is available")));
            // 保存最终绑定值及其来源，后续 samples() 只读取快照。
            jointResult = Optional.of(new JointSqlInferenceResult(result.status(), models.stream()
                    .flatMap(model -> model.combination().stream()).toList(), result.issues(), result.coverageComplete()));
        } else {
            models.add(render(definition, state, point, Optional.empty(),
                    append(sourceIssues, "Joint path inference was not requested")));
        }
        var renderLimits = models.stream().flatMap(model -> model.bound().rendered().diagnostics().stream())
                .filter(diagnostic -> diagnostic.code().equals("RENDER_LIMIT")).map(SqlDiagnostic::message).distinct().toList();
        renderLimits.forEach(message -> incomplete.accept("SQL_RENDER_LIMIT", message));
        if (!renderLimits.isEmpty() && jointResult.isPresent()) {
            var result = jointResult.get(); var issues = new ArrayList<>(result.issues()); issues.addAll(renderLimits);
            jointResult = Optional.of(new JointSqlInferenceResult(JointSqlInferenceResult.Status.UNKNOWN, result.combinations(), issues, false));
        }
        var savedJoint = jointResult;
        List<RawSql.Variant> variants = new ArrayList<>();
        for (var model : models) model.bound().rendered().sql().ifPresent(text -> variants.add(
                new RawSql.Variant(text, List.of(), Optional.empty(), model.bindings(), savedJoint)));
        var saved = new TemplateInferenceResult(traces, parameters, aliases, fallback, input.parameterMapName(), input.constants(),
                jointResult, models, jointResult.map(JointSqlInferenceResult::issues).orElse(List.of()));
        var bindings = models.stream().flatMap(model -> model.bindings().stream()).distinct().toList();
        return new SqlExtraction.Statement(Optional.empty(), definition.template(), bindings, source.batchEntry(), sourceIssues,
                definition.issues(), new RawSql(variants, definition.issues()), Optional.of(definition), Optional.of(saved));
    }

    private static String key(Map<TemplateSqlInput.Parameter, String> keys, TemplateSqlInput.Parameter parameter) {
        return keys.computeIfAbsent(parameter, ignored -> "input" + (keys.size() + 1));
    }

    private TemplateInferenceResult.BoundModel render(SqlDefinition definition, TemplateInferenceResult state, FlowPoint point,
                                                      Optional<JointSqlInferenceResult.Combination> combination, List<String> issues) {
        var selected = combination.map(JointSqlInferenceResult.Combination::parameters).orElse(Map.of());
        var bound = definition.render(state.environment(selected), checkpoint);
        List<SqlExtraction.Binding> bindings = new ArrayList<>();
        List<JointSqlInferenceResult.BindingValue> values = new ArrayList<>();
        for (var slot : bound.bindings()) {
            checkpoint.run();
            String name = sourceKey(state, slot.expression());
            String type = !slot.type().isEmpty() ? slot.type() : name != null ? state.parameters().get(name).type()
                    : slot.value() instanceof SqlValue.Known known && known.value() != null ? known.value().getClass().getName() : "java.lang.Object";
            var trace = expressionTrace(state, selected, point, slot.expression(), type);
            var keyTrace = positionTrace(point, slot.position());
            var selectedValue = name == null ? null : selected.get(name);
            var inferred = new InferredParameterValue(slot.value(), trace,
                    selectedValue != null ? selectedValue.initialValues() : selected.values().stream()
                        .flatMap(value -> value.initialValues().stream()).distinct().toList(),
                    slot.value() instanceof SqlValue.Unknown unknown ? List.of(unknown.reason()) : List.of());
            String key = Integer.toString(slot.position());
            var valueInference = new ParameterValueInferenceResult(slot.expression(), type, List.of(inferred), inferred.issues());
            var keyValue = new InferredParameterValue(new SqlValue.Known(slot.position()), keyTrace, List.of(), List.of());
            bindings.add(new SqlExtraction.Binding(key, keyTrace, trace, definition.location(), valueInference, slot.options()));
            values.add(new JointSqlInferenceResult.BindingValue(key, keyValue, inferred));
        }
        var enriched = combination.map(value -> new JointSqlInferenceResult.Combination(
                !bound.valuesComplete() ? JointSqlInferenceResult.Status.UNKNOWN : value.status(), value.parameters(), values,
                value.conditions(), value.inputs(), value.issues()));
        return new TemplateInferenceResult.BoundModel(bound, bindings, enriched, issues);
    }

    private static @Nullable String sourceKey(TemplateInferenceResult state, String expression) {
        String path = expression.strip();
        if (state.parameterMapName().isPresent() && path.startsWith(state.parameterMapName().get() + "."))
            path = path.substring(state.parameterMapName().get().length() + 1);
        String root = path.split("[.\\[]", 2)[0];
        if (state.aliases().containsKey(root)) return state.aliases().get(root);
        if (expression.strip().matches("[\\p{L}_$][\\p{L}\\p{N}_$]*") && !state.constants().containsKey(root))
            return state.fallback().orElse(null);
        return null;
    }

    private static ValueTrace expressionTrace(TemplateInferenceResult state, Map<String, InferredParameterValue> selected,
                                               FlowPoint point, String expression, String type) {
        String name = sourceKey(state, expression);
        if (name != null && (state.aliases().containsKey(expression.strip()) || expression.strip().matches("[\\p{L}_$][\\p{L}\\p{N}_$]*")))
            return selected.containsKey(name) ? selected.get(name).propagationPath() : state.sources().get(name);
        var variable = variable(point, "template:" + expression, type);
        var inputs = state.sources().entrySet().stream().map(entry -> selected.containsKey(entry.getKey())
                ? selected.get(entry.getKey()).propagationPath().root() : entry.getValue().root()).toList();
        return new ValueTrace(variable, point.context().snapshot(), new ValueTrace.Node(variable, ValueTrace.Kind.EXPRESSION,
                "Rendered template expression: " + expression, inputs));
    }

    private static ValueTrace positionTrace(FlowPoint point, int position) {
        var variable = variable(point, "template-position:" + position, "int");
        return new ValueTrace(variable, point.context().snapshot(), new ValueTrace.Node(variable, ValueTrace.Kind.CONSTANT,
                "Template binding position", List.of(), new ValueTrace.Computation(ValueTrace.Operation.LITERAL, Integer.toString(position))));
    }
    private static ValueTrace.Variable variable(FlowPoint point, String expression, String type) {
        return new ValueTrace.Variable(point.method().getSignature(), point.method().getBody().getStmts().indexOf(point.statement()),
                expression, type, point.context().snapshot());
    }

    private static List<String> append(List<String> issues, String issue) {
        var result = new ArrayList<>(issues); result.add(issue); return result;
    }
    private boolean conditional(SqlNode node) {
        checkpoint.run();
        return switch (node) {
            case IfSqlNode ignored -> true;
            case ChooseSqlNode ignored -> true;
            case ForEachSqlNode ignored -> true;
            case MixedSqlNode mixed -> mixed.contents().stream().anyMatch(this::conditional);
            case WhereSqlNode where -> conditional(where.contents());
            case SetSqlNode set -> conditional(set.contents());
            case TrimSqlNode trim -> conditional(trim.contents());
            default -> false;
        };
    }
}
