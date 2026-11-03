package cn.jnu.zhangcs.jsqlint.analysis;

import cn.jnu.zhangcs.jsqlint.support.FixtureProjectCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

/**
 * 召回率探针的明细下钻：逐 fixture 打印提取到的每条语句的 statementId 与 rawSql 首行，
 * 用于人工比对标注清单、定位重复计数（同一调用点多条语句）与误报。
 *
 * @author zhangcs
 */
class ExtractionRecallDetailProbeTest {

    @TempDir
    Path tempDir;

    @Test
    @Timeout(300)
    void printDetails() throws Exception {
        for (String fixture : List.of(
                "native-jdbc-simple",
                "spring-jdbc-simple",
                "spring-boot-mybatis-simple",
                "spring-data-jpa-simple",
                "book-management-mixed")) {
            Path source = new FixtureProjectCompiler(tempDir).compile(fixture);
            AnalyzeResult result = new Analyzer(source, null).analyze();
            System.out.println("########## " + fixture + " (" + result.getStatements().size() + " 条) ##########");
            for (AnalyzeResult.SqlStatementResult statement : result.getStatements()) {
                String raw = statement.getRawSql().replaceAll("\\s+", " ");
                if (raw.length() > 90) {
                    raw = raw.substring(0, 90) + "...";
                }
                System.out.println("  [" + statement.getStatementType() + "] "
                        + statement.getStatementId() + "\n      SQL: " + raw);
            }
        }
    }
}
