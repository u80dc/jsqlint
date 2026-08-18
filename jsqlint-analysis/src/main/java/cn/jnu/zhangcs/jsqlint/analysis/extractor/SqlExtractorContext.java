package cn.jnu.zhangcs.jsqlint.analysis.extractor;

import sootup.callgraph.EnhancedCallGraph;
import sootup.java.core.views.JavaView;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 共享分析上下文
 * <p>
 * 将各提取器共用的分析状态（源路径、SootUp 视图、增强调用图）封装为不可变记录，
 * 在提取流程中传递，避免重复构建与状态散落。
 * </p>
 *
 * @param graph 增强调用图
 * @param source 目标程序源文件或目录
 * @param view SootUp 视图
 * @author zhangcs
 */
public record SqlExtractorContext(
        Path source,
        JavaView view,
        EnhancedCallGraph graph) {

    /**
     * 紧凑构造器：对所有字段做非空校验。
     */
    public SqlExtractorContext {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(graph, "graph");
    }
}
