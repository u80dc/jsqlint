package cn.jnu.zhangcs.jsqlint.analysis;

import cn.jnu.zhangcs.jsqlint.analysis.node.SqlNode;
import cn.jnu.zhangcs.jsqlint.analysis.node.SqlNodeRenderer;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.infer.ParameterValueInferenceResult;
import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * SQL 分析完整结果
 *
 * <p>
 *     包含从代码中提取的 SQL 定义、参数推断、SQL 渲染等完整信息
 * </p>
 *
 * @author zhangcs
 */
@Getter
@Builder
public class AnalyzeResult {

    /**
     * 分析状态
     */
    private final boolean success;

    /**
     * SQL 语句列表
     */
    private final List<SqlStatementResult> statements;

    /**
     * 分析过程中的错误和警告信息
     */
    private final List<String> diagnostics;

    /**
     * 分析统计信息
     */
    private final AnalysisStatistics statistics;

    private AnalyzeResult(
            boolean success,
            List<SqlStatementResult> statements,
            List<String> diagnostics,
            AnalysisStatistics statistics) {
        this.success = success;
        this.statements = Objects.requireNonNull(statements, "statements");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.statistics = Objects.requireNonNull(statistics, "statistics");
    }

    /**
     * 创建失败结果
     */
    public static AnalyzeResult failed(String message) {
        return AnalyzeResult.builder()
                .success(false)
                .statements(Collections.emptyList())
                .diagnostics(List.of(message))
                .statistics(AnalysisStatistics.empty())
                .build();
    }

    /**
     * 创建成功结果
     */
    public static AnalyzeResult success(List<SqlStatementResult> statements) {
        return success(statements, Collections.emptyList());
    }

    /**
     * 创建成功结果（带诊断信息）
     */
    public static AnalyzeResult success(List<SqlStatementResult> statements, List<String> diagnostics) {
        return AnalyzeResult.builder()
                .success(true)
                .statements(statements == null ? Collections.emptyList() : List.copyOf(statements))
                .diagnostics(diagnostics == null ? Collections.emptyList() : List.copyOf(diagnostics))
                .statistics(AnalysisStatistics.from(statements))
                .build();
    }

    /**
     * 单条 SQL 语句的完整分析结果
     */
    @Getter
    @Builder
    public static class SqlStatementResult {

        /**
         * Statement ID (如 com.example.UserMapper.selectById)
         */
        private final String statementId;

        /**
         * SQL 类型 (SELECT, INSERT, UPDATE, DELETE)
         */
        private final StatementType statementType;

        /**
         * SQL 来源类型 (XML, ANNOTATION, JDBC)
         */
        private final SqlSourceType sourceType;

        /**
         * SQL 定义位置
         */
        private final SourceLocation location;

        /**
         * 原始 SQL 文本（MyBatis XML 中的原始文本，可能包含动态标签）
         */
        private final String rawSql;

        /**
         * SqlNode 树结构（解析后的动态 SQL 结构）
         */
        private final SqlNode sqlNode;

        /**
         * 是否为动态 SQL
         */
        private final boolean dynamic;

        /**
         * 参数推断结果列表
         */
        private final List<ParameterValueInferenceResult> parameterInferences;

        /**
         * 渲染后的 SQL 列表（根据推断参数生成的可能 SQL）
         */
        private final List<SqlNodeRenderer.RenderedSql> renderedSqls;

        /**
         * 分析诊断信息（警告、错误等）
         */
        private final List<String> diagnostics;

        /**
         * 元数据（扩展信息）
         */
        private final Metadata metadata;

        /**
         * 判断是否有推断出的参数值
         */
        public boolean hasInferredParameters() {
            return parameterInferences != null &&
                    !parameterInferences.isEmpty() &&
                    parameterInferences.stream().anyMatch(ParameterValueInferenceResult::hasValues);
        }

        /**
         * 判断是否有渲染后的 SQL
         */
        public boolean hasRenderedSqls() {
            return renderedSqls != null && !renderedSqls.isEmpty();
        }

        /**
         * 获取参数数量
         */
        public int getParameterCount() {
            return parameterInferences == null ? 0 : parameterInferences.size();
        }

        /**
         * 获取渲染后 SQL 数量
         */
        public int getRenderedSqlCount() {
            return renderedSqls == null ? 0 : renderedSqls.size();
        }
    }

    /**
     * SQL 语句元数据
     */
    @Getter
    @Builder
    public static class Metadata {

        /**
         * 调用链深度
         */
        private final int callDepth;

        /**
         * 调用者列表
         */
        private final List<String> callers;

        /**
         * SQL 复杂度（如动态节点数量）
         */
        private final int complexity;

        /**
         * 涉及的表名
         */
        private final List<String> tableNames;

        /**
         * 自定义标签
         */
        private final List<String> tags;

        /**
         * 扩展属性
         */
        private final java.util.Map<String, Object> extra;

        public static Metadata empty() {
            return Metadata.builder()
                    .callDepth(0)
                    .callers(Collections.emptyList())
                    .complexity(0)
                    .tableNames(Collections.emptyList())
                    .tags(Collections.emptyList())
                    .extra(Collections.emptyMap())
                    .build();
        }
    }

    /**
     * 分析统计信息
     */
    @Getter
    @Builder
    public static class AnalysisStatistics {

        /**
         * 总 SQL 数量
         */
        private final int totalStatements;

        /**
         * 动态 SQL 数量
         */
        private final int dynamicStatements;

        /**
         * 静态 SQL 数量
         */
        private final int staticStatements;

        /**
         * SELECT 语句数量
         */
        private final int selectCount;

        /**
         * INSERT 语句数量
         */
        private final int insertCount;

        /**
         * UPDATE 语句数量
         */
        private final int updateCount;

        /**
         * DELETE 语句数量
         */
        private final int deleteCount;

        /**
         * 成功推断参数的 SQL 数量
         */
        private final int inferredParameterCount;

        /**
         * 成功渲染的 SQL 数量
         */
        private final int renderedSqlCount;

        /**
         * 有诊断信息的 SQL 数量
         */
        private final int diagnosticCount;

        public static AnalysisStatistics empty() {
            return AnalysisStatistics.builder()
                    .totalStatements(0)
                    .dynamicStatements(0)
                    .staticStatements(0)
                    .selectCount(0)
                    .insertCount(0)
                    .updateCount(0)
                    .deleteCount(0)
                    .inferredParameterCount(0)
                    .renderedSqlCount(0)
                    .diagnosticCount(0)
                    .build();
        }

        public static AnalysisStatistics from(List<SqlStatementResult> statements) {
            if (statements == null || statements.isEmpty()) {
                return empty();
            }

            int dynamic = 0;
            int select = 0, insert = 0, update = 0, delete = 0;
            int inferred = 0;
            int rendered = 0;
            int diagnostic = 0;

            for (SqlStatementResult stmt : statements) {
                if (stmt.isDynamic()) {
                    dynamic++;
                }

                if (stmt.getStatementType() != null) {
                    switch (stmt.getStatementType()) {
                        case SELECT -> select++;
                        case INSERT -> insert++;
                        case UPDATE -> update++;
                        case DELETE -> delete++;
                    }
                }

                if (stmt.hasInferredParameters()) {
                    inferred++;
                }

                if (stmt.hasRenderedSqls()) {
                    rendered += stmt.getRenderedSqlCount();
                }

                if (stmt.getDiagnostics() != null && !stmt.getDiagnostics().isEmpty()) {
                    diagnostic++;
                }
            }

            return AnalysisStatistics.builder()
                    .totalStatements(statements.size())
                    .dynamicStatements(dynamic)
                    .staticStatements(statements.size() - dynamic)
                    .selectCount(select)
                    .insertCount(insert)
                    .updateCount(update)
                    .deleteCount(delete)
                    .inferredParameterCount(inferred)
                    .renderedSqlCount(rendered)
                    .diagnosticCount(diagnostic)
                    .build();
        }
    }

}
