package org.u80dc.jsqlint.core;

import org.u80dc.jsqlint.core.resource.AnalysisResourceSource;
import sootup.core.inputlocation.AnalysisInputLocation;

import java.util.List;
import java.util.Objects;

/**
 * 调用方持有输入的所有权；分析器不会关闭这些 input locations。
 * 可只传资源。JDK 运行时和依赖由调用方显式提供，保留输入顺序与 SourceType。
 * @author u80dc
 */
public record AnalysisRequest(List<? extends AnalysisInputLocation> locations,
                              List<? extends AnalysisResourceSource> resources,
                              AnalysisOptions options) {
    public AnalysisRequest {
        // 只复制集合，防止执行期间成员被增删；不复制或接管其中的输入对象。
        locations = List.copyOf(locations);
        resources = List.copyOf(resources);
        Objects.requireNonNull(options, "options");
        if (locations.isEmpty() && resources.isEmpty())
            throw new IllegalArgumentException("At least one code or resource input is required");
    }

    public AnalysisRequest(List<? extends AnalysisInputLocation> locations,
                           List<? extends AnalysisResourceSource> resources) {
        this(locations, resources, AnalysisOptions.defaults());
    }
}
