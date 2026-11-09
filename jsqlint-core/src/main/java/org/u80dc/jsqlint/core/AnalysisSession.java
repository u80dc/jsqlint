package org.u80dc.jsqlint.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.parameter.ValueTraceAnalyzer;
import org.u80dc.jsqlint.core.parameter.ParameterValueInferencer;
import org.u80dc.jsqlint.core.parameter.ParameterValueInferenceResult;
import org.u80dc.jsqlint.core.parameter.RandomExternalValueGenerator;
import org.u80dc.jsqlint.core.parameter.InferredParameterValue;
import org.u80dc.jsqlint.core.parameter.JointSqlValueInferencer;
import org.u80dc.jsqlint.core.dataflow.*;
import org.u80dc.jsqlint.core.spi.*;
import org.u80dc.jsqlint.core.resource.AnalysisResource;
import sootup.core.model.SourceType;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.views.JavaView;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.JavaSootClass;

import java.net.URI;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import static org.u80dc.jsqlint.core.AnalysisListener.Stage;
import static org.u80dc.jsqlint.core.AnalysisResult.Status;

/**
 * 一次调用独占的可变状态；不保存在 Analyzer 或静态缓存中。
 *
 * @author u80dc
 */
final class AnalysisSession {
    private static final Logger LOG = LoggerFactory.getLogger(AnalysisSession.class);
    private final AnalyzerConfig config;
    private final AnalysisOptions options;
    private final long started = System.nanoTime();
    private final List<Registered> modules = new ArrayList<>();
    // 发现阶段保留起点，待跨资源定义和调用者索引准备完毕后再求值。
    private final List<Pending> pending = new ArrayList<>();
    private final Map<MethodSignature, List<InvocationSite>> callers = new LinkedHashMap<>();
    private final List<JavaSootClass> applicationClasses = new ArrayList<>();
    private final List<PendingExtraction> extractionRequests = new ArrayList<>();
    private final List<SqlResult> sqlResults = new ArrayList<>();
    private final List<AnalysisDiagnostic> diagnostics = new ArrayList<>();
    private Optional<SourceLocation> diagnosticLocation = Optional.empty();
    private Stage stage = Stage.PREPARING;
    private long resources, methods, analyzed;
    private boolean partial;

    /** config 已与单次覆盖项合并；缓存、计时起点等状态仍在每次构造时独立创建。 */
    AnalysisSession(AnalyzerConfig config, AnalysisOptions options) {
        this.config = config;
        this.options = options;
    }

    /** 在工作边界协作停止；单次第三方编译或解析调用仍需自行返回。 */
    void checkpoint() {
        if (Thread.currentThread().isInterrupted() || options.cancelled().getAsBoolean())
            throw new Stop(Status.CANCELLED);
        if (!config.timeout().isZero() && System.nanoTime() - started >= config.timeout().toNanos())
            throw new Stop(Status.PARTIAL);
    }

    void progress(Stage next) {
        stage = next;
        notifyListener(listener -> listener.onProgress(new AnalysisListener.Progress(
                stage, resources, methods, pending.size(), analyzed)));
    }

    void diagnostic(String code, String message) {
        diagnostic(code, message, false);
    }

    private void diagnostic(String code, String message, boolean incomplete) {
        var diagnostic = new AnalysisDiagnostic(code, stage, diagnosticLocation, incomplete, message);
        diagnostics.add(diagnostic);
        partial |= incomplete;
        notifyListener(listener -> listener.onDiagnostic(diagnostic));
    }

    /** 已跳过或截断的工作会影响终态；普通未知参数只保留符号值，不走此分支。 */
    void incomplete(String code, String message) {
        diagnostic(code, message, true);
    }

    private void notifyListener(Consumer<AnalysisListener> action) {
        try { action.accept(options.listener()); }
        catch (RuntimeException e) {
            recordListenerFailure(e);
            throw new ListenerFailure(e);
        }
    }

    Status execute(AnalysisRequest request, List<SqlAnalysisModule> extensions) throws Exception {
        checkpoint();
        // 每次重新注册，模块可以在 register 内创建本次会话专用的处理器和缓存。
        for (SqlAnalysisModule module : extensions) {
            DiscoveryRegistry registry = new DiscoveryRegistry();
            module.register(registry);
            modules.add(new Registered(module.id(), registry.resources(), registry.methods(), registry.invocations(),
                    registry.sqlMethods(), registry.sqlInvocations(), registry.extractions(), registry.isCallIndexRequired()));
        }
        JavaView view = new JavaView(new ArrayList<>(request.locations()));
        progress(Stage.RESOURCES);
        // 相同 URI 只枚举一次，但仍向所有匹配模块分发，不采用首个模块独占的方式。
        Set<URI> visitedResources = new HashSet<>();
        if (modules.stream().anyMatch(module -> !module.resources().isEmpty())) {
            for (var source : request.resources()) {
                checkpoint();
                try (var stream = source.resources()) {
                    var iterator = stream.iterator();
                    while (iterator.hasNext()) {
                        checkpoint();
                        AnalysisResource resource = iterator.next();
                        if (!visitedResources.add(resource.uri())) continue;
                        resources++;
                        for (Registered module : modules) dispatch(module.id(), module.resources(), resource);
                        progress(stage);
                    }
                } catch (Exception e) { recover("RESOURCE_DISCOVERY", "Cannot enumerate resources", e); }
            }
        }
        progress(Stage.CODE);
        boolean codeRequested = modules.stream().anyMatch(module ->
                !module.methods().isEmpty() || !module.sqlMethods().isEmpty()
                        || !module.sqlInvocations().isEmpty() || module.callIndexRequired());
        boolean callIndexRequired = modules.stream().anyMatch(Registered::callIndexRequired);
        boolean invocationsRequested = callIndexRequired
                || modules.stream().anyMatch(module -> !module.sqlInvocations().isEmpty());
        Set<Object> visitedClasses = new HashSet<>();
        if (codeRequested) for (var location : request.locations()) {
            checkpoint();
            if (location.getSourceType() == SourceType.Library) continue;
            // 发现阶段只扫描应用类；JDK 和依赖留在视图中，供后续按需解析。
            try (var sources = location.getClassSources(view)) {
                var iterator = sources.iterator();
                while (iterator.hasNext()) {
                    checkpoint();
                    var source = iterator.next();
                    // 组合输入可能同时包含应用和依赖，还需检查每个类的实际来源。
                    if (source.getAnalysisInputLocation().getSourceType() == SourceType.Library
                            || !visitedClasses.add(source.getClassType())) continue;
                    try {
                        var clazz = view.getClass(source.getClassType()).orElseThrow();
                        if (!clazz.isApplicationClass()) continue;
                        applicationClasses.add(clazz);
                        for (var method : clazz.getMethods()) {
                            checkpoint();
                            methods++;
                            for (Registered module : modules) {
                                if (matches(module.id(), module.sqlMethods(), rule -> rule.test(method, view)))
                                    reportCallSite(module.id(), SqlCallSite.declaration(method));
                            }
                            for (Registered module : modules) dispatch(module.id(), module.methods(), method);
                            // 仅检查注解的模块不必加载方法体；调用点处理器共享同一次 Jimple 扫描。
                            if (invocationsRequested && method.hasBody()) {
                                try {
                                    var statements = method.getBody().getStmts();
                                    for (int i = 0; i < statements.size(); i++) {
                                        checkpoint();
                                        var statement = statements.get(i);
                                        if (!statement.isInvokableStmt()) continue;
                                        var invocation = statement.asInvokableStmt().getInvokeExpr();
                                        if (invocation.isEmpty()) continue;
                                        var site = new InvocationSite(method, statement, i, invocation.get(), view);
                                        // 普通辅助方法的调用也要入索引，否则无法沿调用链追踪 SQL 参数。
                                        if (callIndexRequired)
                                            callers.computeIfAbsent(invocation.get().getMethodSignature(), key -> new ArrayList<>()).add(site);
                                        for (Registered module : modules) {
                                            if (matches(module.id(), module.sqlInvocations(), rule -> rule.test(site)))
                                                reportCallSite(module.id(), SqlCallSite.invocation(site));
                                        }
                                        for (Registered module : modules) dispatch(module.id(), module.invocations(), site);
                                        for (Registered module : modules) for (var rule : module.extractions()) {
                                            if (matches(module.id(), List.of(rule), candidate -> candidate.matches().test(site)))
                                                extractionRequests.add(new PendingExtraction(module.id(), site, rule.handler()));
                                        }
                                    }
                                } catch (Exception e) { recover("METHOD_BODY", method.getSignature().toString(), e); }
                            }
                            progress(stage);
                        }
                    } catch (Exception e) { recover("CLASS_DISCOVERY", source.getClassType().toString(), e); }
                }
            } catch (Exception e) { recover("CODE_DISCOVERY", "Cannot enumerate application classes", e); }
        }
        progress(Stage.ANALYZING);
        // 此时所有资源定义和应用调用点已就绪，避免分析结果受发现顺序影响。
        ProgramIndex program = extractionRequests.isEmpty() ? null : new ProgramIndex(view, applicationClasses,
                callers.values().stream().flatMap(Collection::stream).toList(), this::checkpoint);
        ValueTraceAnalyzer tracing = new ValueTraceAnalyzer(view, program == null ? callers : program.callers(), config.maxCallDepth(),
                config.maxTraceDepth(), config.maxTraceNodes(), config.methodCacheSize(), this::checkpoint, this::incomplete);
        Set<ExtractionPoint> extractionPoints = new HashSet<>();
        for (var requestPoint : extractionRequests) extractionPoints.add(new ExtractionPoint(requestPoint.moduleId(),
                requestPoint.site().method().getSignature(), requestPoint.site().statement()));
        if (extensions.isEmpty()) diagnostic("NO_MODULES", "No SQL discovery modules registered");
        for (Pending item : pending) {
            checkpoint();
            diagnosticLocation = Optional.of(item.seed().location());
            int diagnosticStart = diagnostics.size();
            SqlNode template;
            boolean coveredByExtraction = false;
            try {
                SqlInput input = item.seed().input();
                // JDBC 同时注册旧模板起点和完整溯源处理器，同一调用点只保存完整结果。
                coveredByExtraction = input instanceof SqlInput.ValueAt value && extractionPoints.contains(
                        new ExtractionPoint(item.moduleId(), value.method().getSignature(), value.statement()));
                template = switch (input) {
                    case SqlInput.Template source -> source.node();
                    case SqlInput.ValueAt source -> tracing.sql(source);
                };
            } catch (Exception e) {
                recover("SQL_EXTRACTION", item.seed().location().toString(), e);
                // 已确认的起点不能因为适配失败而消失，仍输出带来源的未知 SQL。
                template = ValueSqlNode.raw("unresolvedSql");
            }
            var node = template;
            SqlNodeRenderer.RenderedSql rendered = null;
            try {
                rendered = SqlNodeRenderer.render(node, item.seed().parameters());
            } catch (Exception e) { recover("SQL_RENDERING", item.seed().location().toString(), e); }
            if (!coveredByExtraction) {
                // 未求值的动态结构也是有效结果；先保存，监听器失败时调用方仍可取回。
                var result = new SqlTemplateResult(item.moduleId(), item.seed().location(), node,
                        Optional.ofNullable(rendered), diagnostics.subList(diagnosticStart, diagnostics.size()));
                sqlResults.add(result);
                notifyListener(listener -> listener.onSqlResult(result));
            }
            if (rendered != null) {
                checkpoint();
                var value = rendered;
                notifyListener(listener -> listener.onSql(item.moduleId(), item.seed(), node, value));
                analyzed++;
            }
            diagnosticLocation = Optional.empty();
            progress(stage);
        }
        if (program != null) {
            var dataFlow = new DataFlowAnalysis(program, config.maxCallDepth(), config.maxTraceDepth(), config.maxTraceNodes(),
                    config.methodCacheSize(), this::checkpoint, this::incomplete);
            for (PendingExtraction extraction : extractionRequests) extract(extraction, dataFlow, tracing);
        }
        return partial ? Status.PARTIAL : Status.SUCCESS;
    }

    private void extract(PendingExtraction request, DataFlowAnalysis flow, ValueTraceAnalyzer tracing) {
        diagnosticLocation = Optional.of(request.site().location());
        var jointInference = new JointSqlValueInferencer(flow);
        for (var context : flow.contexts(request.site().method())) {
            checkpoint();
            List<SqlExtraction.Statement> statements = new ArrayList<>();
            List<String> issues = new ArrayList<>();
            if (!context.complete()) issues.add("Caller chain ended at " + context.end());
            try {
                var point = new FlowPoint(request.site().method(), request.site().statement(), context.context());
                for (SqlTraceSource source : request.handler().extract(point, flow)) {
                    checkpoint();
                    Optional<ValueTrace> sql = source.sql().map(flow::trace);
                    SqlNode template = ValueSqlNode.raw("unresolvedSql");
                    // 调用链截断影响该上下文下的每条 SQL，必须随来源进入联合求解及每个组合。
                    // 仅降低路径判定的确定性，不抹掉已恢复的常量和参数传播链。
                    var sourceIssues = new ArrayList<>(issues);
                    sourceIssues.addAll(source.issues());
                    List<String> templateIssues = new ArrayList<>();
                    if (source.sql().isPresent()) {
                        try {
                            var recovered = tracing.recoverSql(source.sql().get().input(), source.sql().get().point().context());
                            template = recovered.template();
                            templateIssues.addAll(recovered.issues());
                        }
                        catch (Exception e) {
                            // 文本恢复失败不应丢失已经定位的变量、绑定值和调用链。
                            recover("SQL_TEMPLATE", source.sql().get().snapshot().toString(), e);
                            templateIssues.add("SQL template recovery failed: " + e.getMessage());
                        }
                    } else templateIssues.add("SQL source is unavailable");
                    // 同一语句的绑定值与拼接槽位共用输入样例；不同调用上下文不共享可变推断状态。
                    var inference = new ParameterValueInferencer(new RandomExternalValueGenerator(), this::checkpoint);
                    List<SqlExtraction.Binding> bindings = new ArrayList<>();
                    for (var binding : source.bindings()) {
                        var value = flow.trace(binding.value());
                        bindings.add(new SqlExtraction.Binding(binding.key(), flow.trace(binding.keyInput()), value,
                                binding.location(), inferParameter(inference, ParameterValueInferencer.variableName(value.root()), value, binding.valueType())));
                    }
                    RawSql rawSql;
                    try {
                        rawSql = sql.map(trace -> RawSqlBuilder.build(trace, this::checkpoint))
                                .orElseGet(() -> new RawSql(List.of(), List.of("SQL source is unavailable")));
                    } catch (Exception e) {
                        // raw SQL 恢复与来源保存隔离；失败时仍保留已经完成的 SQL/绑定参数传播链。
                        recover("RAW_SQL", request.site().location().toString(), e);
                        rawSql = new RawSql(List.of(), List.of("Raw SQL reconstruction failed: " + e.getMessage()));
                    }
                    rawSql = inferPlaceholders(rawSql, inference);
                    try { rawSql = jointInference.infer(rawSql, bindings, point, source.controlFlowPaths(), sourceIssues); }
                    catch (Exception e) {
                        // 联合求解是进一步精化，失败不能抹掉已经提取的 SQL、来源和独立参数候选。
                        recover("JOINT_PATH_INFERENCE", request.site().location().toString(), e);
                        var failed = new org.u80dc.jsqlint.core.parameter.JointSqlInferenceResult(
                                org.u80dc.jsqlint.core.parameter.JointSqlInferenceResult.Status.UNKNOWN,
                                List.of(), List.of("Joint path inference failed: " + e.getMessage()));
                        rawSql = new RawSql(rawSql.variants().stream().map(variant -> new RawSql.Variant(
                                variant.text(), variant.placeholders(), variant.trace(), bindings, Optional.of(failed))).toList(), rawSql.issues());
                    }
                    statements.add(new SqlExtraction.Statement(sql, template, bindings, source.batchEntry(), sourceIssues, templateIssues, rawSql));
                }
            } catch (Exception e) {
                recover("SQL_DATA_FLOW", request.site().location().toString(), e);
                issues.add("Extraction failed: " + e.getMessage());
            }
            var extraction = new SqlExtraction(request.moduleId(), SqlCallSite.invocation(request.site()),
                    context.context().snapshot(), statements, issues);
            sqlResults.add(extraction);
            if (!extraction.complete()) incomplete("SQL_DATA_FLOW_INCOMPLETE", request.site().location().toString());
            var uncovered = extraction.rawSqls().stream().flatMap(variant -> variant.jointInference().stream())
                    .filter(joint -> !joint.coverageComplete()).toList();
            if (!uncovered.isEmpty()) {
                var reasons = uncovered.stream().flatMap(joint -> java.util.stream.Stream.concat(joint.issues().stream(),
                        joint.combinations().stream().flatMap(combination -> combination.issues().stream()))).distinct().toList();
                incomplete("JOINT_ANALYSIS_INCOMPLETE", reasons.isEmpty() ? "Joint analysis did not cover all paths" : String.join("; ", reasons));
            }
            LOG.info("SQL 提取: module={}, call={}, chain={}, complete={}, issues={}", request.moduleId(),
                    extraction.callSite(), extraction.callChain(), extraction.complete(), extraction.issues());
            for (var statement : statements) {
                LOG.info("SQL 来源: batch={}, bindings={}, issues={}, templateComplete={}, templateIssues={}",
                        statement.batchEntry(), statement.bindings().size(), statement.issues(), statement.templateComplete(), statement.templateIssues());
                for (int i = 0; i < statement.rawSql().variants().size(); i++) {
                    var variant = statement.rawSql().variants().get(i);
                    String label = "rawSql[" + (i + 1) + "]";
                    LOG.info("Raw SQL: variant={}, sql={}, placeholders={}, complete={}, issues={}", label, variant.text(),
                            variant.placeholders().stream().map(RawSql.Placeholder::name).toList(), statement.rawSql().complete(), statement.rawSql().issues());
                    // 同一调用点可能有多条相同文本，使用候选编号关联各自选中的传播分支。
                    variant.trace().ifPresent(trace -> printTrace(label, trace));
                    variant.placeholders().stream().map(RawSql.Placeholder::inference).distinct().forEach(this::printInference);
                    variant.jointInference().ifPresent(joint -> {
                        if (!LOG.isInfoEnabled()) return;
                        LOG.info("SQL 联合路径: variant={}, status={}, combinations={}, issues={}",
                                label, joint.status(), joint.combinations().size(), joint.issues());
                        // 组合级未知原因与参数值一起输出，避免汇总 issues 为空时无法解释 UNKNOWN。
                        for (int combinationIndex = 0; combinationIndex < joint.combinations().size(); combinationIndex++) {
                            var combination = joint.combinations().get(combinationIndex);
                            LOG.info("SQL 联合组合: variant={}, combination={}, status={}, parameters={}, bindings={}, conditions={}, issues={}",
                                    label, combinationIndex + 1, combination.status(),
                                    combination.parameters().entrySet().stream()
                                            .map(entry -> entry.getKey() + "=" + entry.getValue().value()).toList(),
                                    combination.bindings().stream()
                                            .map(binding -> binding.keyValue().value() + "=" + binding.value().value()).toList(),
                                    combination.conditions().size(), combination.issues());
                        }
                    });
                }
                statement.sql().ifPresent(trace -> printTrace("sql", trace));
                for (var binding : statement.bindings()) {
                    printTrace("binding[" + binding.key() + "].key", binding.keyTrace());
                    printTrace("binding[" + binding.key() + "].value", binding.value());
                    printInference(binding.inference());
                }
            }
            notifyListener(listener -> listener.onSqlResult(extraction));
        }
        diagnosticLocation = Optional.empty();
    }

    private RawSql inferPlaceholders(RawSql raw, ParameterValueInferencer inferencer) {
        List<RawSql.Variant> variants = new ArrayList<>();
        for (var variant : raw.variants()) {
            Map<String, ParameterValueInferenceResult> inferred = new HashMap<>();
            List<RawSql.Placeholder> slots = new ArrayList<>();
            for (var slot : variant.placeholders()) {
                var value = inferred.computeIfAbsent(slot.name(), ignored -> inferParameter(inferencer, slot.name(),
                        new ValueTrace(slot.source().variable(), variant.trace().map(ValueTrace::callChain).orElse(List.of()), slot.source()),
                        slot.source().variable().type()));
                slots.add(new RawSql.Placeholder(slot.name(), slot.start(), slot.end(), slot.source(), value));
            }
            variants.add(new RawSql.Variant(variant.text(), slots, variant.trace(), variant.bindings()));
        }
        return new RawSql(variants, raw.issues());
    }

    private ParameterValueInferenceResult inferParameter(ParameterValueInferencer inferencer, String name,
                                                          ValueTrace trace, String expectedType) {
        try {
            var inferred = inferencer.infer(trace, expectedType);
            return new ParameterValueInferenceResult(name, inferred.type(), inferred.possibleValues(), inferred.issues(), inferred.truncated());
        } catch (Exception e) {
            // 重演失败不得丢失已保存的参数传播链；取消与监听器异常由 recover 继续向外传播。
            recover("PARAMETER_INFERENCE", trace.variable().toString(), e);
            String issue = "Parameter inference failed: " + e.getMessage();
            return new ParameterValueInferenceResult(name, expectedType, List.of(new InferredParameterValue(
                    new SqlValue.Unknown(issue), trace, List.of(), List.of(issue))), List.of(issue));
        }
    }

    private void printInference(ParameterValueInferenceResult result) {
        LOG.info("参数推断: name={}, type={}, complete={}, issues={}", result.name(), result.type(), result.complete(), result.issues());
        for (var value : result.possibleValues()) {
            LOG.info("参数推断值: name={}, value={}, sampled={}, initialValues={}, issues={}",
                    result.name(), value.value(), value.sampled(), value.initialValues(), value.issues());
            printTrace("parameter[" + result.name() + "]", value.propagationPath());
        }
    }

    private void printTrace(String label, ValueTrace trace) {
        if (!LOG.isInfoEnabled()) return;
        LOG.info("变量调用链: variable={}, source={}, chain={}, complete={}", label, trace.variable(), trace.callChain(), trace.complete());
        printTracePaths(label, trace.root(), new ArrayList<>());
    }
    private void printTracePaths(String label, ValueTrace.Node node, List<String> path) {
        checkpoint();
        var variable = node.variable();
        path.add(variable.method() + " @" + variable.statementIndex() + " " + variable.expression() + " [" + node.kind() + ": " + node.detail() + "]");
        if (node.inputs().isEmpty()) LOG.info("逆向数据链: variable={}, path={}", label, String.join(" <- ", path));
        else for (var child : node.inputs()) printTracePaths(label, child, path);
        path.removeLast();
    }

    /** 规则取并集，避免同一模块的重叠规则重复报告；单条规则失败不阻止其他规则。 */
    private <T> boolean matches(String moduleId, List<T> rules, Predicate<T> test) {
        for (T rule : rules) {
            checkpoint();
            try { if (test.test(rule)) return true; }
            catch (Exception e) { recover("SQL_CALL_SITE_DISCOVERY", moduleId, e); }
        }
        return false;
    }

    private void reportCallSite(String moduleId, SqlCallSite site) {
        LOG.info("SQL 调用点: module={}, kind={}, class={}, method={}, caller={}, location={}",
                moduleId, site.kind(), site.target().getDeclClassType(), site.target(),
                site.caller().map(Object::toString).orElse("<声明>"), site.location());
        notifyListener(listener -> listener.onSqlCallSite(moduleId, site));
    }

    private <T> void dispatch(String moduleId, List<DiscoveryRegistry.Rule<T>> rules, T input) {
        for (var rule : rules) {
            checkpoint();
            try {
                if (rule.matches().test(input)) rule.handler().discover(input, seed -> {
                    checkpoint();
                    Objects.requireNonNull(seed, "seed");
                    Objects.requireNonNull(seed.location(), "seed.location");
                    pending.add(new Pending(moduleId, seed));
                });
            } catch (Exception e) { recover("MODULE_DISCOVERY", moduleId + ": " + input, e); }
        }
    }

    private void recover(String code, String message, Exception error) {
        // 取消和监听器异常不是可恢复的局部错误，必须传播到统一入口处理终态。
        if (error instanceof Stop stop) throw stop;
        if (error instanceof ListenerFailure failure) throw failure;
        if (error instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            throw new Stop(Status.CANCELLED);
        }
        LOG.warn("{}: {}", code, message, error);
        incomplete(code, message + ": " + error.getMessage());
    }

    Status failure(Exception error) {
        if (error instanceof Stop stop) {
            if (stop.status == Status.PARTIAL) incomplete("TIMEOUT", "Analysis deadline exceeded");
            return stop.status;
        }
        if (error instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return Status.CANCELLED;
        }
        LOG.error("Analysis failed", error);
        if (!(error instanceof ListenerFailure)) {
            try { incomplete("ANALYSIS_FAILED", Objects.toString(error.getMessage(), error.getClass().getSimpleName())); }
            catch (ListenerFailure ignored) { /* notifyListener 已保存回调失败，不再次通知。 */ }
        }
        return Status.FAILED;
    }

    AnalysisResult finish(Status status) {
        // 收尾时不再检查取消或超时，确保包括提前停止在内的执行都能发出结束事件。
        try { progress(Stage.FINISHED); }
        catch (ListenerFailure e) {
            LOG.error("Analysis listener failed", e);
            status = Status.FAILED;
        }
        return new AnalysisResult(status, sqlResults, diagnostics);
    }

    /** 监听器本身失败时直接保存诊断，避免再次回调形成递归失败。 */
    private void recordListenerFailure(Exception error) {
        diagnostics.add(new AnalysisDiagnostic("LISTENER_FAILED", stage, diagnosticLocation, true,
                Objects.toString(error.getMessage(), error.getClass().getSimpleName())));
    }

    private record Pending(String moduleId, AnalysisSeed seed) {}
    private record ExtractionPoint(String moduleId, MethodSignature method, sootup.core.jimple.common.stmt.Stmt statement) {}
    private record PendingExtraction(String moduleId, InvocationSite site, SqlExtractionHandler handler) {}
    private record Registered(String id, List<DiscoveryRegistry.Rule<AnalysisResource>> resources,
                              List<DiscoveryRegistry.Rule<JavaSootMethod>> methods,
                              List<DiscoveryRegistry.Rule<InvocationSite>> invocations,
                              List<BiPredicate<JavaSootMethod, JavaView>> sqlMethods,
                              List<Predicate<InvocationSite>> sqlInvocations,
                              List<DiscoveryRegistry.ExtractionRule> extractions, boolean callIndexRequired) {}
    private static final class ListenerFailure extends RuntimeException {
        ListenerFailure(Throwable cause) { super(cause); }
    }
    private static final class Stop extends RuntimeException {
        private final Status status;
        // 这是主动停止的控制信号，省略堆栈采集。
        Stop(Status status) { super(status.toString(), null, false, false); this.status = status; }
    }
}
