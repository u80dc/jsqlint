package org.u80dc.jsqlint.core;

/**
 * 联合路径求解的预算上限。dataflow 与参数推断的调用/数据依赖预算由 AnalyzerConfig 其余字段控制；
 * 此处只集中联合分析自身的搜索容量：候选组合数、完整来源尝试数、参数前缀矛盾检查数、
 * 每个调用帧的路径与步数预算、历史化简步数和条件模型搜索步数。
 * 各项相互独立且必须为正；提高一项不会放宽其他搜索边界。
 *
 * @author u80dc
 */
public record JointBudgets(int combinations, int attempts, int prefixChecks, int framePaths,
                           int frameSteps, int historySteps, int modelSteps) {
    public JointBudgets {
        if (combinations < 1 || attempts < 1 || prefixChecks < 1 || framePaths < 1
                || frameSteps < 1 || historySteps < 1 || modelSteps < 1)
            throw new IllegalArgumentException("Joint budgets must be positive");
    }

    public static JointBudgets defaults() {
        return new JointBudgets(128, 512, 4096, 64, 4000, 20000, 2048);
    }

    public JointBudgets withCombinations(int combinations) { return new JointBudgets(combinations, attempts, prefixChecks, framePaths, frameSteps, historySteps, modelSteps); }
    public JointBudgets withAttempts(int attempts) { return new JointBudgets(combinations, attempts, prefixChecks, framePaths, frameSteps, historySteps, modelSteps); }
    public JointBudgets withPrefixChecks(int prefixChecks) { return new JointBudgets(combinations, attempts, prefixChecks, framePaths, frameSteps, historySteps, modelSteps); }
    public JointBudgets withFramePaths(int framePaths) { return new JointBudgets(combinations, attempts, prefixChecks, framePaths, frameSteps, historySteps, modelSteps); }
    public JointBudgets withFrameSteps(int frameSteps) { return new JointBudgets(combinations, attempts, prefixChecks, framePaths, frameSteps, historySteps, modelSteps); }
    public JointBudgets withHistorySteps(int historySteps) { return new JointBudgets(combinations, attempts, prefixChecks, framePaths, frameSteps, historySteps, modelSteps); }
    public JointBudgets withModelSteps(int modelSteps) { return new JointBudgets(combinations, attempts, prefixChecks, framePaths, frameSteps, historySteps, modelSteps); }
}
