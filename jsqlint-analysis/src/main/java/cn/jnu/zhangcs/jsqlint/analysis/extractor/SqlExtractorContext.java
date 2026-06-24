package cn.jnu.zhangcs.jsqlint.analysis.extractor;

import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.ParameterTraceAnalyzer;
import sootup.callgraph.EnhancedCallGraph;
import sootup.java.core.views.JavaView;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Shared analysis state made available to independent extractors.
 */
public record SqlExtractorContext(
        Path source,
        JavaView view,
        EnhancedCallGraph graph,
        ParameterTraceAnalyzer parameterTraceAnalyzer) {

    public SqlExtractorContext {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(parameterTraceAnalyzer, "parameterTraceAnalyzer");
    }
}
