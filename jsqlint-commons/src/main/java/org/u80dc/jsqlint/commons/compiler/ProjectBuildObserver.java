package org.u80dc.jsqlint.commons.compiler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;

/** 构建任务开始前公布实际输入和输出，仅由缓存构建期使用，不持有分析视图。 */
@FunctionalInterface
interface ProjectBuildObserver {
    void observe(Collection<Path> inputs, Collection<Path> outputs) throws IOException;
}
