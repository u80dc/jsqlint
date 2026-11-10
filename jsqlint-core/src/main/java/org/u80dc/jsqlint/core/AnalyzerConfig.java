package org.u80dc.jsqlint.core;

import org.jspecify.annotations.Nullable;
import org.u80dc.jsqlint.core.spi.AnalysisInputOptions;

import java.time.Duration;
import java.util.Objects;

/**
 * Analyzer 实例级的不可变配置。只保存默认值和容量上限，不保存缓存、计时器或其他执行状态。
 * timeout 为零表示不限时；单次调用可覆盖超时和结束清理选项。
 * inputOptions 由输入扩展定义，仅在 Path 入口传递给选中的解析器；null 表示使用解析器自身配置。
 * maxCallDepth 限制调用栈，maxTraceDepth 限制数据依赖展开深度，maxTraceNodes 限制单次溯源访问节点数。
 * jointBudgets 集中联合路径求解的搜索预算。各项预算相互独立，增加表达式容量不会放宽递归调用限制。
 * inferenceOptions 设置推断阶段、随机种子及外部值工厂；每次调用仍创建独立的推断状态。
 *
 * @author u80dc
 */
public record AnalyzerConfig(Duration timeout, int maxTraceDepth, int methodCacheSize,
                             boolean cleanupClassOutput, @Nullable AnalysisInputOptions inputOptions,
                             int maxCallDepth, int maxTraceNodes, JointBudgets jointBudgets, InferenceOptions inferenceOptions) {
    public AnalyzerConfig {
        validateTimeout(timeout);
        if (maxTraceDepth < 1 || methodCacheSize < 1 || maxCallDepth < 1 || maxTraceNodes < 1) {
            throw new IllegalArgumentException("Call depth, trace depth, trace nodes and cache size must be positive");
        }
        Objects.requireNonNull(jointBudgets, "jointBudgets");
        Objects.requireNonNull(inferenceOptions, "inferenceOptions");
    }

    /** 兼容旧构造器：默认仍启用联合推断。 */
    public AnalyzerConfig(Duration timeout, int maxTraceDepth, int methodCacheSize,
                          boolean cleanupClassOutput, @Nullable AnalysisInputOptions inputOptions,
                          int maxCallDepth, int maxTraceNodes, JointBudgets jointBudgets) {
        this(timeout, maxTraceDepth, methodCacheSize, cleanupClassOutput, inputOptions, maxCallDepth, maxTraceNodes,
                jointBudgets, InferenceOptions.defaults());
    }

    /** 兼容旧构造器：显式传入的原共享深度仍同时初始化调用和数据依赖预算。 */
    public AnalyzerConfig(Duration timeout, int maxTraceDepth, int methodCacheSize,
                          boolean cleanupClassOutput, @Nullable AnalysisInputOptions inputOptions) {
        this(timeout, maxTraceDepth, methodCacheSize, cleanupClassOutput, inputOptions, maxTraceDepth, 10000);
    }

    /** 兼容旧构造器：联合求解预算使用默认值。 */
    public AnalyzerConfig(Duration timeout, int maxTraceDepth, int methodCacheSize,
                          boolean cleanupClassOutput, @Nullable AnalysisInputOptions inputOptions,
                          int maxCallDepth, int maxTraceNodes) {
        this(timeout, maxTraceDepth, methodCacheSize, cleanupClassOutput, inputOptions, maxCallDepth, maxTraceNodes, JointBudgets.defaults());
    }

    /** 保留原有构造方式，未指定输入扩展配置。 */
    public AnalyzerConfig(Duration timeout, int maxTraceDepth, int methodCacheSize, boolean cleanupClassOutput) {
        this(timeout, maxTraceDepth, methodCacheSize, cleanupClassOutput, null);
    }

    static void validateTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
        timeout.toNanos(); // 提前校验纳秒换算，避免执行时才发现时间预算溢出。
    }

    public static AnalyzerConfig defaults() {
        return new AnalyzerConfig(Duration.ZERO, 256, 128, false, null, 32, 10000, JointBudgets.defaults());
    }

    public AnalyzerConfig withTimeout(Duration timeout) {
        return new AnalyzerConfig(timeout, maxTraceDepth, methodCacheSize, cleanupClassOutput, inputOptions, maxCallDepth, maxTraceNodes, jointBudgets, inferenceOptions);
    }

    /** 仅调整数据依赖深度；跨方法调用栈上限通过 withMaxCallDepth 单独设置。 */
    public AnalyzerConfig withMaxTraceDepth(int depth) {
        return new AnalyzerConfig(timeout, depth, methodCacheSize, cleanupClassOutput, inputOptions, maxCallDepth, maxTraceNodes, jointBudgets, inferenceOptions);
    }

    public AnalyzerConfig withMaxCallDepth(int depth) {
        return new AnalyzerConfig(timeout, maxTraceDepth, methodCacheSize, cleanupClassOutput, inputOptions, depth, maxTraceNodes, jointBudgets, inferenceOptions);
    }

    public AnalyzerConfig withMaxTraceNodes(int nodes) {
        return new AnalyzerConfig(timeout, maxTraceDepth, methodCacheSize, cleanupClassOutput, inputOptions, maxCallDepth, nodes, jointBudgets, inferenceOptions);
    }

    public AnalyzerConfig withMethodCacheSize(int size) {
        return new AnalyzerConfig(timeout, maxTraceDepth, size, cleanupClassOutput, inputOptions, maxCallDepth, maxTraceNodes, jointBudgets, inferenceOptions);
    }

    /** 设置联合路径求解预算；null 恢复默认值，单项调整可通过 JointBudgets 的 with 方法完成。 */
    public AnalyzerConfig withJointBudgets(@Nullable JointBudgets budgets) {
        return new AnalyzerConfig(timeout, maxTraceDepth, methodCacheSize, cleanupClassOutput, inputOptions, maxCallDepth, maxTraceNodes,
                budgets == null ? JointBudgets.defaults() : budgets, inferenceOptions);
    }

    /** 设置推断阶段和外部样例策略；null 恢复默认联合推断。 */
    public AnalyzerConfig withInferenceOptions(@Nullable InferenceOptions options) {
        return new AnalyzerConfig(timeout, maxTraceDepth, methodCacheSize, cleanupClassOutput, inputOptions, maxCallDepth, maxTraceNodes,
                jointBudgets, options == null ? InferenceOptions.defaults() : options);
    }

    public AnalyzerConfig withCleanupClassOutput(boolean cleanup) {
        return new AnalyzerConfig(timeout, maxTraceDepth, methodCacheSize, cleanup, inputOptions, maxCallDepth, maxTraceNodes, jointBudgets, inferenceOptions);
    }

    /** 为本实例设置输入配置；null 恢复使用解析器自身配置，不改变单次输出路径的管理规则。 */
    public AnalyzerConfig withInputOptions(@Nullable AnalysisInputOptions options) {
        return new AnalyzerConfig(timeout, maxTraceDepth, methodCacheSize, cleanupClassOutput, options, maxCallDepth, maxTraceNodes, jointBudgets, inferenceOptions);
    }
}
