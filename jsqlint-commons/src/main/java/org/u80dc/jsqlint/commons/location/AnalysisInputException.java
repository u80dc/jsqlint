package org.u80dc.jsqlint.commons.location;

/**
 * 分析输入准备失败，包括输入扫描、源码或项目编译以及产物收集失败。
 *
 * @author u80dc
 */
public final class AnalysisInputException extends IllegalStateException {

    public AnalysisInputException(String message) {
        super(message);
    }

    public AnalysisInputException(String message, Throwable cause) {
        super(message, cause);
    }

}
