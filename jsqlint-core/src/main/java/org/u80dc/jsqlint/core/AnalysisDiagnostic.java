package org.u80dc.jsqlint.core;

import org.u80dc.jsqlint.core.spi.SourceLocation;
import java.util.*;

/** 可持久化的分析诊断；incomplete 明确表示分析遗漏，与外部参数尚无取值区分。 @author u80dc */
public record AnalysisDiagnostic(String code, AnalysisListener.Stage stage, Optional<SourceLocation> location,
                                  boolean incomplete, String message, Set<SqlAssessment.Dimension> affectedDimensions) {
    public AnalysisDiagnostic {
        Objects.requireNonNull(code); Objects.requireNonNull(stage); Objects.requireNonNull(location); Objects.requireNonNull(message);
        affectedDimensions = Set.copyOf(affectedDimensions);
    }

    /** 旧扩展未描述影响阶段时，分析遗漏保守地影响来源和文本恢复。 */
    public AnalysisDiagnostic(String code, AnalysisListener.Stage stage, Optional<SourceLocation> location,
                              boolean incomplete, String message) {
        this(code, stage, location, incomplete, message,
                incomplete ? Set.of(SqlAssessment.Dimension.SOURCES, SqlAssessment.Dimension.TEXT) : Set.of());
    }

    public boolean affects(SqlAssessment.Dimension dimension) { return affectedDimensions.contains(dimension); }
}
