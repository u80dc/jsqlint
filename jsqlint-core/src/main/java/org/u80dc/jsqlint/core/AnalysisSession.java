package org.u80dc.jsqlint.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.u80dc.jsqlint.core.SqlDiscovery.Pending;
import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.dataflow.*;
import org.u80dc.jsqlint.core.spi.*;
import sootup.core.signatures.MethodSignature;

import java.util.*;
import java.util.function.Consumer;

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
    private final SqlAnalysisLogger sqlLogger = new SqlAnalysisLogger(this::checkpoint);
    private final long started = System.nanoTime();
    private final List<SqlResult> sqlResults = new ArrayList<>();
    private final List<AnalysisDiagnostic> diagnostics = new ArrayList<>();
    private Optional<SourceLocation> diagnosticLocation = Optional.empty();
    private Stage stage = Stage.PREPARING;
    private long resources, methods, candidates, analyzed;
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
                stage, resources, methods, candidates, analyzed)));
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
        var discovery = new SqlDiscovery(this::checkpoint, this::recover, event -> {
            resources = event.resources(); methods = event.methods(); candidates = event.candidates();
            progress(event.stage());
        }, this::reportCallSite);
        var plan = discovery.discover(request, extensions);
        var pending = plan.seeds();
        var extractionRequests = plan.extractions();
        progress(Stage.ANALYZING);
        // 此时所有资源定义和应用调用点已就绪，避免分析结果受发现顺序影响。
        var program = new ProgramIndex(plan.view(), plan.classes(), plan.calls(), this::checkpoint);
        var flow = new DataFlowAnalysis(program, config.maxCallDepth(), config.maxTraceDepth(), config.maxTraceNodes(),
                config.methodCacheSize(), this::checkpoint, this::incomplete);
        var pipeline = new SqlExtractionPipeline(flow, config, this::checkpoint, this::incomplete, this::recover);
        Set<ExtractionPoint> extractionPoints = new HashSet<>();
        for (var requestPoint : extractionRequests) extractionPoints.add(new ExtractionPoint(requestPoint.moduleId(),
                requestPoint.site().method().getSignature(), requestPoint.site().statement()));
        Map<ExtractionPoint, List<Compatibility>> compatibility = new LinkedHashMap<>();
        if (extensions.isEmpty()) diagnostic("NO_MODULES", "No SQL discovery modules registered");
        for (Pending item : pending) {
            checkpoint();
            diagnosticLocation = Optional.of(item.seed().location());
            int diagnosticStart = diagnostics.size();
            SqlNode template;
            Optional<SqlDefinition> definition = Optional.empty();
            try {
                SqlInput input = item.seed().input();
                if (input instanceof SqlInput.ValueAt value) {
                    var point = new ExtractionPoint(item.moduleId(), value.method().getSignature(), value.statement());
                    if (extractionPoints.contains(point)) {
                        // 已注册完整提取的调用点只分析一次；旧事件稍后从同一份结果派生。
                        compatibility.computeIfAbsent(point, ignored -> new ArrayList<>()).add(new Compatibility(item, value));
                        diagnosticLocation = Optional.empty();
                        continue;
                    }
                }
                if (input instanceof SqlInput.Definition source) definition = Optional.of(source.definition());
                template = switch (input) {
                    case SqlInput.Template source -> source.node();
                    case SqlInput.Definition source -> source.definition().template();
                    case SqlInput.ValueAt source -> pipeline.template(source);
                };
            } catch (Exception e) {
                recover("SQL_EXTRACTION", item.seed().location().toString(), e);
                template = ValueSqlNode.raw("unresolvedSql");
            }
            publishTemplate(item, template, diagnosticStart, definition);
            diagnosticLocation = Optional.empty();
            progress(stage);
        }
        Map<ExtractionPoint, List<SqlExtraction.Statement>> compatibilityStatements = new LinkedHashMap<>();
        for (SqlDiscovery.ExtractionRequest requestPoint : extractionRequests) {
            checkpoint();
            diagnosticLocation = Optional.of(requestPoint.site().location());
            var results = pipeline.extract(requestPoint.moduleId(), requestPoint.site(), requestPoint.handler(), this::publishExtraction);
            var point = new ExtractionPoint(requestPoint.moduleId(), requestPoint.site().method().getSignature(), requestPoint.site().statement());
            if (compatibility.containsKey(point)) compatibilityStatements.computeIfAbsent(point, ignored -> new ArrayList<>())
                    .addAll(results.stream().flatMap(result -> result.statements().stream()).toList());
            diagnosticLocation = Optional.empty();
            progress(stage);
        }
        // 同一位置可有多个提取规则；兼容事件按原始 seed 发出一次，展示所有规则的候选。
        for (var entry : compatibility.entrySet()) {
            for (Compatibility legacy : entry.getValue()) {
                Pending item = legacy.item();
                diagnosticLocation = Optional.of(item.seed().location());
                var templates = compatibilityStatements.getOrDefault(entry.getKey(), List.of()).stream()
                        .filter(statement -> sourceMatches(statement, legacy.input())).map(SqlExtraction.Statement::template).toList();
                if (templates.isEmpty()) {
                    // 相同调用位置的其他 SQL 输入没有被完整提取规则覆盖，仍按原始 seed 恢复。
                    int diagnosticStart = diagnostics.size();
                    SqlNode template;
                    try { template = pipeline.template(legacy.input()); }
                    catch (Exception e) {
                        recover("SQL_EXTRACTION", item.seed().location().toString(), e);
                        template = ValueSqlNode.raw("unresolvedSql");
                    }
                    publishTemplate(item, template, diagnosticStart);
                } else {
                    var template = SqlTemplateProjection.alternatives(templates);
                    notifySql(item, template, render(item, template));
                }
                progress(stage);
            }
        }
        diagnosticLocation = Optional.empty();
        return partial ? Status.PARTIAL : Status.SUCCESS;
    }

    private boolean sourceMatches(SqlExtraction.Statement statement, SqlInput.ValueAt input) {
        if (statement.sql().isEmpty()) return false;
        var source = statement.sql().get().variable();
        return source.method().equals(input.method().getSignature())
                && source.statementIndex() == input.method().getBody().getStmts().indexOf(input.statement())
                && source.expression().equals(input.value().toString()) && source.type().equals(input.value().getType().toString());
    }

    private void publishTemplate(Pending item, SqlNode template, int diagnosticStart) {
        publishTemplate(item, template, diagnosticStart, Optional.empty());
    }

    private void publishTemplate(Pending item, SqlNode template, int diagnosticStart, Optional<SqlDefinition> definition) {
        definition.ifPresent(source -> source.issues().forEach(issue -> incomplete("SQL_DEFINITION", issue)));
        SqlNodeRenderer.RenderedSql rendered;
        try { rendered = SqlNodeRenderer.render(template, item.seed().parameters(),
                definition.map(SqlDefinition::evaluator).orElseGet(SqlExpressionEvaluator::simple)); }
        catch (Exception e) { recover("SQL_RENDERING", item.seed().location().toString(), e, false); rendered = null; }
        var result = new SqlTemplateResult(item.moduleId(), item.seed().location(), template,
                Optional.ofNullable(rendered), diagnostics.subList(diagnosticStart, diagnostics.size()), definition);
        sqlResults.add(result);
        notifyListener(listener -> listener.onSqlResult(result));
        analyzed++;
        notifySql(item, template, rendered);
    }

    private SqlNodeRenderer.RenderedSql render(Pending item, SqlNode template) {
        try { return SqlNodeRenderer.render(template, item.seed().parameters()); }
        catch (Exception e) { recover("SQL_RENDERING", item.seed().location().toString(), e, false); return null; }
    }

    private void notifySql(Pending item, SqlNode template, SqlNodeRenderer.RenderedSql rendered) {
        if (rendered == null) return;
        checkpoint();
        notifyListener(listener -> listener.onSql(item.moduleId(), item.seed(), template, rendered));
    }

    private void publishExtraction(SqlExtraction extraction) {
        sqlResults.add(extraction);
        if (!extraction.complete()) incomplete("SQL_DATA_FLOW_INCOMPLETE", extraction.location().toString());
        var uncovered = extraction.statements().stream().flatMap(statement -> statement.jointInferences().stream())
                .filter(joint -> !joint.coverageComplete()).toList();
        if (!uncovered.isEmpty()) {
            var reasons = uncovered.stream().flatMap(joint -> java.util.stream.Stream.concat(joint.issues().stream(),
                    joint.combinations().stream().flatMap(combination -> combination.issues().stream()))).distinct().toList();
            incomplete("JOINT_ANALYSIS_INCOMPLETE", reasons.isEmpty() ? "Joint analysis did not cover all paths" : String.join("; ", reasons));
        }
        sqlLogger.extraction(extraction);
        notifyListener(listener -> listener.onSqlResult(extraction));
        analyzed++;
    }

    private void reportCallSite(String moduleId, SqlCallSite site) {
        LOG.info("SQL 调用点: module={}, kind={}, class={}, method={}, caller={}, location={}",
                moduleId, site.kind(), site.target().getDeclClassType(), site.target(),
                site.caller().map(Object::toString).orElse("<声明>"), site.location());
        notifyListener(listener -> listener.onSqlCallSite(moduleId, site));
    }

    private void recover(String code, String message, Exception error) {
        recover(code, message, error, true);
    }

    private void recover(String code, String message, Exception error, boolean incomplete) {
        // 取消和监听器异常不是可恢复的局部错误，必须传播到统一入口处理终态。
        if (error instanceof Stop stop) throw stop;
        if (error instanceof ListenerFailure failure) throw failure;
        if (error instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            throw new Stop(Status.CANCELLED);
        }
        LOG.warn("{}: {}", code, message, error);
        diagnostic(code, message + ": " + error.getMessage(), incomplete);
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

    private record ExtractionPoint(String moduleId, MethodSignature method, sootup.core.jimple.common.stmt.Stmt statement) {}
    private record Compatibility(Pending item, SqlInput.ValueAt input) {}
    private static final class ListenerFailure extends RuntimeException {
        ListenerFailure(Throwable cause) { super(cause); }
    }
    private static final class Stop extends RuntimeException {
        private final Status status;
        // 这是主动停止的控制信号，省略堆栈采集。
        Stop(Status status) { super(status.toString(), null, false, false); this.status = status; }
    }
}
