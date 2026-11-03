package cn.jnu.zhangcs.jsqlint.analysis;

import cn.jnu.zhangcs.jsqlint.support.FixtureProjectCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL 提取召回率探针测试。
 *
 * <p>对 shared-test-resources 下每个 fixture 以完整 {@link Analyzer}（全部提取器）
 * 执行分析，将提取到的语句数与人工标注的 SQL 调用点数对比，输出召回率报表。</p>
 *
 * <p>标注口径：每个会向数据库发送 SQL 文本的调用点计 1 条
 * （prepareStatement(sql)/execute(sql)/executeQuery(sql)/executeUpdate(sql)/addBatch(sql)/
 * jdbcTemplate.{query,update,queryForObject}/namedJdbcTemplate.{query,update}/
 * entityManager.{createQuery,createNativeQuery}/MyBatis 注解与 XML 语句/JPA @Query 与派生方法）。
 * 无 SQL 参数的 executeUpdate()/executeBatch() 不计（其 SQL 已在 prepareStatement
 * 处计过，避免双算）。</p>
 *
 * <p>本测试为诊断探针：不设召回率断言，基线数字随提取器能力演进更新；
 * 标注数与提取明细的差异即下一步提取器能力的改进清单。</p>
 *
 * @author zhangcs
 */
class ExtractionRecallProbeTest {

    @TempDir
    Path tempDir;

    /**
     * 各 fixture 人工标注的 SQL 调用点数（经 ExtractionRecallDetailProbeTest 明细核对修正）。
     * <ul>
     *   <li>native-jdbc-simple：18（与 NativeJdbcSimpleSqlExtractionTest 的端到端断言一致）</li>
     *   <li>spring-jdbc-simple：15（jdbcTemplate 11 + namedJdbcTemplate 2 + SchemaInitializer 2）</li>
     *   <li>spring-boot-mybatis-simple：14（注解/Provider 8 + XML 5 + schema.sql 1；XML find 未提取，实际 13）</li>
     *   <li>spring-data-jpa-simple：24（接口方法 15 + EntityManager 8 + schema.sql 1）</li>
     *   <li>book-management-mixed：30（schema.sql 3 + AuthorMapper 10 + BorrowJdbc 8 + BookJpa 6 + BookService 3）</li>
     * </ul>
     *
     * <p>注意：原始提取数含 JPA 提取器对非 JPA 类 find、delete、count 前缀方法的
     * 误报（生成无调用点后缀的裸 statementId，与真实提取重复），
     * 召回率超 100% 即该误报所致，精确召回需按去重后的真实调用点条目计算。</p>
     */
    private static final Map<String, Integer> EXPECTED = new LinkedHashMap<>(Map.of(
            "native-jdbc-simple", 18,
            "spring-jdbc-simple", 15,
            "spring-boot-mybatis-simple", 14,
            "spring-data-jpa-simple", 24,
            "book-management-mixed", 30));

    private static final List<String> FIXTURES = List.of(
            "native-jdbc-simple",
            "spring-jdbc-simple",
            "spring-boot-mybatis-simple",
            "spring-data-jpa-simple",
            "book-management-mixed");

    @Test
    @Timeout(300)
    void printRecallReport() throws Exception {
        Map<String, Integer> extracted = new LinkedHashMap<>();
        Map<String, List<String>> failureDiagnostics = new LinkedHashMap<>();
        for (String fixture : FIXTURES) {
            try {
                Path source = new FixtureProjectCompiler(tempDir).compile(fixture);
                AnalyzeResult result = new Analyzer(source, null).analyze();
                extracted.put(fixture, result.getStatements().size());
                if (!result.getDiagnostics().isEmpty()) {
                    failureDiagnostics.put(fixture, result.getDiagnostics());
                }
            } catch (RuntimeException e) {
                extracted.put(fixture, -1);
                failureDiagnostics.put(fixture, List.of("分析异常: " + e));
            }
        }

        StringBuilder report = new StringBuilder("\n==================== SQL 提取召回率报告 ====================\n");
        report.append(String.format("%-32s %8s %10s %10s%n", "fixture", "标注数", "提取数", "召回率"));
        int totalExpected = 0;
        int totalExtracted = 0;
        for (String fixture : FIXTURES) {
            int expectedCount = EXPECTED.get(fixture);
            Integer got = extracted.get(fixture);
            int actual = got == null ? 0 : got;
            String rate = actual < 0 ? "分析失败"
                    : expectedCount == 0 ? "-" : (actual * 100 / expectedCount) + "%";
            report.append(String.format("%-32s %8d %10d %10s%n",
                    fixture, expectedCount, actual, rate));
            totalExpected += expectedCount;
            totalExtracted += Math.max(actual, 0);
        }
        report.append(String.format("%-32s %8d %10d %10s%n", "合计", totalExpected, totalExtracted,
                totalExpected == 0 ? "-" : (totalExtracted * 100 / totalExpected) + "%"));
        if (!failureDiagnostics.isEmpty()) {
            report.append("------------------------------------------------------------\n");
            for (Map.Entry<String, List<String>> entry : failureDiagnostics.entrySet()) {
                report.append("诊断[").append(entry.getKey()).append("]: ")
                        .append(entry.getValue().size()).append(" 条\n");
                for (String message : entry.getValue()) {
                    report.append("  ").append(message).append("\n");
                }
            }
        }
        report.append("============================================================");
        System.out.println(report);
    }
}
