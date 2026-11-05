package org.u80dc.jsqlint.core;

import java.time.Duration;
import java.util.Objects;

/**
 * Analyzer 实例级的不可变配置。只保存默认值和容量上限，不保存缓存、计时器或其他执行状态。
 * timeout 为零表示不限时；单次调用可覆盖超时和结束清理选项。
 *
 * @author u80dc
 */
public record AnalyzerConfig(Duration timeout, int maxTraceDepth, int methodCacheSize,
                             boolean cleanupClassOutput) {
    public AnalyzerConfig {
        validateTimeout(timeout);
        if (maxTraceDepth < 1 || methodCacheSize < 1) {
            throw new IllegalArgumentException("Trace depth and cache size must be positive");
        }
    }

    static void validateTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
        timeout.toNanos(); // 提前校验纳秒换算，避免执行时才发现时间预算溢出。
    }

    public static AnalyzerConfig defaults() {
        return new AnalyzerConfig(Duration.ZERO, 32, 128, false);
    }

    public AnalyzerConfig withTimeout(Duration timeout) {
        return new AnalyzerConfig(timeout, maxTraceDepth, methodCacheSize, cleanupClassOutput);
    }

    public AnalyzerConfig withMaxTraceDepth(int depth) {
        return new AnalyzerConfig(timeout, depth, methodCacheSize, cleanupClassOutput);
    }

    public AnalyzerConfig withMethodCacheSize(int size) {
        return new AnalyzerConfig(timeout, maxTraceDepth, size, cleanupClassOutput);
    }

    public AnalyzerConfig withCleanupClassOutput(boolean cleanup) {
        return new AnalyzerConfig(timeout, maxTraceDepth, methodCacheSize, cleanup);
    }
}
