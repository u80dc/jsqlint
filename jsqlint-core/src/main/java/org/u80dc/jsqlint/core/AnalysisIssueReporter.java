package org.u80dc.jsqlint.core;

import java.util.Set;

/** 阶段生产方明确说明遗漏影响的结果维度，诊断文案不参与完整性判断。 */
@FunctionalInterface
interface AnalysisIssueReporter {
    void incomplete(String code, String message, Set<SqlAssessment.Dimension> dimensions);
    default void incomplete(String code, String message) {
        incomplete(code, message, Set.of(SqlAssessment.Dimension.SOURCES, SqlAssessment.Dimension.TEXT));
    }
}
