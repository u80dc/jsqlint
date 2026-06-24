package cn.jnu.zhangcs.jsqlint.analysis.extractor;

import cn.jnu.zhangcs.jsqlint.analysis.AnalyzeResult;
import cn.jnu.zhangcs.jsqlint.analysis.StatementType;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.ParameterTraceAnalyzer;
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

/**
 * SQL提取测试支持
 *
 * @author zhangcs
 */
public final class ExtractorTestSupport {

    private ExtractorTestSupport() {
    }

    public static SqlExtractorContext context(Path source) {
        List<AnalysisInputLocation> locations = new ArrayList<>();
        locations.add(new JrtFileSystemAnalysisInputLocation());
        locations.add(PathBasedAnalysisInputLocation.create(source.toAbsolutePath(), SourceType.Application));

        JavaView view = new MutableJavaView(locations);
        EnhancedCallGraph graph = EnhancedCallGraph.initialize(view);
        ParameterTraceAnalyzer analyzer = new ParameterTraceAnalyzer(view, graph);
        return new SqlExtractorContext(source, view, graph, analyzer);
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
