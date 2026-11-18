package org.u80dc.jsqlint.core;

/** 单次参数重演的候选数、节点访问、依赖深度和结果文本长度上限；独立于来源追踪和路径搜索。 */
public record ReplayBudgets(int values, int steps, int depth, int length) {
    public ReplayBudgets {
        if (values < 1 || steps < 1 || depth < 1 || length < 1)
            throw new IllegalArgumentException("Replay budgets must be positive");
    }

    public static ReplayBudgets defaults() { return new ReplayBudgets(128, 10000, 256, 1_000_000); }

    public ReplayBudgets withValues(int values) { return new ReplayBudgets(values, steps, depth, length); }
    public ReplayBudgets withSteps(int steps) { return new ReplayBudgets(values, steps, depth, length); }
    public ReplayBudgets withDepth(int depth) { return new ReplayBudgets(values, steps, depth, length); }
    public ReplayBudgets withLength(int length) { return new ReplayBudgets(values, steps, depth, length); }
}
