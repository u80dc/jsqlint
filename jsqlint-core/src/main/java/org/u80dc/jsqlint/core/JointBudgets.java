package org.u80dc.jsqlint.core;

/**
 * 联合路径求解的预算上限。来源追踪与独立参数重演的预算由 AnalyzerConfig 其余配置控制；
 * 此处只集中联合分析自身的搜索容量：候选组合数、完整来源尝试数、参数前缀矛盾检查数、
 * 调用帧及条件依赖闭包的路径与步数预算、历史化简步数、条件模型搜索步数、
 * 联合参数维度、条件依赖深度、每帧控制流路径深度，以及条件前处理/每模型求值工作量。
 * 各项相互独立且必须为正；提高一项不会放宽其他搜索边界。
 *
 * @author u80dc
 */
public record JointBudgets(int combinations, int attempts, int prefixChecks, int framePaths,
                           int frameSteps, int historySteps, int modelSteps, int dimensions,
                           int dependencyDepth, int controlFlowDepth, int predicateSteps) {
    public JointBudgets {
        if (combinations < 1 || attempts < 1 || prefixChecks < 1 || framePaths < 1
                || frameSteps < 1 || historySteps < 1 || modelSteps < 1 || dimensions < 1
                || dependencyDepth < 1 || controlFlowDepth < 1 || predicateSteps < 1)
            throw new IllegalArgumentException("Joint budgets must be positive");
    }

    /** 兼容旧构造器，条件表达式工作量保持默认容量。 */
    public JointBudgets(int combinations, int attempts, int prefixChecks, int framePaths,
                        int frameSteps, int historySteps, int modelSteps, int dimensions,
                        int dependencyDepth, int controlFlowDepth) {
        this(combinations, attempts, prefixChecks, framePaths, frameSteps, historySteps, modelSteps,
                dimensions, dependencyDepth, controlFlowDepth, 20_000);
    }

    /** 兼容旧构造器，新增的维度和深度预算保持原默认值。 */
    public JointBudgets(int combinations, int attempts, int prefixChecks, int framePaths,
                        int frameSteps, int historySteps, int modelSteps) {
        this(combinations, attempts, prefixChecks, framePaths, frameSteps, historySteps, modelSteps, 64, 256, 512);
    }

    public static JointBudgets defaults() {
        return new JointBudgets(128, 512, 4096, 64, 4000, 20000, 2048);
    }

    public JointBudgets withCombinations(int combinations) { return new JointBudgets(combinations, attempts, prefixChecks, framePaths, frameSteps, historySteps, modelSteps, dimensions, dependencyDepth, controlFlowDepth, predicateSteps); }
    public JointBudgets withAttempts(int attempts) { return new JointBudgets(combinations, attempts, prefixChecks, framePaths, frameSteps, historySteps, modelSteps, dimensions, dependencyDepth, controlFlowDepth, predicateSteps); }
    public JointBudgets withPrefixChecks(int prefixChecks) { return new JointBudgets(combinations, attempts, prefixChecks, framePaths, frameSteps, historySteps, modelSteps, dimensions, dependencyDepth, controlFlowDepth, predicateSteps); }
    public JointBudgets withFramePaths(int framePaths) { return new JointBudgets(combinations, attempts, prefixChecks, framePaths, frameSteps, historySteps, modelSteps, dimensions, dependencyDepth, controlFlowDepth, predicateSteps); }
    public JointBudgets withFrameSteps(int frameSteps) { return new JointBudgets(combinations, attempts, prefixChecks, framePaths, frameSteps, historySteps, modelSteps, dimensions, dependencyDepth, controlFlowDepth, predicateSteps); }
    public JointBudgets withHistorySteps(int historySteps) { return new JointBudgets(combinations, attempts, prefixChecks, framePaths, frameSteps, historySteps, modelSteps, dimensions, dependencyDepth, controlFlowDepth, predicateSteps); }
    public JointBudgets withModelSteps(int modelSteps) { return new JointBudgets(combinations, attempts, prefixChecks, framePaths, frameSteps, historySteps, modelSteps, dimensions, dependencyDepth, controlFlowDepth, predicateSteps); }
    public JointBudgets withDimensions(int dimensions) { return new JointBudgets(combinations, attempts, prefixChecks, framePaths, frameSteps, historySteps, modelSteps, dimensions, dependencyDepth, controlFlowDepth, predicateSteps); }
    public JointBudgets withDependencyDepth(int dependencyDepth) { return new JointBudgets(combinations, attempts, prefixChecks, framePaths, frameSteps, historySteps, modelSteps, dimensions, dependencyDepth, controlFlowDepth, predicateSteps); }
    public JointBudgets withControlFlowDepth(int controlFlowDepth) { return new JointBudgets(combinations, attempts, prefixChecks, framePaths, frameSteps, historySteps, modelSteps, dimensions, dependencyDepth, controlFlowDepth, predicateSteps); }
    public JointBudgets withPredicateSteps(int predicateSteps) { return new JointBudgets(combinations, attempts, prefixChecks, framePaths, frameSteps, historySteps, modelSteps, dimensions, dependencyDepth, controlFlowDepth, predicateSteps); }
}
