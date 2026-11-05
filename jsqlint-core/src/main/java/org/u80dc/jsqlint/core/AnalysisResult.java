package org.u80dc.jsqlint.core;

import java.util.Objects;

/**
 * 分析终态；执行中的阶段通过监听器报告。
 *
 * @author u80dc
 */
public record AnalysisResult(Status status) {
    public AnalysisResult {
        Objects.requireNonNull(status, "status");
    }

    public enum Status {
        /** 已完成约定范围的分析；没有发现 SQL 也属于成功。 */
        SUCCESS,
        /** 部分工作因能力限制、解析错误、追踪预算或超时而未完成。 */
        PARTIAL,
        /** 输入准备、初始化或结果交付失败。 */
        FAILED,
        /** 调用方主动取消，或执行线程收到中断。 */
        CANCELLED
    }
}
