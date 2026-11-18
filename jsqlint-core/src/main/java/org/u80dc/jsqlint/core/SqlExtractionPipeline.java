package org.u80dc.jsqlint.core;

import org.jspecify.annotations.Nullable;
import org.u80dc.jsqlint.core.dataflow.*;
import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.parameter.*;
import org.u80dc.jsqlint.core.spi.*;
import org.u80dc.jsqlint.core.template.SqlTemplateProjection;
import org.u80dc.jsqlint.core.template.RawSqlBuilder;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** 会话内的 SQL 提取与推断管线；结果、监听器和执行终态由会话管理。 @author u80dc */
final class SqlExtractionPipeline {
    private final DataFlowAnalysis flow;
    private final AnalyzerConfig config;
    private final Runnable checkpoint;
    private final BiConsumer<String, String> incomplete;
    private final AnalysisErrorHandler errors;

    SqlExtractionPipeline(DataFlowAnalysis flow, AnalyzerConfig config, Runnable checkpoint,
                          BiConsumer<String, String> incomplete, AnalysisErrorHandler errors) {
        this.flow = flow; this.config = config; this.checkpoint = checkpoint;
        this.incomplete = incomplete; this.errors = errors;
    }

    SqlNode template(SqlInput.ValueAt input) {
        List<SqlNode> templates = new ArrayList<>();
        for (var context : flow.contexts(input.method())) {
            checkpoint.run();
            var trace = flow.trace(new FlowPoint(input.method(), input.statement(), context.context()).value(input.value()));
            templates.add(SqlTemplateProjection.template(rawSql(Optional.of(trace), input.toString()), config.replayBudgets(), checkpoint));
        }
        return SqlTemplateProjection.alternatives(templates);
    }

    List<SqlExtraction> extract(String moduleId, InvocationSite site, SqlExtractionHandler handler,
                                Consumer<SqlExtraction> results) {
        List<SqlExtraction> extracted = new ArrayList<>();
        var mode = config.inferenceOptions().mode();
        var joint = mode.jointEnabled() ? new JointSqlValueInferencer(flow, config.jointBudgets(), this::externalGenerator, config.replayBudgets()) : null;
        var templates = new TemplateExtractionPipeline(flow, checkpoint, this::reportTraceLimits, errors, this::inferParameter, incomplete);
        for (var context : flow.contexts(site.method())) {
            checkpoint.run();
            List<SqlExtraction.Statement> statements = new ArrayList<>();
            List<String> issues = new ArrayList<>();
            if (!context.complete()) issues.add("Caller chain ended at " + context.end());
            try {
                var point = new FlowPoint(site.method(), site.statement(), context.context());
                for (var source : handler.extract(point, flow)) {
                    checkpoint.run();
                    var sourceIssues = new ArrayList<>(issues); sourceIssues.addAll(source.issues());
                    Optional<ValueTrace> sql = source.sql().map(flow::trace);
                    // 同一语句共享入口值；其他调用上下文和批次使用独立的推断状态。
                    var inference = mode.parametersEnabled() ? new ParameterValueInferencer(externalGenerator(), checkpoint, config.replayBudgets()) : null;
                    if (source.templateInput().isPresent()) {
                        statements.add(templates.extract(source, point, inference, joint, sourceIssues));
                        continue;
                    }
                    List<SqlExtraction.Binding> bindings = new ArrayList<>();
                    for (var binding : source.bindings()) {
                        var value = flow.trace(binding.value());
                        reportTraceLimits(value);
                        var key = flow.trace(binding.keyInput()); reportTraceLimits(key);
                        bindings.add(new SqlExtraction.Binding(binding.key(), key, value, binding.location(),
                                inferParameter(inference, ParameterValueInferencer.variableName(value.root()), value, binding.valueType())));
                    }
                    RawSql raw = rawSql(sql, site.location().toString());
                    SqlNode template = ValueSqlNode.raw("unresolvedSql");
                    var templateIssues = new ArrayList<>(raw.issues());
                    try { template = SqlTemplateProjection.template(raw, config.replayBudgets(), checkpoint); }
                    catch (Exception e) {
                        errors.recover("SQL_TEMPLATE", site.location().toString(), e, false);
                        templateIssues.add("SQL template projection failed: " + e.getMessage());
                    }
                    raw = inferPlaceholders(raw, inference);
                    if (joint != null) try { raw = joint.infer(raw, bindings, point, source.controlFlowPaths(), sourceIssues); }
                    catch (Exception e) {
                        errors.recover("JOINT_PATH_INFERENCE", site.location().toString(), e, true);
                        var failed = new JointSqlInferenceResult(JointSqlInferenceResult.Status.UNKNOWN,
                                List.of(), List.of("Joint path inference failed: " + e.getMessage()));
                        raw = new RawSql(raw.variants().stream().map(variant -> new RawSql.Variant(
                                variant.text(), variant.placeholders(), variant.trace(), bindings, Optional.of(failed))).toList(), raw.issues());
                    }
                    statements.add(new SqlExtraction.Statement(sql, template, bindings, source.batchEntry(), sourceIssues, templateIssues, raw));
                }
            } catch (Exception e) {
                errors.recover("SQL_DATA_FLOW", site.location().toString(), e, true);
                issues.add("Extraction failed: " + e.getMessage());
            }
            var result = new SqlExtraction(moduleId, SqlCallSite.invocation(site), context.context().snapshot(), statements, issues);
            extracted.add(result);
            results.accept(result);
        }
        return List.copyOf(extracted);
    }

    private RawSql rawSql(Optional<ValueTrace> sql, String location) {
        sql.ifPresent(this::reportTraceLimits);
        try {
            var raw = sql.map(trace -> RawSqlBuilder.build(trace, config.sqlRecoveryBudgets(), checkpoint))
                    .orElseGet(() -> new RawSql(List.of(), List.of("SQL source is unavailable")));
            // 框架没有找到 SQL 来源时由提取结果报告，不将缺失来源误当作未知表达式。
            if (sql.isPresent() && !raw.issues().isEmpty()) incomplete.accept("UNSUPPORTED_VALUE", String.join("; ", raw.issues()));
            return raw;
        } catch (Exception e) {
            errors.recover("RAW_SQL", location, e, true);
            return new RawSql(List.of(), List.of("Raw SQL reconstruction failed: " + e.getMessage()));
        }
    }

    private void reportTraceLimits(ValueTrace trace) {
        Deque<ValueTrace.Node> pending = new ArrayDeque<>(); pending.add(trace.root());
        Set<ValueTrace.Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (!pending.isEmpty()) {
            checkpoint.run();
            var node = pending.removeFirst();
            if (!seen.add(node)) continue;
            if (node.kind() == ValueTrace.Kind.LIMIT) incomplete.accept("TRACE_LIMIT", node.detail() + ": " + node.variable());
            pending.addAll(node.inputs());
        }
    }

    private RawSql inferPlaceholders(RawSql raw, @Nullable ParameterValueInferencer inference) {
        List<RawSql.Variant> variants = new ArrayList<>();
        for (var variant : raw.variants()) {
            Map<String, ParameterValueInferenceResult> inferred = new HashMap<>();
            List<RawSql.Placeholder> slots = new ArrayList<>();
            for (var slot : variant.placeholders()) {
                var value = inferred.computeIfAbsent(slot.name(), ignored -> inferParameter(inference, slot.name(),
                        new ValueTrace(slot.source().variable(), variant.trace().map(ValueTrace::callChain).orElse(List.of()), slot.source()),
                        slot.source().variable().type()));
                slots.add(new RawSql.Placeholder(slot.name(), slot.start(), slot.end(), slot.source(), value));
            }
            variants.add(new RawSql.Variant(variant.text(), slots, variant.trace(), variant.bindings()));
        }
        return new RawSql(variants, raw.issues());
    }

    private ParameterValueInferenceResult inferParameter(@Nullable ParameterValueInferencer inference, String name, ValueTrace trace, String type) {
        if (inference == null) return new ParameterValueInferenceResult(name, type, List.of(), List.of("Parameter inference was not requested"));
        try {
            var inferred = inference.infer(trace, type);
            if (inferred.truncated()) incomplete.accept("PARAMETER_INFERENCE_LIMIT",
                    "Parameter inference was truncated: " + name + (inferred.issues().isEmpty()
                            ? "" : "; " + String.join("; ", inferred.issues())));
            return new ParameterValueInferenceResult(name, inferred.type(), inferred.possibleValues(), inferred.issues(), inferred.truncated());
        } catch (Exception e) {
            errors.recover("PARAMETER_INFERENCE", trace.variable().toString(), e, true);
            String issue = "Parameter inference failed: " + e.getMessage();
            return new ParameterValueInferenceResult(name, type, List.of(new InferredParameterValue(
                    new SqlValue.Unknown(issue), trace, List.of(), List.of(issue))), List.of(issue));
        }
    }

    private ExternalValueGenerator externalGenerator() {
        try { return config.inferenceOptions().createGenerator(); }
        catch (Exception e) {
            errors.recover("EXTERNAL_VALUE_GENERATOR", "Cannot create external value generator", e, true);
            return (type, name) -> new SqlValue.Unknown("External value generator is unavailable: " + e.getMessage());
        }
    }
}
