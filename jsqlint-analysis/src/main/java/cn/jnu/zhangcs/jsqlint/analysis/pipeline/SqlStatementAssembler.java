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
 * Builds final analysis results after SQL extraction has completed.
 */
public final class SqlStatementAssembler {

    private final ParameterAnalysisService parameterAnalysisService;

    public SqlStatementAssembler() {
        this(null);
    }

    public SqlStatementAssembler(ParameterAnalysisService parameterAnalysisService) {
        this.parameterAnalysisService = parameterAnalysisService;
    }

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

    private List<ParameterValueInferenceResult> inferParameters(SqlStatement extracted) {
        if (extracted.parameters().isEmpty()) {
            return List.of();
        }
        if (parameterAnalysisService == null) {
            return List.of();
        }
        return parameterAnalysisService.inferAll(extracted.parameters());
    }

    private List<String> cleanDiagnostics(List<String> diagnostics) {
        return diagnostics.stream()
                .filter(message -> message != null && !message.isBlank())
                .distinct()
                .toList();
    }
}
