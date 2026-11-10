package org.u80dc.jsqlint.core.parameter;

import org.u80dc.jsqlint.core.dataflow.*;
import java.util.*;

/** 对同一框架快照的历史做析取化简，只有覆盖一个分支的全部后继时才消去该分支。 @author u80dc */
final class JointControlFlowHistories {
    private JointControlFlowHistories() {}

    /** maxSteps 是化简预算，来自 AnalyzerConfig 的联合求解配置；耗尽时回退原始历史。 */
    static List<List<ControlFlowEdge>> simplify(List<List<ControlFlowEdge>> histories, Runnable checkpoint, int maxSteps) {
        if (histories.isEmpty()) return List.of(List.of());
        // 异常及循环历史继续交给原有未知边界处理，不能用无环正常路径的规则消除它们。
        if (histories.stream().flatMap(Collection::stream).anyMatch(ControlFlowEdge::exceptional)) return histories;
        Set<Map<FlowPoint, FlowPoint>> cubes = new LinkedHashSet<>();
        for (var history : histories) {
            Map<FlowPoint, FlowPoint> branches = new LinkedHashMap<>();
            for (var edge : history) {
                checkpoint.run();
                var graph = edge.from().method().getBody().getControlFlowGraph();
                if (graph.successors(edge.from().statement()).size() < 2) continue;
                FlowPoint previous = branches.putIfAbsent(edge.from(), edge.to());
                if (previous != null && !previous.equals(edge.to())) return histories;
            }
            cubes.add(Map.copyOf(branches));
        }
        int steps = 0;
        while (true) {
            boolean changed = false;
            // 每轮逐个消去变量，避免同时生成一个条件集合的所有子集。
            var branches = cubes.stream().flatMap(cube -> cube.keySet().stream()).distinct().toList();
            for (FlowPoint branch : branches) {
                Map<Map<FlowPoint, FlowPoint>, Set<FlowPoint>> groups = new LinkedHashMap<>();
                for (var cube : cubes) {
                    checkpoint.run();
                    // 化简预算耗尽时回退原始历史，不丢候选，也不额外声称分析不完整。
                    if (++steps > maxSteps) return histories;
                    if (!cube.containsKey(branch)) continue;
                    var rest = new LinkedHashMap<>(cube); rest.remove(branch);
                    groups.computeIfAbsent(Map.copyOf(rest), ignored -> new HashSet<>()).add(cube.get(branch));
                }
                Set<FlowPoint> successors = new HashSet<>();
                for (var stmt : branch.method().getBody().getControlFlowGraph().successors(branch.statement()))
                    successors.add(new FlowPoint(branch.method(), stmt, branch.context()));
                for (var group : groups.entrySet()) {
                    if (!group.getValue().equals(successors)) continue;
                    for (FlowPoint successor : successors) {
                        var covered = new LinkedHashMap<>(group.getKey()); covered.put(branch, successor);
                        changed |= cubes.remove(covered);
                    }
                    changed |= cubes.add(group.getKey());
                }
            }
            if (!changed) break;
        }
        // 小的条件合取已经覆盖更严格的合取时，只保留较小者。
        List<Map<FlowPoint, FlowPoint>> minimal = cubes.stream().filter(cube -> cubes.stream()
                .noneMatch(other -> other.size() < cube.size() && cube.entrySet().containsAll(other.entrySet()))).toList();
        return minimal.stream().map(cube -> cube.entrySet().stream()
                .map(edge -> new ControlFlowEdge(edge.getKey(), edge.getValue(), false)).toList()).toList();
    }
}
