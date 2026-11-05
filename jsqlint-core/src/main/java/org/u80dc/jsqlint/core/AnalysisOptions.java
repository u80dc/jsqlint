package org.u80dc.jsqlint.core;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 每次执行的配置。timeout 为零表示不限时；取消和超时均在工作边界协作检查。
 * 方法缓存有容量上限，追踪深度耗尽会报告 PARTIAL 并保留未知值。
 * classOutputId 仅用于 Path 入口的源码编译，表示系统临时目录下 .jsqlint 中的单个目录名。
 * 为空时，Analyzer 为每次调用生成随机标识。
 * @author u80dc
 */
public record AnalysisOptions(Duration timeout, int maxTraceDepth, int methodCacheSize,
                              BooleanSupplier cancelled, AnalysisListener listener,
                              @Nullable String classOutputId) {
    public AnalysisOptions {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(listener, "listener");
        if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
        timeout.toNanos(); // 在开始执行前校验纳秒换算，避免运行时才发现时间预算溢出。
        if (maxTraceDepth < 1 || methodCacheSize < 1)
            throw new IllegalArgumentException("Trace depth and cache size must be positive");
        if (classOutputId != null) {
            // 标识只能是单个目录名，不能通过绝对路径或父目录跳转改变输出根目录。
            if (classOutputId.isBlank() || classOutputId.equals(".") || classOutputId.equals("..")
                    || classOutputId.contains("/") || classOutputId.contains("\\")
                    || classOutputId.endsWith(".") || classOutputId.endsWith(" ")
                    || Path.of(classOutputId).isAbsolute() || Path.of(classOutputId).getNameCount() != 1) {
                throw new IllegalArgumentException("Class output ID must be a single directory name: " + classOutputId);
            }
        }
    }

    /** 保留原有执行选项构造方式，未指定编译输出标识。 */
    public AnalysisOptions(Duration timeout, int maxTraceDepth, int methodCacheSize,
                           BooleanSupplier cancelled, AnalysisListener listener) {
        this(timeout, maxTraceDepth, methodCacheSize, cancelled, listener, null);
    }

    /** 指定本次编译输出标识；传入 null 恢复每次调用生成随机标识的行为。 */
    public AnalysisOptions withClassOutputId(@Nullable String id) {
        return new AnalysisOptions(timeout, maxTraceDepth, methodCacheSize, cancelled, listener, id);
    }

    public static AnalysisOptions defaults() {
        return new AnalysisOptions(Duration.ZERO, 32, 128, () -> false, AnalysisListener.NONE);
    }
}
