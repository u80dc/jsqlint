package cn.jnu.zhangcs.jsqlint.analysis.extractor;

import cn.jnu.zhangcs.jsqlint.analysis.AnalyzeResult;
import cn.jnu.zhangcs.jsqlint.analysis.StatementType;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.ParameterAnalysisService;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.ParameterTraceAnalyzer;
import cn.jnu.zhangcs.jsqlint.analysis.pipeline.SqlStatementAssembler;
import sootup.callgraph.EnhancedCallGraph;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.JrtFileSystemAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.PathBasedAnalysisInputLocation;
import sootup.java.core.views.JavaView;
import sootup.java.core.views.MutableJavaView;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

public final class ExtractorTestSupport {

    private ExtractorTestSupport() {
    }

    public static SqlExtractorContext context(Path source) {
        List<AnalysisInputLocation> locations = new ArrayList<>();
        locations.add(new JrtFileSystemAnalysisInputLocation());
        locations.add(PathBasedAnalysisInputLocation.create(source.toAbsolutePath(), SourceType.Application));

        JavaView view = new MutableJavaView(locations);
        EnhancedCallGraph graph = EnhancedCallGraph.initialize(view);
        return new SqlExtractorContext(source, view, graph);
    }

    public static List<AnalyzeResult.SqlStatementResult> assemble(
            SqlExtractorContext context,
            SqlExtractionResult result) {

        ParameterTraceAnalyzer analyzer = new ParameterTraceAnalyzer(context.view(), context.graph());
        SqlStatementAssembler assembler = new SqlStatementAssembler(new ParameterAnalysisService(analyzer));
        return result.statements().stream()
                .map(assembler::assemble)
                .toList();
    }

    public static List<AnalyzeResult.SqlStatementResult> assemble(SqlExtractionResult result) {
        SqlStatementAssembler assembler = new SqlStatementAssembler();
        return result.statements().stream()
                .map(assembler::assemble)
                .toList();
    }

    public static void assertSql(
            List<AnalyzeResult.SqlStatementResult> statements,
            StatementType type,
            String expectedSql) {

        String expected = normalizeSql(expectedSql);
        assertTrue(statements.stream()
                        .filter(statement -> statement.getStatementType() == type)
                        .flatMap(statement -> statement.getRenderedSqls().stream())
                        .map(rendered -> normalizeSql(rendered.sql()))
                        .anyMatch(sql -> sql.contains(expected)),
                () -> statements.stream()
                        .map(AnalyzeResult.SqlStatementResult::getRawSql)
                        .reduce((left, right) -> left + "\n---\n" + right)
                        .orElse(""));
    }

    public static String normalizeSql(String value) {
        return value.replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
