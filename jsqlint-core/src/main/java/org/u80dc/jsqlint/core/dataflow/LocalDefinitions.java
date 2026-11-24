package org.u80dc.jsqlint.core.dataflow;

import org.u80dc.jsqlint.core.AnalysisStatistics;
import org.u80dc.jsqlint.core.statistics.StatisticsRecorder;
import sootup.core.jimple.common.Local;
import sootup.core.jimple.common.stmt.*;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootMethod;
import java.util.*;

/** 通用的控制流到达定义查询；异常边上的赋值不视为已经完成。 @author u80dc */
public final class LocalDefinitions {
    private final Runnable checkpoint;
    private final StatisticsRecorder statistics;
    private final Map<MethodSignature, Map<Query, Definitions>> cache;
    public LocalDefinitions(int capacity, Runnable checkpoint) {
        this(capacity, checkpoint, StatisticsRecorder.none());
    }
    public LocalDefinitions(int capacity, Runnable checkpoint, StatisticsRecorder statistics) {
        this.checkpoint = checkpoint;
        this.statistics = Objects.requireNonNull(statistics);
        cache = new LinkedHashMap<>(16, .75f, true) {
            protected boolean removeEldestEntry(Map.Entry<MethodSignature, Map<Query, Definitions>> entry) { return size() > capacity; }
        };
    }
    public Definitions reaching(JavaSootMethod method, Local local, Stmt use) {
        var queries = cache.computeIfAbsent(method.getSignature(), ignored -> new HashMap<>());
        Query query = new Query(local, use);
        if (queries.containsKey(query)) return queries.get(query);
        try (var timing = statistics.measure(AnalysisStatistics.Phase.INDEXING)) {
            return findDefinitions(method, local, use, queries, query);
        }
    }
    private Definitions findDefinitions(JavaSootMethod method, Local local, Stmt use,
                                         Map<Query, Definitions> queries, Query query) {
        var graph = method.getBody().getControlFlowGraph();
        Deque<Edge> work = new ArrayDeque<>();
        graph.predecessors(use).forEach(stmt -> work.add(new Edge(stmt, false)));
        graph.exceptionalPredecessors(use).forEach(stmt -> work.add(new Edge(stmt, true)));
        Set<Edge> visited = new HashSet<>();
        Set<Stmt> found = new LinkedHashSet<>();
        boolean missing = work.isEmpty();
        while (!work.isEmpty()) {
            checkpoint.run();
            Edge edge = work.removeFirst();
            if (!visited.add(edge)) continue;
            Stmt stmt = edge.statement();
            if (!edge.exceptional() && stmt instanceof AbstractDefinitionStmt definition && definition.getLeftOp().equals(local)) {
                found.add(stmt); continue;
            }
            var predecessors = graph.predecessors(stmt);
            var exceptional = graph.exceptionalPredecessors(stmt);
            if (predecessors.isEmpty() && exceptional.isEmpty()) missing = true;
            predecessors.forEach(pred -> work.add(new Edge(pred, false)));
            exceptional.forEach(pred -> work.add(new Edge(pred, true)));
        }
        var result = new Definitions(List.copyOf(found), missing);
        queries.put(query, result);
        return result;
    }
    public record Definitions(List<Stmt> statements, boolean missing) {}
    private record Query(Local local, Stmt use) {}
    private record Edge(Stmt statement, boolean exceptional) {}
}
