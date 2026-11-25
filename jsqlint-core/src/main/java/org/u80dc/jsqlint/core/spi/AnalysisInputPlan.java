package org.u80dc.jsqlint.core.spi;

import org.jspecify.annotations.Nullable;
import java.nio.file.Path;

/**
 * 修改输出目录前确定的输入准备决定。已有产物或扩展管理的缓存不申请 Analyzer 的临时输出目录。
 * 计划只在本次准备期内使用；返回 PreparedInput 后由其管理分析输入，已有产物不转交删除所有权。
 */
public interface AnalysisInputPlan extends AutoCloseable {
    boolean requiresManagedClassOutputDirectory();
    PreparedInput resolve(@Nullable Path classOutputDirectory, AnalysisInputContext context) throws Exception;
    @Override default void close() throws Exception {}
}
