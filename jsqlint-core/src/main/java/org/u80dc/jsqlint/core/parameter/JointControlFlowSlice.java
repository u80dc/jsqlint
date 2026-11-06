package org.u80dc.jsqlint.core.parameter;

import sootup.core.jimple.common.Local;
import sootup.core.jimple.common.stmt.*;
import sootup.java.core.JavaSootMethod;
import java.util.*;

/** 保守识别正常控制流中不影响目标语句、局部值或堆写入的汇合分支。 @author u80dc */
final class JointControlFlowSlice {
    private final JavaSootMethod method;
    private final Runnable checkpoint;
    private final Set<Stmt> required = new HashSet<>();
    private final Set<String> locals = new HashSet<>();
    private final Map<Stmt, Optional<Stmt>> joins = new HashMap<>();
    private final boolean cyclic;

    JointControlFlowSlice(JavaSootMethod method, Set<Integer> statements, Set<Integer> returns, Runnable checkpoint) {
        this.method = method; this.checkpoint = checkpoint;
        var body = method.getBody().getStmts();
        for (int index : statements) required.add(body.get(index));
        for (int index : returns) required.add(body.get(index));
        for (Stmt stmt : body) if (required.contains(stmt) || stmt instanceof JIfStmt
                || stmt instanceof sootup.core.jimple.javabytecode.stmt.JSwitchStmt) addUses(stmt);
        boolean changed;
        do {
            int count = locals.size();
            for (Stmt stmt : body) {
                checkpoint.run();
                if (stmt instanceof AbstractDefinitionStmt definition && definition.getLeftOp() instanceof Local local
                        && locals.contains(local.toString())) addUses(stmt);
            }
            changed = locals.size() != count;
        } while (changed);
        var graph = method.getBody().getControlFlowGraph();
        Map<Stmt, Integer> incoming = new HashMap<>();
        for (Stmt stmt : body) incoming.put(stmt, graph.predecessors(stmt).size());
        Deque<Stmt> work = new ArrayDeque<>();
        incoming.forEach((stmt, count) -> { if (count == 0) work.add(stmt); });
        int visited = 0;
        while (!work.isEmpty()) {
            checkpoint.run(); visited++;
            for (Stmt next : graph.successors(work.removeFirst()))
                if (incoming.computeIfPresent(next, (ignored, count) -> count - 1) == 0) work.add(next);
        }
        cyclic = visited != body.size();
    }

    boolean cyclic() { return cyclic; }
    boolean relevant(Local local) { return locals.contains(local.toString()); }

    private void addUses(Stmt stmt) {
        for (var value : stmt.getUses()) if (value instanceof Local local) locals.add(local.toString());
    }

    Optional<Stmt> join(Stmt branch) {
        if (cyclic || required.contains(branch)) return Optional.empty();
        return joins.computeIfAbsent(branch, this::findJoin);
    }

    private Optional<Stmt> findJoin(Stmt branch) {
        var graph = method.getBody().getControlFlowGraph();
        if (!graph.exceptionalSuccessors(branch).isEmpty()) return Optional.empty();
        List<Map<Stmt, Integer>> distances = graph.successors(branch).stream().map(this::distances).toList();
        if (distances.size() < 2) return Optional.empty();
        // 先找最近的公共后继，再检查每条分支都必须正常到达它；不能跨过提前返回或异常处理。
        Stmt join = method.getBody().getStmts().stream().filter(stmt -> distances.stream().allMatch(map -> map.containsKey(stmt)))
                .min(Comparator.comparingInt(stmt -> distances.stream().mapToInt(map -> map.get(stmt)).max().orElseThrow())).orElse(null);
        if (join == null) return Optional.empty();
        Set<Stmt> visited = new HashSet<>();
        Deque<Stmt> pending = new ArrayDeque<>(graph.successors(branch));
        while (!pending.isEmpty()) {
            checkpoint.run();
            Stmt stmt = pending.removeFirst();
            if (stmt.equals(join) || !visited.add(stmt)) continue;
            if (visited.size() > 2000 || required.contains(stmt) || stmt instanceof ReturnStmt || stmt instanceof JThrowStmt
                    || !graph.exceptionalSuccessors(stmt).isEmpty() || graph.successors(stmt).isEmpty()) return Optional.empty();
            if (stmt instanceof AbstractDefinitionStmt definition
                    && (!(definition.getLeftOp() instanceof Local local) || relevant(local))) return Optional.empty();
            pending.addAll(graph.successors(stmt));
        }
        return Optional.of(join);
    }

    private Map<Stmt, Integer> distances(Stmt start) {
        Map<Stmt, Integer> result = new HashMap<>(); result.put(start, 0);
        Deque<Stmt> pending = new ArrayDeque<>(); pending.add(start);
        var graph = method.getBody().getControlFlowGraph();
        while (!pending.isEmpty()) {
            checkpoint.run();
            Stmt stmt = pending.removeFirst();
            for (Stmt next : graph.successors(stmt)) if (result.putIfAbsent(next, result.get(stmt) + 1) == null) pending.add(next);
        }
        return result;
    }
}
