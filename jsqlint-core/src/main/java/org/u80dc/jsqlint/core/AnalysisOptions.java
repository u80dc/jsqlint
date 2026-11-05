package org.u80dc.jsqlint.core;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 每次执行的配置。timeout 为零表示不限时；取消和超时均在工作边界协作检查。
 * 方法缓存有容量上限，追踪深度耗尽会报告 PARTIAL 并保留未知值。
 * @author u80dc
 */
public record AnalysisOptions(Duration timeout, int maxTraceDepth, int methodCacheSize,
                              BooleanSupplier cancelled, AnalysisListener listener) {
    public AnalysisOptions {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(listener, "listener");
        if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
        timeout.toNanos(); // 在开始执行前校验纳秒换算，避免运行时才发现时间预算溢出。
        if (maxTraceDepth < 1 || methodCacheSize < 1)
            throw new IllegalArgumentException("Trace depth and cache size must be positive");
    }

    public static AnalysisOptions defaults() {
        return new AnalysisOptions(Duration.ZERO, 32, 128, () -> false, AnalysisListener.NONE);
    }
}
