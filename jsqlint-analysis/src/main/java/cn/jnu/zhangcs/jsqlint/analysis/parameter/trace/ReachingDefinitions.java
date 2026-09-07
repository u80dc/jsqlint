package cn.jnu.zhangcs.jsqlint.analysis.parameter.trace;

import sootup.core.jimple.common.stmt.JAssignStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.SootMethod;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 过程内到达定义分析（reaching definitions, may-analysis）。
 *
 * <p>基于 SootUp {@link sootup.core.graph.StmtGraph} 的 worklist 迭代实现：
 * 对每个语句求 {@code OUT[s] = GEN[s] ∪ (IN[s] − KILL[s])}，沿正常控制流边传播直至不动点。
 * 异常边（{@code exceptionalSuccessors}）不参与传播——try-with-resources 产生的
 * catch 边会把资源变量与迭代器变量污染进定义集合，而 SQL 绑定值分析不关心异常路径。</p>
 *
 * <p>每个方法体只求解一次，结果供 {@link ValueModelResolver} 在解析局部变量时
 * 查询"该使用点可达的全部定义"，替代线性最近定义回溯。</p>
 *
 * @author zhangcs
 */
public final class ReachingDefinitions {

    private final Map<Stmt, Set<Definition>> inSets;
    private final Map<Stmt, Set<Definition>> outSets;

    private ReachingDefinitions(Map<Stmt, Set<Definition>> inSets, Map<Stmt, Set<Definition>> outSets) {
        this.inSets = inSets;
        this.outSets = outSets;
    }

    /**
     * 对方法体求解到达定义。
     *
     * @param method 含方法体的方法
     * @return 求解结果
     */
    public static ReachingDefinitions analyze(SootMethod method) {
        List<Stmt> stmts = method.getBody().getStmts();
        // gen/kill 按"变量名 → 定义该变量的全部语句"预计算，KILL 即同名的其他定义
        Map<String, Set<Stmt>> defsByLocal = collectDefs(stmts);

        Map<Stmt, Set<Definition>> inSets = new HashMap<>();
        Map<Stmt, Set<Definition>> outSets = new HashMap<>();
        for (Stmt stmt : stmts) {
            inSets.put(stmt, new HashSet<>());
            outSets.put(stmt, gen(stmt));
        }

        Deque<Stmt> worklist = new ArrayDeque<>(stmts);
        while (!worklist.isEmpty()) {
            Stmt stmt = worklist.poll();
            Set<Definition> in = new HashSet<>();
            for (Stmt pred : method.getBody().getStmtGraph().predecessors(stmt)) {
                in.addAll(outSets.getOrDefault(pred, Set.of()));
            }
            Set<Definition> oldOut = outSets.get(stmt);
            Set<Definition> out = transfer(stmt, in, defsByLocal);
            inSets.put(stmt, in);
            if (!out.equals(oldOut)) {
                outSets.put(stmt, out);
                // 只沿正常边传播；异常边上的语句不因此重新入队
                for (Stmt succ : method.getBody().getStmtGraph().successors(stmt)) {
                    if (!worklist.contains(succ)) {
                        worklist.add(succ);
                    }
                }
            }
        }
        return new ReachingDefinitions(inSets, outSets);
    }

    /**
     * 返回语句执行前（即该使用点）可达的局部变量定义集合。
     *
     * @param localName 局部变量名
     * @param useStmt   使用该变量的语句
     * @return 可达定义；无定义返回空集
     */
    public Set<Stmt> defsReaching(String localName, Stmt useStmt) {
        Set<Stmt> result = new HashSet<>();
        for (Definition definition : inSets.getOrDefault(useStmt, Set.of())) {
            if (definition.localName().equals(localName)) {
                result.add(definition.stmt());
            }
        }
        return result;
    }

    private static Set<Definition> transfer(Stmt stmt, Set<Definition> in, Map<String, Set<Stmt>> defsByLocal) {
        Set<Definition> out = new HashSet<>(in);
        if (stmt instanceof JAssignStmt assign && assign.getLeftOp() instanceof sootup.core.jimple.basic.Local left) {
            // KILL：同名其他定义全部失效
            for (Stmt other : defsByLocal.getOrDefault(left.getName(), Set.of())) {
                out.removeIf(def -> def.localName().equals(left.getName()) && def.stmt() == other);
            }
            out.add(new Definition(left.getName(), stmt));
        }
        return out;
    }

    private static Set<Definition> gen(Stmt stmt) {
        if (stmt instanceof JAssignStmt assign
                && assign.getLeftOp() instanceof sootup.core.jimple.basic.Local left) {
            return new HashSet<>(List.of(new Definition(left.getName(), stmt)));
        }
        return Set.of();
    }

    private static Map<String, Set<Stmt>> collectDefs(List<Stmt> stmts) {
        Map<String, Set<Stmt>> defsByLocal = new HashMap<>();
        for (Stmt stmt : stmts) {
            if (stmt instanceof JAssignStmt assign
                    && assign.getLeftOp() instanceof sootup.core.jimple.basic.Local left) {
                defsByLocal.computeIfAbsent(left.getName(), ignored -> new HashSet<>()).add(stmt);
            }
        }
        return defsByLocal;
    }

    /**
     * 一条定义：变量名 + 定义语句。
     */
    record Definition(String localName, Stmt stmt) {
    }
}
