package org.u80dc.jsqlint.core;

import org.u80dc.jsqlint.core.spi.SourceLocation;
import java.util.*;

/** 可持久化的分析诊断；incomplete 明确表示分析遗漏，与外部参数尚无取值区分。 @author u80dc */
public record AnalysisDiagnostic(String code, AnalysisListener.Stage stage, Optional<SourceLocation> location,
                                  boolean incomplete, String message) {
    public AnalysisDiagnostic {
        Objects.requireNonNull(code); Objects.requireNonNull(stage); Objects.requireNonNull(location); Objects.requireNonNull(message);
    }
}
