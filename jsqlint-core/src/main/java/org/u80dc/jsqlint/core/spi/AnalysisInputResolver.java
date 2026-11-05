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
}
