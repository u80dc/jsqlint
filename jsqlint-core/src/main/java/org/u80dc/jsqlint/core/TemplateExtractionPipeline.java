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
        Map<String, String> renderAliases = new LinkedHashMap<>();
        input.renderParameters().forEach((alias, parameter) -> renderAliases.put(alias, key(keys, parameter)));
        Optional<String> fallback = input.fallback().map(parameter -> key(keys, parameter));
        // SQL 返回路径参与联合求解，不向模板暴露额外变量或改变业务参数 Map。
        input.sqlSource().ifPresent(trace -> key(keys, new TemplateSqlInput.Parameter(trace, trace.variable().type())));
        Map<String, ValueTrace> traces = new LinkedHashMap<>();
        Map<String, ParameterValueInferenceResult> parameters = new LinkedHashMap<>();
        keys.forEach((parameter, name) -> {
            checkpoint.run();
            var trace = parameter.trace(flow); traceLimits.accept(trace);
            traces.put(name, trace);
            parameters.put(name, inferParameter.infer(inference, name, trace, parameter.type()));
        });
        var state = new TemplateInferenceResult(traces, parameters, aliases, fallback, input.parameterMapName(),
                input.constants(), Optional.empty(), List.of(), List.of(), renderAliases);
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
            jointResult = Optional.of(result.withCombinations(models.stream()
                    .flatMap(model -> model.combination().stream()).toList()));
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
        List<TemplateInferenceResult.BoundModel> associatedModels = new ArrayList<>();
        for (var model : models) {
            if (model.bound().rendered().sql().isPresent()) {
                var snapshot = new RawSql.BindingSnapshot(
                        SqlBindingSnapshots.bound(model.bound(), definition.location(), model.bindings()), model.combination());
                var variant = new RawSql.Variant(model.bound().rendered().sql().orElseThrow(), List.of(),
                        input.sqlSource(), model.bindings(), savedJoint, List.of(snapshot));
                variants.add(variant);
                associatedModels.add(model.withRawSql(variant));
            } else associatedModels.add(model);
        }
        var saved = new TemplateInferenceResult(traces, parameters, aliases, fallback, input.parameterMapName(), input.constants(),
                jointResult, associatedModels, jointResult.map(JointSqlInferenceResult::issues).orElse(List.of()), renderAliases);
        var bindings = associatedModels.stream().flatMap(model -> model.bindings().stream()).distinct().toList();
        return new SqlExtraction.Statement(input.sqlSource(), definition.template(), bindings, source.batchEntry(), sourceIssues,
                definition.issues(), new RawSql(variants, definition.issues()), Optional.of(definition), Optional.of(saved));
    }

    private static String key(Map<TemplateSqlInput.Parameter, String> keys, TemplateSqlInput.Parameter parameter) {
        return keys.computeIfAbsent(parameter, ignored -> "input" + (keys.size() + 1));
    }

    private TemplateInferenceResult.BoundModel render(SqlDefinition definition, TemplateInferenceResult state, FlowPoint point,
                                                      Optional<JointSqlInferenceResult.Combination> combination, List<String> issues) {
        var selected = combination.map(JointSqlInferenceResult.Combination::parameters).orElse(Map.of());
        var environment = state.environment(selected);
        var bound = definition.render(environment, reference -> inputOrigin(state, environment, reference), checkpoint);
        var sources = new TemplateSources(state, selected, point);
        List<SqlExtraction.Binding> bindings = new ArrayList<>();
        List<JointSqlInferenceResult.BindingValue> values = new ArrayList<>();
        for (var slot : bound.bindings()) {
            checkpoint.run();
            var names = sources.keys(slot.origin());
            String type = bindingType(state, slot, names);
            var trace = sources.trace(slot.origin(), type, 0);
            traceLimits.accept(trace);
            var keyTrace = positionTrace(point, slot.position());
            var inferred = new InferredParameterValue(slot.value(), trace,
                    names.stream().map(selected::get).filter(Objects::nonNull)
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

    private static SqlValueOrigin inputOrigin(TemplateInferenceResult state, Map<String, Object> environment,
                                              SqlExpressionEvaluator.Reference reference) {
        String name = reference.name(); var path = reference.path();
        String key = state.aliases().getOrDefault(name, state.renderAliases().get(name));
        boolean parameterMap = state.parameterMapName().filter(name::equals).isPresent();
        if (key == null && parameterMap && !path.isEmpty() && path.getFirst() instanceof String property) {
            key = state.aliases().get(property); path = path.subList(1, path.size());
        }
        SqlValueOrigin origin;
        if (key != null) origin = new SqlValueOrigin.Input(key);
        else if (state.constants().containsKey(name)) {
            Object value = state.constants().get(name);
            origin = new SqlValueOrigin.Literal(value instanceof SqlValue sqlValue ? sqlValue : new SqlValue.Known(value));
        } else if (parameterMap) {
            origin = new SqlValueOrigin.Expression("Named template parameters", state.aliases().values().stream().distinct()
                    .map(input -> (SqlValueOrigin) new SqlValueOrigin.Input(input)).toList());
        } else if (state.fallback().isPresent() && environment.containsKey(name)) origin = new SqlValueOrigin.Input(state.fallback().get());
        else origin = new SqlValueOrigin.Literal(new SqlValue.Unknown("Unmapped template input: " + name));
        if (path.isEmpty()) return origin;
        var inputs = new ArrayList<SqlValueOrigin>(); inputs.add(origin);
        path.forEach(property -> inputs.add(new SqlValueOrigin.Literal(new SqlValue.Known(property))));
        return new SqlValueOrigin.Expression("Template property access", inputs);
    }

    private static String bindingType(TemplateInferenceResult state, SqlNodeRenderer.BoundParameter slot, List<String> names) {
        if (!slot.type().isEmpty()) return slot.type();
        if (slot.origin() instanceof SqlValueOrigin.Input input && state.parameters().containsKey(input.name()))
            return state.parameters().get(input.name()).type();
        if (slot.value() instanceof SqlValue.Known known && known.value() != null) {
            String actual = known.value().getClass().getName();
            return names.stream().map(state.parameters()::get).filter(Objects::nonNull).map(ParameterValueInferenceResult::type)
                    .filter(type -> boxed(type).equals(actual)).findFirst().orElse(actual);
        }
        return "java.lang.Object";
    }
    private static String boxed(String type) {
        return switch (type) {
            case "boolean" -> "java.lang.Boolean"; case "byte" -> "java.lang.Byte"; case "short" -> "java.lang.Short";
            case "char" -> "java.lang.Character"; case "int" -> "java.lang.Integer"; case "long" -> "java.lang.Long";
            case "float" -> "java.lang.Float"; case "double" -> "java.lang.Double"; default -> type;
        };
    }

    /** 只连接渲染器返回的结构化来源，不按绑定表达式猜测 Java 参数。 */
    private final class TemplateSources {
        private final TemplateInferenceResult state;
        private final Map<String, InferredParameterValue> selected;
        private final ValueTrace.Variable position;
        private final Map<SqlValueOrigin, Map<String, ValueTrace>> traces = new IdentityHashMap<>();
        private final Map<SqlValueOrigin, List<String>> inputs = new IdentityHashMap<>();
        private int nodes;
        private TemplateSources(TemplateInferenceResult state, Map<String, InferredParameterValue> selected, FlowPoint point) {
            this.state = state; this.selected = selected;
            position = TemplateExtractionPipeline.this.variable(point, "template-value", "java.lang.Object");
        }
        private List<String> keys(SqlValueOrigin origin) {
            return inputs.computeIfAbsent(origin, ignored -> {
                var result = new LinkedHashSet<String>(); var pending = new ArrayDeque<SqlValueOrigin>(); pending.add(origin);
                Set<SqlValueOrigin> seen = Collections.newSetFromMap(new IdentityHashMap<>());
                int steps = 0;
                while (!pending.isEmpty() && ++steps <= 10000) {
                    checkpoint.run(); var value = pending.removeFirst(); if (!seen.add(value)) continue;
                    if (value instanceof SqlValueOrigin.Input input) result.add(input.name());
                    else if (value instanceof SqlValueOrigin.Expression expression) pending.addAll(expression.inputs());
                }
                return List.copyOf(result);
            });
        }
        private ValueTrace trace(SqlValueOrigin origin, String type, int depth) {
            checkpoint.run();
            var cachedByType = traces.get(origin);
            var cached = cachedByType == null ? null : cachedByType.get(type); if (cached != null) return cached;
            ValueTrace result;
            if (depth > 128 || ++nodes > 10000) result = leaf("template-value-limit", type, ValueTrace.Kind.LIMIT,
                    "Template value origin budget exceeded", new ValueTrace.Computation(ValueTrace.Operation.OPAQUE, ""));
            else result = switch (origin) {
                case SqlValueOrigin.Input input -> selected.containsKey(input.name()) ? selected.get(input.name()).propagationPath()
                        : state.sources().containsKey(input.name()) ? state.sources().get(input.name())
                        : leaf("template-input:" + input.name(), type, ValueTrace.Kind.UNRESOLVED, "Unmapped template input: " + input.name(),
                            new ValueTrace.Computation(ValueTrace.Operation.OPAQUE, ""));
                case SqlValueOrigin.Literal literal -> literal(literal.value());
                case SqlValueOrigin.Expression expression -> {
                    var variable = variable("template:" + expression.expression(), type);
                    var children = expression.inputs().stream().map(input -> trace(input, "java.lang.Object", depth + 1).root()).toList();
                    yield new ValueTrace(variable, position.callContext(), new ValueTrace.Node(variable, ValueTrace.Kind.EXPRESSION,
                            "Rendered template expression: " + expression.expression(), children,
                            new ValueTrace.Computation(ValueTrace.Operation.OPAQUE, expression.expression())));
                }
            };
            traces.computeIfAbsent(origin, ignored -> new HashMap<>()).put(type, result); return result;
        }
        private ValueTrace literal(SqlValue value) {
            if (value instanceof SqlValue.Unknown unknown) return leaf("template-literal", "java.lang.Object", ValueTrace.Kind.UNRESOLVED,
                    unknown.reason(), new ValueTrace.Computation(ValueTrace.Operation.OPAQUE, ""));
            Object raw = ((SqlValue.Known) value).value();
            boolean scalar = raw == null || raw instanceof Number || raw instanceof String || raw instanceof Character || raw instanceof Boolean;
            String text = raw == null ? "null" : scalar ? raw.toString() : "Template constant snapshot";
            return leaf("template-literal", raw == null ? "java.lang.Object" : raw.getClass().getName(), ValueTrace.Kind.CONSTANT,
                    "Template literal: " + text, new ValueTrace.Computation(raw == null ? ValueTrace.Operation.NULL
                        : scalar ? ValueTrace.Operation.LITERAL : ValueTrace.Operation.OPAQUE, text));
        }
        private ValueTrace leaf(String expression, String type, ValueTrace.Kind kind, String detail, ValueTrace.Computation computation) {
            var variable = variable(expression, type);
            return new ValueTrace(variable, position.callContext(), new ValueTrace.Node(variable, kind, detail, List.of(), computation));
        }
        private ValueTrace.Variable variable(String expression, String type) {
            return new ValueTrace.Variable(position.method(), position.statementIndex(), expression, type, position.callContext());
        }
    }

    private ValueTrace positionTrace(FlowPoint point, int position) {
        var variable = variable(point, "template-position:" + position, "int");
        return new ValueTrace(variable, point.context().snapshot(), new ValueTrace.Node(variable, ValueTrace.Kind.CONSTANT,
                "Template binding position", List.of(), new ValueTrace.Computation(ValueTrace.Operation.LITERAL, Integer.toString(position))));
    }
    private ValueTrace.Variable variable(FlowPoint point, String expression, String type) {
        return new ValueTrace.Variable(point.method().getSignature(), flow.program().statementIndex(point.method(), point.statement()),
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
