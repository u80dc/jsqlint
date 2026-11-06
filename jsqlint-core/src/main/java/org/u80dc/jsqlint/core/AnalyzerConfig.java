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
 * 三项预算相互独立，增加表达式容量不会放宽递归调用限制。
 *
 * @author u80dc
 */
public record AnalyzerConfig(Duration timeout, int maxTraceDepth, int methodCacheSize,
                             boolean cleanupClassOutput, @Nullable AnalysisInputOptions inputOptions,
                             int maxCallDepth, int maxTraceNodes) {
    public AnalyzerConfig {
        validateTimeout(timeout);
        if (maxTraceDepth < 1 || methodCacheSize < 1 || maxCallDepth < 1 || maxTraceNodes < 1) {
            throw new IllegalArgumentException("Call depth, trace depth, trace nodes and cache size must be positive");
        }
    }

    /** 兼容旧构造器：显式传入的原共享深度仍同时初始化调用和数据依赖预算。 */
    public AnalyzerConfig(Duration timeout, int maxTraceDepth, int methodCacheSize,
                          boolean cleanupClassOutput, @Nullable AnalysisInputOptions inputOptions) {
        this(timeout, maxTraceDepth, methodCacheSize, cleanupClassOutput, inputOptions, maxTraceDepth, 10000);
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
        return new AnalyzerConfig(Duration.ZERO, 256, 128, false, null, 32, 10000);
    }

    public AnalyzerConfig withTimeout(Duration timeout) {
        return new AnalyzerConfig(timeout, maxTraceDepth, methodCacheSize, cleanupClassOutput, inputOptions, maxCallDepth, maxTraceNodes);
    }

    /** 仅调整数据依赖深度；跨方法调用栈上限通过 withMaxCallDepth 单独设置。 */
    public AnalyzerConfig withMaxTraceDepth(int depth) {
        return new AnalyzerConfig(timeout, depth, methodCacheSize, cleanupClassOutput, inputOptions, maxCallDepth, maxTraceNodes);
    }

    public AnalyzerConfig withMaxCallDepth(int depth) {
        return new AnalyzerConfig(timeout, maxTraceDepth, methodCacheSize, cleanupClassOutput, inputOptions, depth, maxTraceNodes);
    }

    public AnalyzerConfig withMaxTraceNodes(int nodes) {
        return new AnalyzerConfig(timeout, maxTraceDepth, methodCacheSize, cleanupClassOutput, inputOptions, maxCallDepth, nodes);
    }

    public AnalyzerConfig withMethodCacheSize(int size) {
        return new AnalyzerConfig(timeout, maxTraceDepth, size, cleanupClassOutput, inputOptions, maxCallDepth, maxTraceNodes);
    }

    public AnalyzerConfig withCleanupClassOutput(boolean cleanup) {
        return new AnalyzerConfig(timeout, maxTraceDepth, methodCacheSize, cleanup, inputOptions, maxCallDepth, maxTraceNodes);
    }

    /** 为本实例设置输入配置；null 恢复使用解析器自身配置，不改变单次输出路径的管理规则。 */
    public AnalyzerConfig withInputOptions(@Nullable AnalysisInputOptions options) {
        return new AnalyzerConfig(timeout, maxTraceDepth, methodCacheSize, cleanupClassOutput, options, maxCallDepth, maxTraceNodes);
    }
}
