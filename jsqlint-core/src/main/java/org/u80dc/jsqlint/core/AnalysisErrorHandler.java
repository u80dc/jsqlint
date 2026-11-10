package org.u80dc.jsqlint.core;

/** 局部错误的上报接口；取消、中断和监听器异常由会话统一处理。 @author u80dc */
@FunctionalInterface
interface AnalysisErrorHandler {
    void recover(String code, String message, Exception error, boolean incomplete);

    default void recover(String code, String message, Exception error) {
        recover(code, message, error, true);
    }
}
