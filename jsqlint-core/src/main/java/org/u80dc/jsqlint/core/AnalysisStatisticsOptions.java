package org.u80dc.jsqlint.core;

import java.util.*;

/** 请求级统计收集选项；默认不收集，可通过增加 Feature 扩展其它测量而无需增加结果字段。 */
public record AnalysisStatisticsOptions(Set<Feature> features) {
    private static final AnalysisStatisticsOptions NONE = new AnalysisStatisticsOptions(Set.of());
    private static final AnalysisStatisticsOptions TIMINGS = new AnalysisStatisticsOptions(Set.of(Feature.TIMINGS));

    public AnalysisStatisticsOptions {
        Objects.requireNonNull(features, "features");
        features = features.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(features));
    }

    public enum Feature { TIMINGS }
    public static AnalysisStatisticsOptions none() { return NONE; }
    public static AnalysisStatisticsOptions timings() { return TIMINGS; }
    public boolean enabled() { return !features.isEmpty(); }
}
