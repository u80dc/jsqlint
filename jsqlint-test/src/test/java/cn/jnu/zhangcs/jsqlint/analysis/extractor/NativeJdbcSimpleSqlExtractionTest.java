package cn.jnu.zhangcs.jsqlint.analysis.extractor;

import cn.jnu.zhangcs.jsqlint.analysis.AnalyzeResult.SqlStatementResult;
import cn.jnu.zhangcs.jsqlint.analysis.SqlSourceType;
import cn.jnu.zhangcs.jsqlint.analysis.StatementType;
import cn.jnu.zhangcs.jsqlint.analysis.jdbc.NativeJdbcSqlExtractor;
import cn.jnu.zhangcs.jsqlint.support.FixtureProjectCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static cn.jnu.zhangcs.jsqlint.analysis.extractor.ExtractorTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeJdbcSimpleSqlExtractionTest {

    @TempDir
    Path tempDir;

    @Test
    @Timeout(60)
    void extractsNativeJdbcSqlFromNativeJdbcSimpleFixture() throws Exception {
        Path source = new FixtureProjectCompiler(tempDir).compile("native-jdbc-simple");

        var context = context(source);
        SqlExtractionResult result = new NativeJdbcSqlExtractor().extract(context);
        List<SqlStatementResult> statements = assemble(context, result);

        // deleteByDynamicStatus 经过程间返回值内联提取；queryUsers 经调用方实参回溯提取
        //（sql 由调用方 search() 用 StringBuilder 构造后传入通用执行方法）。
        // 两条跨方法链路均已打通，跳过诊断应为空。
        assertAll(
                () -> assertEquals(18, statements.size(), () -> "Expected SQL statements from native-jdbc-simple, got " + statements.size()),
                () -> assertTrue(result.diagnostics().isEmpty(),
                        () -> "Expected no skip diagnostics, got: " + String.join("\n", result.diagnostics())),
                () -> assertTrue(statements.stream()
                        .allMatch(statement -> statement.getSourceType() == SqlSourceType.PARAM_SQL)),
                () -> assertTrue(statements.stream()
                        .allMatch(statement -> statement.getMetadata().getTags().contains("jdbc"))),
                () -> assertTrue(statements.stream()
                        .anyMatch(statement -> "Statement".equals(statement.getMetadata().getExtra().get("jdbcCategory")))),
                () -> assertTrue(statements.stream().allMatch(SqlStatementResult::hasRenderedSqls)),
                // 调用点语句的参数推断不应为空：值模型依赖调用者形参时必须能追溯参数轨迹
                () -> assertTrue(statements.stream()
                        .anyMatch(SqlStatementResult::hasInferredParameters),
                        () -> "Expected at least one statement with inferred parameters, rawSql:\n"
                                + statements.stream()
                                .map(SqlStatementResult::getRawSql)
                                .reduce((left, right) -> left + "\n---\n" + right)
                                .orElse("")),
                // 轨迹应跨方法传播：步骤非空说明从调用点向上游调用者完成了追踪
                () -> assertTrue(statements.stream()
                        .flatMap(statement -> statement.getParameterInferences().stream())
                        .flatMap(inference -> inference.possibleValues().stream())
                        .anyMatch(value -> !value.propagationPath().steps().isEmpty()),
                        () -> "Expected cross-method propagation steps in parameter traces")
        );

        assertSql(statements, StatementType.INSERT,
                "INSERT INTO users (username, email, status, age) VALUES (?, ?, ?, ?)");
        assertSql(statements, StatementType.SELECT,
                "SELECT id, username, email, status, age, created_at, updated_at FROM users WHERE id = ?");
        assertSql(statements, StatementType.SELECT,
                "SELECT COUNT(*) FROM users");
        assertSql(statements, StatementType.UPDATE,
                "UPDATE users SET");
        assertSql(statements, StatementType.UPDATE,
                "UPDATE users SET status");
        assertSql(statements, StatementType.DELETE,
                "DELETE FROM users WHERE id = ?");
        assertSql(statements, StatementType.DELETE,
                "DELETE FROM users WHERE status");
        assertSql(statements, StatementType.TRUNCATE,
                "TRUNCATE TABLE users");
    }
}
