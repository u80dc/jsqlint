package org.u80dc.jsqlint.core;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 单次调用的不可变选项。timeout 和 cleanupClassOutput 为 null 时继承 AnalyzerConfig，
 * 显式的 Duration.ZERO 表示不限时，false 表示保留编译产物。
 * 取消信号和监听器仅用于本次调用；取消和超时均在工作边界协作检查。
 * classOutputId 仅用于 Path 入口的源码编译，表示系统临时目录下 .jsqlint 中的单个目录名。
 * 为空时，Analyzer 为每次调用生成随机标识；复用指定标识时会先清空原输出目录。
 * @author u80dc
 */
public record AnalysisOptions(@Nullable Duration timeout, BooleanSupplier cancelled, AnalysisListener listener,
                              @Nullable String classOutputId, @Nullable Boolean cleanupClassOutput) {
    public AnalysisOptions {
        if (timeout != null) AnalyzerConfig.validateTimeout(timeout);
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(listener, "listener");
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

    /** 覆盖本次超时时间；传入 null 恢复继承实例默认值，零表示本次不限时。 */
    public AnalysisOptions withTimeout(@Nullable Duration timeout) {
        return new AnalysisOptions(timeout, cancelled, listener, classOutputId, cleanupClassOutput);
    }

    public AnalysisOptions withCancelled(BooleanSupplier cancelled) {
        return new AnalysisOptions(timeout, cancelled, listener, classOutputId, cleanupClassOutput);
    }

    public AnalysisOptions withListener(AnalysisListener listener) {
        return new AnalysisOptions(timeout, cancelled, listener, classOutputId, cleanupClassOutput);
    }

    /** 指定本次编译输出标识；传入 null 恢复每次调用生成随机标识的行为。 */
    public AnalysisOptions withClassOutputId(@Nullable String id) {
        return new AnalysisOptions(timeout, cancelled, listener, id, cleanupClassOutput);
    }

    /** 覆盖本次结束清理策略；传入 null 恢复继承实例默认值，失败、取消或超时也按此策略清理。 */
    public AnalysisOptions withCleanupClassOutput(@Nullable Boolean cleanup) {
        return new AnalysisOptions(timeout, cancelled, listener, classOutputId, cleanup);
    }

    /** 未指定覆盖项；实际的超时和清理策略在调用时从 AnalyzerConfig 获取。 */
    public static AnalysisOptions defaults() {
        return new AnalysisOptions(null, () -> false, AnalysisListener.NONE, null, null);
    }

    /** 合并为本次调用独有的配置快照，保持 Analyzer 上的默认配置不变。 */
    AnalyzerConfig resolveAgainst(AnalyzerConfig config) {
        return new AnalyzerConfig(timeout == null ? config.timeout() : timeout,
                config.maxTraceDepth(), config.methodCacheSize(),
                cleanupClassOutput == null ? config.cleanupClassOutput() : cleanupClassOutput, config.inputOptions());
    }
}
