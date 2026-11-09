package org.u80dc.jsqlint.core;

import java.util.Objects;
import java.util.List;

/**
 * 分析终态、raw SQL 与已保存的 SQL/参数溯源结果；执行中的阶段通过监听器报告。
 *
 * @author u80dc
 */
public record AnalysisResult(Status status, List<SqlResult> sqlResults, List<AnalysisDiagnostic> diagnostics) {
    public AnalysisResult {
        Objects.requireNonNull(status, "status");
        sqlResults = List.copyOf(sqlResults);
        diagnostics = List.copyOf(diagnostics);
    }

    public AnalysisResult(Status status) { this(status, List.of(), List.of()); }
    /** 兼容已有的字节码分析结果构造方式。 */
    public AnalysisResult(Status status, List<SqlExtraction> extractions) { this(status, List.copyOf(extractions), List.of()); }

    /** 字节码传播分析结果的兼容视图；所有模块结果通过 sqlResults() 读取。 */
    public List<SqlExtraction> extractions() {
        return sqlResults.stream().filter(SqlExtraction.class::isInstance).map(SqlExtraction.class::cast).toList();
    }

    /**
     * 直接获取本次分析已产生的全部 raw SQL 候选，包括不完整候选。
     * 按提取结果、语句、分支的顺序展开；相同文本的不同调用点/传播路径不去重。
     * 每个候选通过 parameters() 保存拼接参数推断，通过 bindings() 保存所属语句的绑定参数快照。
     * 参数的推断值和传播链随候选保留；列表不可变，输入关闭后仍可读取。
     * 包括资源中的静态 SQL；未展开的动态模板通过 sqlResults().templates() 访问，不伪造文本。
     * 从统一结果派生，避免监听器与返回结果使用不同数据源。
     */
    public List<RawSql.Variant> rawSqls() {
        return sqlResults.stream().flatMap(result -> result.rawSqls().stream()).toList();
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
