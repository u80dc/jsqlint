package cn.jnu.zhangcs.jsqlint.analysis.extractor;

import cn.jnu.zhangcs.jsqlint.analysis.AnalyzeResult.SqlStatementResult;
import cn.jnu.zhangcs.jsqlint.analysis.SqlSourceType;
import cn.jnu.zhangcs.jsqlint.analysis.StatementType;
import cn.jnu.zhangcs.jsqlint.analysis.jpa.JpaSqlExtractor;
import cn.jnu.zhangcs.jsqlint.support.FixtureProjectCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static cn.jnu.zhangcs.jsqlint.analysis.extractor.ExtractorTestSupport.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JpaSqlExtractorTest {

    @TempDir
    Path tempDir;

    @Test
    @Timeout(60)
    void extractsSpringDataJpaAnnotationsAndEntityManagerCallSites() throws Exception {
        Path source = new FixtureProjectCompiler(tempDir).compile("spring-data-jpa-simple");

        var context = context(source);
        SqlExtractionResult result = new JpaSqlExtractor().extract(context);
        List<SqlStatementResult> statements = assemble(context, result);

        assertTrue(statements.size() >= 20, () -> statements.stream()
                .map(SqlStatementResult::getRawSql)
                .reduce((left, right) -> left + "\n---\n" + right)
                .orElse(""));
        assertTrue(statements.stream()
                .filter(statement -> statement.getSourceType() == SqlSourceType.STATIC_SQL)
                .count() >= 12);
        assertTrue(statements.stream()
                .filter(statement -> statement.getSourceType() == SqlSourceType.XML_SQL)
                .count() >= 3);
        assertTrue(statements.stream()
                .filter(statement -> statement.getSourceType() == SqlSourceType.PARAM_SQL)
                .count() >= 5);
        assertTrue(statements.stream().allMatch(statement -> statement.getMetadata().getTags().contains("jpa")));
        assertTrue(statements.stream().anyMatch(statement -> statement.getMetadata().getTags().contains("spring-data-jpa")));
        assertTrue(statements.stream().anyMatch(statement -> statement.getMetadata().getTags().contains("entity-manager")));
        assertTrue(statements.stream().allMatch(statement -> !statement.getRenderedSqls().isEmpty()));

        assertSql(statements, StatementType.SELECT,
                "select u from User u where u.username = :username");
        assertSql(statements, StatementType.SELECT,
                "SELECT id, username, email, status, age, created_at, updated_at FROM users WHERE status = ?1");
        assertSql(statements, StatementType.SELECT,
                "select u from User u where u.status = :status order by u.id asc");
        assertSql(statements, StatementType.UPDATE,
                "update User u set u.status = :status where u.id = :id");
        assertSql(statements, StatementType.UPDATE,
                "UPDATE users SET email = ?2, updated_at = CURRENT_TIMESTAMP WHERE id = ?1");
        assertSql(statements, StatementType.DELETE,
                "DELETE FROM users WHERE status = ?1");
        assertSql(statements, StatementType.SELECT,
                "select e from User e where e.status = ?1 order by e.createdAt desc");
        assertSql(statements, StatementType.SELECT,
                "select e from User e where e.status = ?1 and e.age >= ?2");
        assertSql(statements, StatementType.SELECT,
                "select e from User e where e.email = ?1");
        assertSql(statements, StatementType.SELECT,
                "select count(e) from User e where e.status = ?1");
        assertSql(statements, StatementType.SELECT,
                "select count(e) from User e where e.username = ?1");
        assertSql(statements, StatementType.DELETE,
                "delete from User e where e.email = ?1");
        assertSql(statements, StatementType.SELECT,
                "select u from User u where u.status = :status order by u.updatedAt desc");
        assertSql(statements, StatementType.SELECT,
                "select count(u) from User u where u.status = :status");
        assertSql(statements, StatementType.SELECT,
                "SELECT id, username, email, status, age, created_at, updated_at FROM users WHERE email LIKE CONCAT('%', ?1) ORDER BY id DESC");
        assertSql(statements, StatementType.SELECT,
                "select u from User u where u.status = :status");
        assertSql(statements, StatementType.SELECT,
                "SELECT id, username, email, status, age, created_at, updated_at FROM users WHERE id = ?");
        assertSql(statements, StatementType.UPDATE,
                "update User u set");
        assertSql(statements, StatementType.DELETE,
                "DELETE FROM users");
        assertSql(statements, StatementType.TRUNCATE,
                "TRUNCATE TABLE users");
    }
}
