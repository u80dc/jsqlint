package org.u80dc.jsqlint.core;

import java.util.Objects;
import java.util.List;
import java.util.Optional;

/**
 * 分析终态、raw SQL、已保存的 SQL/参数溯源结果及可选统计快照；执行中的阶段通过监听器报告。
 * statistics 未启用时为空，读取已结束的统计不会重新采集或保留执行资源。
 *
 * @author u80dc
 */
public record AnalysisResult(Status status, List<SqlResult> sqlResults, List<AnalysisDiagnostic> diagnostics,
                             Optional<AnalysisStatistics> statistics) {
    public AnalysisResult {
        Objects.requireNonNull(status, "status");
        sqlResults = List.copyOf(sqlResults);
        diagnostics = List.copyOf(diagnostics);
        Objects.requireNonNull(statistics, "statistics");
    }

    /** 兼容未启用统计的原结果构造器。 */
    public AnalysisResult(Status status, List<SqlResult> sqlResults, List<AnalysisDiagnostic> diagnostics) {
        this(status, sqlResults, diagnostics, Optional.empty());
    }

    public AnalysisResult(Status status) { this(status, List.of(), List.of()); }

    /** 完整调用提取视图，包含 JDBC 与关联定义的 Mapper 调用，保留发现顺序和不同调用上下文。 */
    public List<SqlExtraction> calls() {
        return sqlResults.stream().filter(SqlExtraction.class::isInstance).map(SqlExtraction.class::cast).toList();
    }

    /** 资源、注解和计算定义的分析结果；不重复包含调用结果关联的定义，也不包含旧的裸模板起点。 */
    public List<SqlTemplateResult> definitions() {
        return sqlResults.stream().filter(SqlTemplateResult.class::isInstance).map(SqlTemplateResult.class::cast)
                .filter(result -> result.definition().isPresent()).toList();
    }

    /**
     * 直接获取本次分析已产生的全部 raw SQL 候选，包括不完整候选。
     * 按提取结果、语句、分支的顺序展开；相同文本的不同调用点/传播路径不去重。
     * 每个候选通过 parameters() 保存拼接参数推断，bindings() 保留 Java 绑定来源和独立推断；
     * bindingSnapshots() 按最终模型保存统一绑定快照，包括没有 Java 来源的定义。
     * 参数的推断值和传播链随候选保留；列表不可变，输入关闭后仍可读取。
     * 包括资源中的静态 SQL；未展开的动态模板通过 sqlResults().templates() 访问，不伪造文本。
     * 从统一结果派生，避免监听器与返回结果使用不同数据源。
     */
    public List<RawSql.Variant> rawSqls() {
        return sqlResults.stream().flatMap(result -> result.rawSqls().stream()).toList();
    }

    /** 统一获取代码和资源的样例；已证明无解的 raw 候选保留在 rawSqls() 中供审计。 */
    public List<SqlSample> samples() { return sqlResults.stream().flatMap(result -> result.samples().stream()).toList(); }

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
