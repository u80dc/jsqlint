package org.u80dc.jsqlint.core;

import java.util.Objects;
import java.util.List;

/**
 * 分析终态、raw SQL 与已保存的 SQL/参数溯源结果；执行中的阶段通过监听器报告。
 *
 * @author u80dc
 */
public record AnalysisResult(Status status, List<SqlExtraction> extractions) {
    public AnalysisResult {
        Objects.requireNonNull(status, "status");
        extractions = List.copyOf(extractions);
    }

    public AnalysisResult(Status status) { this(status, List.of()); }

    /**
     * 直接获取本次分析已产生的全部 raw SQL 候选，包括不完整候选。
     * 按提取结果、语句、分支的顺序展开；相同文本的不同调用点/传播路径不去重。
     * 每个候选通过 parameters() 保存拼接参数推断，通过 bindings() 保存所属语句的绑定参数快照。
     * 参数的推断值和传播链随候选保留；列表不可变，输入关闭后仍可读取。
     * 从不可变的 extractions 派生，避免两份结果存储不同步；调用点和诊断仍可从 extractions 读取。
     */
    public List<RawSql.Variant> rawSqls() {
        return extractions.stream().flatMap(extraction -> extraction.statements().stream())
                .flatMap(statement -> statement.rawSql().variants().stream()).toList();
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
