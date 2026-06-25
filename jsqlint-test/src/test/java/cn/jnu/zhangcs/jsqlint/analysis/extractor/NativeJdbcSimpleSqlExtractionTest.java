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

        assertAll(
                () -> assertEquals(11, statements.size(), () -> "Expected SQL statements from native-jdbc-simple, got " + statements.size()),
                () -> assertTrue(result.diagnostics().isEmpty(), () -> String.join("\n", result.diagnostics())),
                () -> assertTrue(statements.stream()
                        .allMatch(statement -> statement.getSourceType() == SqlSourceType.PARAM_SQL)),
                () -> assertTrue(statements.stream()
                        .allMatch(statement -> statement.getMetadata().getTags().contains("jdbc"))),
                () -> assertTrue(statements.stream()
                        .anyMatch(statement -> "Statement".equals(statement.getMetadata().getExtra().get("jdbcCategory")))),
                () -> assertTrue(statements.stream().allMatch(SqlStatementResult::hasRenderedSqls))
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
