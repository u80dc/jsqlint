package org.u80dc.jsqlint.core;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 通过 {@link Analyzer#bind(Path)} 或 {@link Analyzer#bind(Path, Path)} 创建的不可变路径绑定入口。
 * 保存原 Analyzer 及规范化的绝对路径，每次调用委托其现有分析流程。
 *
 * <p>绑定的是路径，每次分析读取当时的程序内容；绑定时不准备输入。
 * 配置、模块和解析器复用原 Analyzer，执行选项、会话、缓存及资源清理属于本次分析。
 * 本对象不持有活动输入，也无需关闭；并发使用仍遵循原 Analyzer 的扩展与输出目录约定。</p>
 *
 * @author u80dc
 */
public final class BoundAnalyzer {
    private final Analyzer analyzer;
    private final Path source;
    private final @Nullable Path dependencies;

    BoundAnalyzer(Analyzer analyzer, Path source, @Nullable Path dependencies) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.source = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        this.dependencies = dependencies == null ? null : dependencies.toAbsolutePath().normalize();
    }

    /** 使用原 Analyzer 的默认配置，按全程序范围分析绑定目标。 */
    public @NonNull AnalysisResult analyze() {
        return analyzer.analyze(source, dependencies, AnalysisOptions.defaults());
    }

    /** 按全程序范围分析绑定目标；本次选项未指定的覆盖项继承原 Analyzer 的配置。 */
    public @NonNull AnalysisResult analyze(AnalysisOptions options) {
        return analyzer.analyze(source, dependencies, options);
    }

    /**
     * 使用原 Analyzer 的默认配置，分析指定方法及其下游 SQL；支持完整 SootUp 签名和 JVM 方法引用。
     * 无方法体的 Mapper 入口、签名校验和诊断规则与原方法范围入口相同。
     *
     * @throws IllegalArgumentException 方法签名格式不完整或非法
     */
    public @NonNull AnalysisResult analyze(String methodSignature) {
        return analyzer.analyze(source, dependencies, AnalysisOptions.defaults(), methodSignature);
    }

    /**
     * 指定本次选项，分析指定方法及其下游 SQL；支持完整 SootUp 签名和 JVM 方法引用。
     *
     * @throws IllegalArgumentException 方法签名格式不完整或非法
     */
    public @NonNull AnalysisResult analyze(AnalysisOptions options, String methodSignature) {
        return analyzer.analyze(source, dependencies, options, methodSignature);
    }
}
