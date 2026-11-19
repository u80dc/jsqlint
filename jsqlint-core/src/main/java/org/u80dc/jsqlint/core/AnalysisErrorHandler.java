package org.u80dc.jsqlint.core;

import java.util.Set;

/** 局部错误的上报接口；取消、中断和监听器异常由会话统一处理。 @author u80dc */
@FunctionalInterface
interface AnalysisErrorHandler {
    void recover(String code, String message, Exception error, boolean incomplete, Set<SqlAssessment.Dimension> dimensions);

    default void recover(String code, String message, Exception error, boolean incomplete) {
        recover(code, message, error, incomplete, Set.of(SqlAssessment.Dimension.SOURCES, SqlAssessment.Dimension.TEXT));
    }

    default void recover(String code, String message, Exception error) {
        recover(code, message, error, true);
    }
}
