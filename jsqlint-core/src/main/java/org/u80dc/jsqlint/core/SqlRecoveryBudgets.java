package org.u80dc.jsqlint.core;

/** 单次 SQL 恢复的候选数、节点访问、恢复深度、结构检查深度和文本长度上限。 */
public record SqlRecoveryBudgets(int variants, int steps, int depth, int structureDepth, int length) {
    public SqlRecoveryBudgets {
        if (variants < 1 || steps < 1 || depth < 1 || structureDepth < 1 || length < 1)
            throw new IllegalArgumentException("SQL recovery budgets must be positive");
    }

    public static SqlRecoveryBudgets defaults() { return new SqlRecoveryBudgets(128, 10000, 256, 128, 1_000_000); }

    public SqlRecoveryBudgets withVariants(int variants) { return new SqlRecoveryBudgets(variants, steps, depth, structureDepth, length); }
    public SqlRecoveryBudgets withSteps(int steps) { return new SqlRecoveryBudgets(variants, steps, depth, structureDepth, length); }
    public SqlRecoveryBudgets withDepth(int depth) { return new SqlRecoveryBudgets(variants, steps, depth, structureDepth, length); }
    public SqlRecoveryBudgets withStructureDepth(int depth) { return new SqlRecoveryBudgets(variants, steps, this.depth, depth, length); }
    public SqlRecoveryBudgets withLength(int length) { return new SqlRecoveryBudgets(variants, steps, depth, structureDepth, length); }
}
