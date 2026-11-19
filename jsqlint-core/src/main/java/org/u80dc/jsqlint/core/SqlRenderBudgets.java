package org.u80dc.jsqlint.core;

/** 单次 SQL 渲染的节点工作量、安全嵌套深度和输出长度；表达式及 Java 来源重演另用 ReplayBudgets。 */
public record SqlRenderBudgets(int steps, int depth, int length) {
    public SqlRenderBudgets {
        if (steps < 1 || depth < 1 || length < 1) throw new IllegalArgumentException("SQL render budgets must be positive");
    }
    public static SqlRenderBudgets defaults() { return new SqlRenderBudgets(10000, 128, 1_000_000); }
    /** 旧的单预算上下文继续同时约束节点和表达式；新的上下文可独立设置。 */
    public static SqlRenderBudgets fromReplay(ReplayBudgets budgets) {
        return new SqlRenderBudgets(budgets.steps(), Math.min(128, budgets.depth()), budgets.length());
    }
    public SqlRenderBudgets withSteps(int steps) { return new SqlRenderBudgets(steps, depth, length); }
    public SqlRenderBudgets withDepth(int depth) { return new SqlRenderBudgets(steps, depth, length); }
    public SqlRenderBudgets withLength(int length) { return new SqlRenderBudgets(steps, depth, length); }
}
