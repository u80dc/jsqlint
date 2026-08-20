package cn.jnu.zhangcs.jsqlint.analysis.pipeline;

import cn.jnu.zhangcs.jsqlint.analysis.AnalyzeResult;
import cn.jnu.zhangcs.jsqlint.analysis.extractor.SqlStatement;
import cn.jnu.zhangcs.jsqlint.analysis.extractor.SqlExtractionSupport;
import cn.jnu.zhangcs.jsqlint.analysis.node.SqlNodeRenderer;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.ParameterAnalysisService;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.infer.ParameterValueInferenceResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * SQL 提取完成后的最终结果组装器。
 *
 * <p>负责把提取阶段的 {@link SqlStatement} 加工为对外的
 * {@link AnalyzeResult.SqlStatementResult}：执行参数值推断、渲染动态 SQL、
 * 汇总诊断信息。</p>
 *
 * @author zhangcs
 */
public final class SqlStatementAssembler {

    private final ParameterAnalysisService parameterAnalysisService;

    /**
     * 创建不做参数推断的实例，仅渲染 SQL 与汇总诊断。
     */
    public SqlStatementAssembler() {
        this(null);
    }

    /**
     * 创建带参数分析服务的实例。
     *
     * @param parameterAnalysisService 参数分析服务，null 表示跳过参数推断
     */
    public SqlStatementAssembler(ParameterAnalysisService parameterAnalysisService) {
        this.parameterAnalysisService = parameterAnalysisService;
    }

    /**
     * 将提取出的 SQL 语句组装为最终分析结果。
     *
     * @param extracted 提取阶段的 SQL 语句
     * @return 组装完成的分析结果
     */
    public AnalyzeResult.SqlStatementResult assemble(SqlStatement extracted) {
        Objects.requireNonNull(extracted, "extracted");

        List<ParameterValueInferenceResult> parameterInferences = inferParameters(extracted);
        List<SqlNodeRenderer.RenderedSql> renderedSqls =
                SqlNodeRenderer.render(extracted.sqlNode(), parameterInferences);

        List<String> diagnostics = new ArrayList<>(extracted.diagnostics());
        diagnostics.addAll(SqlExtractionSupport.collectParameterDiagnostics(parameterInferences, renderedSqls));

        return AnalyzeResult.SqlStatementResult.builder()
                .statementId(extracted.id())
                .statementType(extracted.type())
                .sourceType(extracted.sourceType())
                .location(extracted.location())
                .rawSql(extracted.rawSql())
                .sqlNode(extracted.sqlNode())
                .dynamic(extracted.dynamic())
                .parameterInferences(parameterInferences)
                .renderedSqls(renderedSqls)
                .diagnostics(cleanDiagnostics(diagnostics))
                .metadata(extracted.metadata())
                .build();
    }

    /**
     * 对语句的外部参数执行批量值推断；无参数或未配置分析服务时返回空列表。
     */
    private List<ParameterValueInferenceResult> inferParameters(SqlStatement extracted) {
        if (extracted.parameters().isEmpty()) {
            return List.of();
        }
        if (parameterAnalysisService == null) {
            return List.of();
        }
        return parameterAnalysisService.inferAll(extracted.parameters());
    }

    /**
     * 过滤空白诊断并去重。
     */
    private List<String> cleanDiagnostics(List<String> diagnostics) {
        return diagnostics.stream()
                .filter(message -> message != null && !message.isBlank())
                .distinct()
                .toList();
    }
}
