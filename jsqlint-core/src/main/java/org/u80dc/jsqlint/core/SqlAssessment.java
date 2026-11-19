package org.u80dc.jsqlint.core;

import java.util.*;

/**
 * 分别描述来源、SQL 文本结构、参数值和路径覆盖；未知参数不等于来源恢复失败。
 * textComplete 包括尚未替换的槽位/动态模板；完整样例文本另见 SqlSample.sql()。
 * pathCoverageComplete 为空表示未进行路径求解，false 表示已求解但存在遗漏。
 * @author u80dc
 */
public record SqlAssessment(boolean sourcesComplete, boolean textComplete, boolean valuesComplete,
                            Optional<Boolean> pathCoverageComplete) {
    public SqlAssessment { Objects.requireNonNull(pathCoverageComplete); }

    /** 诊断生产端声明影响的事实维度，消费端无需从诊断文案推测。 */
    public enum Dimension { SOURCES, TEXT, VALUES, PATH_COVERAGE }

    static Optional<Boolean> pathCoverage(List<RawSql.Variant> variants) {
        if (variants.isEmpty() || variants.stream().anyMatch(variant -> variant.jointInference().isEmpty())) return Optional.empty();
        return Optional.of(variants.stream().allMatch(variant -> variant.jointInference().orElseThrow().coverageComplete()));
    }
}
