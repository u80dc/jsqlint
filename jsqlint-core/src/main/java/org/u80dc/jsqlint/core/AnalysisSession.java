package org.u80dc.jsqlint.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.parameter.ValueTraceAnalyzer;
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
    private final List<SqlExtraction> extractions = new ArrayList<>();
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
        notifyListener(listener -> listener.onDiagnostic(code, message));
    }

    /** 已跳过或截断的工作会影响终态；普通未知参数只保留符号值，不走此分支。 */
    void incomplete(String code, String message) {
        partial = true;
        diagnostic(code, message);
    }

    private void notifyListener(Consumer<AnalysisListener> action) {
        try { action.accept(options.listener()); }
        catch (RuntimeException e) { throw new ListenerFailure(e); }
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
        ValueTraceAnalyzer tracing = new ValueTraceAnalyzer(view, program == null ? callers : program.callers(), config.maxTraceDepth(),
                config.methodCacheSize(), this::checkpoint, this::incomplete);
        if (extensions.isEmpty()) diagnostic("NO_MODULES", "No SQL discovery modules registered");
        for (Pending item : pending) {
            checkpoint();
            SqlNode template;
            try {
                template = switch (item.seed().input()) {
                    case SqlInput.Template input -> input.node();
                    case SqlInput.ValueAt input -> tracing.sql(input);
                };
            } catch (Exception e) {
                recover("SQL_EXTRACTION", item.seed().location().toString(), e);
                // 已确认的起点不能因为适配失败而消失，仍输出带来源的未知 SQL。
                template = ValueSqlNode.raw("unresolvedSql");
            }
            var node = template;
            // 单个起点的渲染失败可继续处理其他起点；输出监听器失败则终止，防止结果丢失。
            try {
                var rendered = SqlNodeRenderer.render(node, item.seed().parameters());
                checkpoint();
                notifyListener(listener -> listener.onSql(item.moduleId(), item.seed(), node, rendered));
                analyzed++;
            } catch (Exception e) { recover("SQL_RENDERING", item.seed().location().toString(), e); }
            progress(stage);
        }
        if (program != null) {
            var dataFlow = new DataFlowAnalysis(program, config.maxTraceDepth(), config.methodCacheSize(), this::checkpoint, this::incomplete);
            for (PendingExtraction extraction : extractionRequests) extract(extraction, dataFlow, tracing);
        }
        return partial ? Status.PARTIAL : Status.SUCCESS;
    }

    private void extract(PendingExtraction request, DataFlowAnalysis flow, ValueTraceAnalyzer tracing) {
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
                    var sourceIssues = new ArrayList<>(source.issues());
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
                    List<SqlExtraction.Binding> bindings = new ArrayList<>();
                    for (var binding : source.bindings()) bindings.add(new SqlExtraction.Binding(binding.key(),
                            flow.trace(binding.keyInput()), flow.trace(binding.value()), binding.location()));
                    RawSql rawSql;
                    try {
                        rawSql = sql.map(trace -> RawSqlBuilder.build(trace, this::checkpoint))
                                .orElseGet(() -> new RawSql(List.of(), List.of("SQL source is unavailable")));
                    } catch (Exception e) {
                        // raw SQL 恢复与来源保存隔离；失败时仍保留已经完成的 SQL/绑定参数传播链。
                        recover("RAW_SQL", request.site().location().toString(), e);
                        rawSql = new RawSql(List.of(), List.of("Raw SQL reconstruction failed: " + e.getMessage()));
                    }
                    statements.add(new SqlExtraction.Statement(sql, template, bindings, source.batchEntry(), sourceIssues, templateIssues, rawSql));
                }
            } catch (Exception e) {
                recover("SQL_DATA_FLOW", request.site().location().toString(), e);
                issues.add("Extraction failed: " + e.getMessage());
            }
            var extraction = new SqlExtraction(request.moduleId(), SqlCallSite.invocation(request.site()),
                    context.context().snapshot(), statements, issues);
            extractions.add(extraction);
            if (!extraction.complete()) incomplete("SQL_DATA_FLOW_INCOMPLETE", request.site().location().toString());
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
                }
                statement.sql().ifPresent(trace -> printTrace("sql", trace));
                for (var binding : statement.bindings()) {
                    printTrace("binding[" + binding.key() + "].key", binding.keyTrace());
                    printTrace("binding[" + binding.key() + "].value", binding.value());
                }
            }
            notifyListener(listener -> listener.onSqlExtraction(extraction));
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
            if (stop.status == Status.PARTIAL) diagnostic("TIMEOUT", "Analysis deadline exceeded");
            return stop.status;
        }
        if (error instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return Status.CANCELLED;
        }
        LOG.error("Analysis failed", error);
        if (!(error instanceof ListenerFailure)) diagnostic("ANALYSIS_FAILED", error.getMessage());
        return Status.FAILED;
    }

    AnalysisResult finish(Status status) {
        // 收尾时不再检查取消或超时，确保包括提前停止在内的执行都能发出结束事件。
        try { progress(Stage.FINISHED); }
        catch (ListenerFailure e) {
            LOG.error("Analysis listener failed", e);
            status = Status.FAILED;
        }
        return new AnalysisResult(status, extractions);
    }

    private record Pending(String moduleId, AnalysisSeed seed) {}
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
