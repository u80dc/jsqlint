package org.u80dc.jsqlint.core;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 单次调用的不可变选项。timeout、cleanupClassOutput 和 inferenceOptions 为 null 时继承 AnalyzerConfig，
 * 显式的 Duration.ZERO 表示不限时，false 表示保留编译产物。
 * 取消信号和监听器仅用于本次调用；取消和超时均在工作边界协作检查。
 * classOutputId 仅用于 Path 入口的源码编译，表示系统临时目录下 .jsqlint 中的单个目录名。
 * 为空时，Analyzer 为每次调用生成随机标识；复用指定标识时会先清空原输出目录。
 * statisticsOptions 为本次调用选择统计收集内容，默认不启用，不继承全局可变状态。
 * @author u80dc
 */
public record AnalysisOptions(@Nullable Duration timeout, BooleanSupplier cancelled, AnalysisListener listener,
                              @Nullable String classOutputId, @Nullable Boolean cleanupClassOutput,
                              @Nullable InferenceOptions inferenceOptions,
                              AnalysisStatisticsOptions statisticsOptions) {
    public AnalysisOptions {
        if (timeout != null) AnalyzerConfig.validateTimeout(timeout);
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(statisticsOptions, "statisticsOptions");
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

    /** 兼容原完整构造器，统计收集保持关闭。 */
    public AnalysisOptions(@Nullable Duration timeout, BooleanSupplier cancelled, AnalysisListener listener,
                           @Nullable String classOutputId, @Nullable Boolean cleanupClassOutput,
                           @Nullable InferenceOptions inferenceOptions) {
        this(timeout, cancelled, listener, classOutputId, cleanupClassOutput, inferenceOptions, AnalysisStatisticsOptions.none());
    }

    /** 兼容旧构造器，未指定推断覆盖项。 */
    public AnalysisOptions(@Nullable Duration timeout, BooleanSupplier cancelled, AnalysisListener listener,
                           @Nullable String classOutputId, @Nullable Boolean cleanupClassOutput) {
        this(timeout, cancelled, listener, classOutputId, cleanupClassOutput, null);
    }

    /** 覆盖本次超时时间；传入 null 恢复继承实例默认值，零表示本次不限时。 */
    public AnalysisOptions withTimeout(@Nullable Duration timeout) {
        return new AnalysisOptions(timeout, cancelled, listener, classOutputId, cleanupClassOutput, inferenceOptions, statisticsOptions);
    }

    public AnalysisOptions withCancelled(BooleanSupplier cancelled) {
        return new AnalysisOptions(timeout, cancelled, listener, classOutputId, cleanupClassOutput, inferenceOptions, statisticsOptions);
    }

    public AnalysisOptions withListener(AnalysisListener listener) {
        return new AnalysisOptions(timeout, cancelled, listener, classOutputId, cleanupClassOutput, inferenceOptions, statisticsOptions);
    }

    /** 指定本次编译输出标识；传入 null 恢复每次调用生成随机标识的行为。 */
    public AnalysisOptions withClassOutputId(@Nullable String id) {
        return new AnalysisOptions(timeout, cancelled, listener, id, cleanupClassOutput, inferenceOptions, statisticsOptions);
    }

    /** 覆盖本次结束清理策略；传入 null 恢复继承实例默认值，失败、取消或超时也按此策略清理。 */
    public AnalysisOptions withCleanupClassOutput(@Nullable Boolean cleanup) {
        return new AnalysisOptions(timeout, cancelled, listener, classOutputId, cleanup, inferenceOptions, statisticsOptions);
    }

    /** 覆盖本次推断策略；null 恢复继承 AnalyzerConfig。 */
    public AnalysisOptions withInferenceOptions(@Nullable InferenceOptions options) {
        return new AnalysisOptions(timeout, cancelled, listener, classOutputId, cleanupClassOutput, options, statisticsOptions);
    }

    /** 启用默认时间统计或关闭收集；其它分析选项不变。 */
    public AnalysisOptions withStatistics(boolean enabled) {
        return withStatisticsOptions(enabled ? AnalysisStatisticsOptions.timings() : AnalysisStatisticsOptions.none());
    }

    /** 设置本次统计内容；null 关闭收集。 */
    public AnalysisOptions withStatisticsOptions(@Nullable AnalysisStatisticsOptions options) {
        return new AnalysisOptions(timeout, cancelled, listener, classOutputId, cleanupClassOutput, inferenceOptions,
                options == null ? AnalysisStatisticsOptions.none() : options);
    }

    /** 未指定覆盖项；实际的超时和清理策略在调用时从 AnalyzerConfig 获取。 */
    public static AnalysisOptions defaults() {
        return new AnalysisOptions(null, () -> false, AnalysisListener.NONE, null, null);
    }

    /** 合并为本次调用独有的配置快照，保持 Analyzer 上的默认配置不变。 */
    AnalyzerConfig resolveAgainst(AnalyzerConfig config) {
        return config.withTimeout(timeout == null ? config.timeout() : timeout)
                .withCleanupClassOutput(cleanupClassOutput == null ? config.cleanupClassOutput() : cleanupClassOutput)
                .withInferenceOptions(inferenceOptions == null ? config.inferenceOptions() : inferenceOptions);
    }
}
