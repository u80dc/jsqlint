package org.u80dc.jsqlint.jdbc;

import org.u80dc.jsqlint.core.dataflow.*;
import org.u80dc.jsqlint.core.spi.*;
import sootup.core.jimple.common.constant.*;
import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr;
import java.util.*;

import static org.u80dc.jsqlint.core.dataflow.FlowPoint.ValuePoint;
import static org.u80dc.jsqlint.core.dataflow.DataFlowAnalysis.Decision;
import static org.u80dc.jsqlint.core.dataflow.DataFlowAnalysis.Alias;

/**
 * JDBC 语义适配：语句工厂、绑定覆盖/清除和批次快照。对象别名、CFG 与过程间遍历由 core 提供。
 * 每次提取使用独立状态，不把其他调用上下文中的 SQL、参数或 Statement 混入当前结果。
 * @author u80dc
 */
final class JdbcSqlExtractor implements SqlExtractionHandler {
    private static final Set<String> SETTERS = Set.of("setNull", "setBoolean", "setByte", "setShort", "setInt",
            "setLong", "setFloat", "setDouble", "setBigDecimal", "setString", "setBytes", "setDate", "setTime",
            "setTimestamp", "setAsciiStream", "setUnicodeStream", "setBinaryStream", "setObject", "setCharacterStream",
            "setRef", "setBlob", "setClob", "setArray", "setURL", "setRowId", "setNString", "setNCharacterStream",
            "setNClob", "setSQLXML");

    @Override
    public List<SqlTraceSource> extract(FlowPoint call, DataFlowAnalysis analysis) {
        InvocationSite site = call.invocation(analysis.program().view()).orElseThrow();
        if (JdbcAnalysisModule.hasSqlArgument(site.invocation().getMethodSignature()))
            return List.of(SqlTraceSource.sql(call.value(site.invocation().getArg(0))));
        if (!(site.invocation() instanceof AbstractInstanceInvokeExpr instance))
            return List.of(unresolved("JDBC invocation has no receiver"));
        var session = new Extraction(analysis);
        var origins = session.origins(call.value(instance.getBase()));
        if (origins.values().isEmpty()) return List.of(unresolved("Cannot resolve JDBC statement receiver"));
        List<SqlTraceSource> results = new ArrayList<>();
        boolean batch = Set.of("executeBatch", "executeLargeBatch").contains(site.invocation().getMethodSignature().getName());
        for (ValuePoint origin : origins.values()) {
            List<String> issues = origins.complete() && origins.values().size() == 1 ? List.of()
                    : List.of("Statement receiver has unresolved or multiple candidate origins");
            results.addAll(batch ? session.batch(call, origin, issues) : session.prepared(call, origin, issues));
        }
        return mergePaths(results);
    }

    /** 相同状态只输出一次，同时保留产生该状态的多条控制路径（析取），不把它们错误合取。 */
    private static List<SqlTraceSource> mergePaths(List<SqlTraceSource> sources) {
        Map<SqlTraceSource, Set<List<ControlFlowEdge>>> grouped = new LinkedHashMap<>();
        for (var source : sources) {
            var key = new SqlTraceSource(source.sql(), source.bindings(), source.batchEntry(), source.issues());
            var paths = grouped.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
            if (source.controlFlowPaths().isEmpty()) paths.add(List.of());
            else paths.addAll(source.controlFlowPaths());
        }
        return grouped.entrySet().stream().map(entry -> new SqlTraceSource(entry.getKey().sql(), entry.getKey().bindings(),
                entry.getKey().batchEntry(), entry.getKey().issues(), new ArrayList<>(entry.getValue()))).toList();
    }

    private static SqlTraceSource unresolved(String reason) {
        return new SqlTraceSource(Optional.empty(), List.of(), Optional.empty(), List.of(reason));
    }
    private static boolean factory(InvocationSite site) {
        String name = site.invocation().getMethodSignature().getName();
        return (name.equals("createStatement") || name.equals("prepareStatement") || name.equals("prepareCall"))
                && site.isDeclaredOnSubtypeOf("java.sql.Connection");
    }

    private static final class Extraction {
        private final DataFlowAnalysis analysis;
        private final Map<ValuePoint, DataFlowAnalysis.Origins> origins = new HashMap<>();
        Extraction(DataFlowAnalysis analysis) { this.analysis = analysis; }
        DataFlowAnalysis.Origins origins(ValuePoint value) {
            return origins.computeIfAbsent(value, key -> analysis.origins(key, JdbcSqlExtractor::factory));
        }
        private Alias receiver(FlowPoint point, InvocationSite site, ValuePoint origin) {
            if (!(site.invocation() instanceof AbstractInstanceInvokeExpr instance)
                    || !site.isDeclaredOnSubtypeOf("java.sql.Statement")) return Alias.NONE;
            return origins(point.value(instance.getBase())).aliasTo(new DataFlowAnalysis.Origins(List.of(origin), true));
        }
        private boolean origin(FlowPoint point, InvocationSite site, ValuePoint origin) {
            return factory(site) && point.value(site.invocation()).equals(origin);
        }
        /** prepared 与 batch 共用的调用点前置分类；factory 命中时短路别名解析，NONE 别名时才检查外部消费边界。 */
        private record Classification(InvocationSite site, boolean factory, Alias alias, Optional<String> external) {}
        private Optional<Classification> classify(FlowPoint point, ValuePoint origin) {
            return point.invocation(analysis.program().view()).map(site -> {
                if (origin(point, site, origin)) return new Classification(site, true, Alias.NONE, Optional.empty());
                Alias alias = receiver(point, site, origin);
                return new Classification(site, false, alias,
                        alias == Alias.NONE ? externalConsumer(point, site, origin) : Optional.empty());
            });
        }
        List<SqlTraceSource> prepared(FlowPoint call, ValuePoint origin, List<String> initialIssues) {
            List<SqlTraceSource> results = new ArrayList<>();
            analysis.backward(call, new State(Map.of(), false, Optional.empty(), initialIssues), (point, state) -> {
                var classified = classify(point, origin);
                if (classified.isEmpty()) return Decision.next(state);
                InvocationSite site = classified.get().site();
                String name = site.invocation().getMethodSignature().getName();
                if (classified.get().factory()) {
                    if (name.equals("prepareStatement") || name.equals("prepareCall"))
                        return Decision.stop(new State(state.bindings(), state.cleared(), Optional.of(point.value(site.invocation().getArg(0))), state.issues()));
                    return Decision.stop(state.issue("No prepared SQL on Statement factory"));
                }
                Alias alias = classified.get().alias();
                if (alias == Alias.NONE) return Decision.next(classified.get().external().map(state::issue).orElse(state));
                boolean uncertainReceiver = alias == Alias.MAY;
                if (uncertainReceiver) state = state.issue("Operation may target another statement: " + site.location());
                if (name.equals("clearParameters") && site.isDeclaredOnSubtypeOf("java.sql.PreparedStatement"))
                    return Decision.skipCall(new State(state.bindings(), !uncertainReceiver || state.cleared(), state.sql(), state.issues()));
                if (setter(site)) {
                    if (state.cleared()) return Decision.skipCall(state);
                    ValuePoint keyInput = point.value(site.invocation().getArg(0));
                    String key = key(keyInput);
                    boolean unknown = key.startsWith("unknown:");
                    // setNull 的第二个参数是 JDBC 类型，绑定值是 null，不能误追踪为 Types.INTEGER 等常量。
                    ValuePoint value = point.value(name.equals("setNull") ? NullConstant.getInstance() : site.invocation().getArg(1));
                    var bindings = new LinkedHashMap<>(state.bindings());
                    // may-alias setter 不强杀更早的确定绑定，否则会把“可能写入”冒充“必定覆盖”。
                    String storageKey = uncertainReceiver ? "candidate:" + site.location() + ":" + key : key;
                    String valueType = name.equals("setNull") ? value.value().getType().toString()
                            : site.invocation().getMethodSignature().getParameterTypes().get(1).toString();
                    bindings.putIfAbsent(storageKey, new SqlTraceSource.Binding(key, keyInput, value, site.location(), valueType));
                    State next = new State(bindings, false, state.sql(), state.issues());
                    return Decision.skipCall(unknown ? next.issue("Dynamic parameter key may overlap other bindings: " + key) : next);
                }
                return Decision.next(state);
            }, end -> {
                State state = end.state();
                var issues = new ArrayList<>(state.issues()); issues.addAll(end.issues());
                if (end.end() != DataFlowAnalysis.End.FOUND) issues.add("Statement history ended at " + end.end());
                if (state.sql().isEmpty()) issues.add("Prepared SQL origin is unavailable");
                results.add(new SqlTraceSource(state.sql(), new ArrayList<>(state.bindings().values()), Optional.empty(), issues, List.of(end.path())));
            });
            return mergePaths(results);
        }

        /** 逆序收集 addBatch 时刻的快照；executeBatch 前最后一次 setXxx 不会覆盖已经加入的批次。 */
        List<SqlTraceSource> batch(FlowPoint call, ValuePoint origin, List<String> initialIssues) {
            List<SqlTraceSource> results = new ArrayList<>();
            analysis.backward(call, new BatchState(List.of(), initialIssues), (point, state) -> {
                var classified = classify(point, origin);
                if (classified.isEmpty()) return Decision.next(state);
                InvocationSite site = classified.get().site();
                if (classified.get().factory()) return Decision.stop(state);
                Alias alias = classified.get().alias();
                if (alias == Alias.NONE) return Decision.next(classified.get().external().map(state::issue).orElse(state));
                String name = site.invocation().getMethodSignature().getName();
                boolean executed = name.equals("executeBatch") || name.equals("executeLargeBatch");
                if (!name.equals("clearBatch") && !name.equals("addBatch") && !executed) return Decision.next(state);
                if (alias == Alias.MAY) state = state.issue("Batch operation may target another statement: " + site.location());
                // 可能清理时保留此前批次作为候选；确定清理才可截断历史。
                // 正常完成的批次执行清空已提交命令；异常边由 core 跳过强更新并附带诊断。
                if (name.equals("clearBatch") || executed) return alias == Alias.MUST ? Decision.stop(state) : Decision.skipCall(state);
                List<SqlTraceSource> snapshots = site.invocation().getArgCount() == 1
                        // 接收者来源诊断属于批次历史，由外层 BatchState 合并一次；嵌套快照只报告自身遍历的问题。
                        ? List.of(SqlTraceSource.sql(point.value(site.invocation().getArg(0)))) : prepared(point, origin, List.of());
                var copy = new ArrayList<>(state.entries());
                for (SqlTraceSource source : snapshots) copy.add(new SqlTraceSource(source.sql(), source.bindings(), Optional.of(site.location()), source.issues(), source.controlFlowPaths()));
                return Decision.skipCall(new BatchState(copy, state.issues()));
            }, end -> {
                var entries = new ArrayList<>(end.state().entries()); Collections.reverse(entries);
                var historyIssues = new ArrayList<>(end.state().issues()); historyIssues.addAll(end.issues());
                if (end.end() != DataFlowAnalysis.End.FOUND) historyIssues.add("Batch history ended at " + end.end());
                for (SqlTraceSource entry : entries) {
                    var issues = new ArrayList<>(entry.issues()); issues.addAll(historyIssues);
                    List<List<ControlFlowEdge>> paths = entry.controlFlowPaths().isEmpty() ? List.<List<ControlFlowEdge>>of(List.of()) : entry.controlFlowPaths();
                    var combined = paths.stream().map(path -> {
                        var edges = new LinkedHashSet<>(path); edges.addAll(end.path()); return List.copyOf(edges);
                    }).toList();
                    results.add(new SqlTraceSource(entry.sql(), entry.bindings(), entry.batchEntry(), issues, combined));
                }
                // 无已知 SQL 时仍输出未知来源，避免把丢失的诊断解释为“确定空批次”。
                if (entries.isEmpty() && !historyIssues.isEmpty())
                    results.add(new SqlTraceSource(Optional.empty(), List.of(), Optional.empty(), historyIssues));
            });
            return mergePaths(results);
        }
        private boolean setter(InvocationSite site) {
            var signature = site.invocation().getMethodSignature();
            if (!SETTERS.contains(signature.getName()) || signature.getParameterTypes().size() < 2) return false;
            String type = signature.getParameterTypes().getFirst().toString();
            return type.equals("int") && site.isDeclaredOnSubtypeOf("java.sql.PreparedStatement")
                    || type.equals("java.lang.String") && site.isDeclaredOnSubtypeOf("java.sql.CallableStatement");
        }
        private String key(ValuePoint input) {
            var resolved = analysis.origins(input, ignored -> false);
            if (resolved.complete() && resolved.values().size() == 1) {
                var value = resolved.values().getFirst().value();
                if (value instanceof IntConstant constant && constant.getValue() > 0) return Integer.toString(constant.getValue());
                if (value instanceof StringConstant constant) return ":" + constant.getValue();
            }
            return "unknown:" + input.snapshot();
        }
        private Optional<String> externalConsumer(FlowPoint point, InvocationSite site, ValuePoint origin) {
            if (!analysis.program().resolution(site).hasExternalTargets()) return Optional.empty();
            // 即使虚调用同时包含项目内实现，外部实现仍可能修改 Statement；绑定与批次共用此边界检查。
            for (var argument : site.invocation().getArgs()) if (origins(point.value(argument)).values().contains(origin))
                return Optional.of("Unmodeled external statement consumer: " + site.invocation().getMethodSignature());
            return Optional.empty();
        }
    }
    private record BatchState(List<SqlTraceSource> entries, List<String> issues) {
        BatchState { entries = List.copyOf(entries); issues = List.copyOf(issues); }
        BatchState issue(String issue) { var copy = new ArrayList<>(issues); copy.add(issue); return new BatchState(entries, copy); }
    }
    private record State(Map<String, SqlTraceSource.Binding> bindings, boolean cleared, Optional<ValuePoint> sql, List<String> issues) {
        State { bindings = Collections.unmodifiableMap(new LinkedHashMap<>(bindings)); issues = List.copyOf(issues); }
        State issue(String issue) { var copy = new ArrayList<>(issues); copy.add(issue); return new State(bindings, cleared, sql, copy); }
    }
}
