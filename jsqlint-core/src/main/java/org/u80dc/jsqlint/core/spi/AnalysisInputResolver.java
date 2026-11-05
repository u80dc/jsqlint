package org.u80dc.jsqlint.core.spi;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;

/**
 * 可选的输入准备扩展。supports 只能检查输入，不应编译或修改文件。
 * 高 priority 优先，同优先级同时匹配视为配置歧义；选中的解析器失败后不回退到旧字节码。
 * @author u80dc
 */
public interface AnalysisInputResolver {
    /** 数值越大越优先；只在支持当前输入的解析器之间比较。 */
    default int priority() { return 0; }
    /** 只做输入识别；统一入口传入已经归一化的绝对路径。 */
    boolean supports(Path source) throws Exception;
    /**
     * 当前输入是否需要编译。编译扩展返回 true，由 Analyzer 管理输出目录的预清理及结束清理。
     * 默认不编译，避免已有字节码或其他只读输入触发输出目录删除；本方法不应修改文件。
     */
    default boolean requiresCompilation(Path source) throws Exception { return false; }
    /**
     * 准备代码及资源输入，并通过返回值转交所有权。
     * 耗时步骤之间应调用 checkpoint；其抛出的停止信号应向上传播，不应被吞掉。
     * 返回前失败时，由实现清理已经创建的输入。
     */
    PreparedInput resolve(Path source, Runnable checkpoint) throws Exception;

    /**
     * 传递调用方为本次分析指定的编译输出目录，null 表示未指定。
     * 编译解析器应重写此方法；默认实现兼容已有解析器，不编译的字节码入口无需处理输出目录。
     */
    default PreparedInput resolve(Path source, @Nullable Path classOutputDirectory,
                                  Runnable checkpoint) throws Exception {
        return resolve(source, checkpoint);
    }

    /**
     * 接收 Analyzer 实例的输入扩展配置，编译解析器应识别其支持的具体选项类型。
     * 未指定配置时兼容原有入口；不编译的字节码入口忽略编译配置。
     * 编译解析器未实现配置传递时明确报错，避免静默使用错误的仓库或构建参数。
     */
    default PreparedInput resolve(Path source, @Nullable Path classOutputDirectory,
                                  @Nullable AnalysisInputOptions options, Runnable checkpoint) throws Exception {
        if (options != null && requiresCompilation(source)) {
            throw new IllegalArgumentException("Input resolver does not support options: " + options.getClass().getName());
        }
        return resolve(source, classOutputDirectory, checkpoint);
    }
}
