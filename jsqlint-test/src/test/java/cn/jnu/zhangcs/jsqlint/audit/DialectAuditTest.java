package cn.jnu.zhangcs.jsqlint.audit;

import cn.jnu.zhangcs.jsqlint.audit.model.RiskLevel;
import cn.jnu.zhangcs.jsqlint.audit.model.SqlAuditFact;
import cn.jnu.zhangcs.jsqlint.audit.model.SqlDialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方言规则过滤与匹配测试。
 *
 * @author zhangcs
 */
class DialectAuditTest {

    private static AuditRule rule(String code, String dialect, String drl) {
        AuditRule rule = new AuditRule();
        rule.setId(1L);
        rule.setCode(code);
        rule.setName(code);
        rule.setDialect(dialect);
        rule.setDroolsContent(drl);
        rule.setEnabled(1);
        return rule;
    }

    @Test
    @Timeout(120)
    void pgRule_firesOnPgSql_notOnMySql() {
        AuditRule pgRule = rule("PG_ILIKE", "postgresql", """
                rule "pg ilike wildcard"
                when
                    $f : SqlAuditFact(sql != null, dialect == SqlDialect.POSTGRESQL)
                    eval($f.getSql().toUpperCase().matches("(?i).*ILIKE[\\\\s]+'%.*"))
                then
                    $f.setRiskLevel(RiskLevel.WARNING);
                    $f.addRiskFactor("PG_ILIKE_WILDCARD");
                end
                """);

        // PG 方言 SQL 命中
        SqlAuditFact pgFact = new SqlAuditFact("SELECT * FROM t WHERE name ILIKE '%abc'", "SELECT");
        pgFact.setDialect(SqlDialect.POSTGRESQL);
        SqlAuditResult pgResult = SqlAuditTool.audit(pgFact, List.of(pgRule));
        assertTrue(pgResult.hasIssues(), "PG 方言 SQL 应命中 PG 专属规则");

        // 同样的 SQL 但项目声明为 MySQL：方言过滤在规则层生效，不进入编译
        SqlAuditFact mysqlFact = new SqlAuditFact("SELECT * FROM t WHERE name ILIKE '%abc'", "SELECT");
        mysqlFact.setDialect(SqlDialect.MYSQL);
        SqlAuditResult mysqlResult = SqlAuditTool.audit(mysqlFact, List.of(pgRule));
        assertFalse(mysqlResult.hasIssues(), "MySQL 方言 SQL 不应命中 PG 专属规则");
        assertEquals(0, mysqlResult.evaluatedRuleCount(), "方言不匹配的规则应被过滤掉，不参与评估");
    }

    @Test
    @Timeout(120)
    void commonRule_firesOnAllDialects() {
        AuditRule commonRule = rule("DELETE_NO_WHERE", null, """
                rule "delete without where"
                when
                    $f : SqlAuditFact(sql != null, sqlType == "DELETE")
                    eval(!$f.getSql().toUpperCase().contains(" WHERE "))
                then
                    $f.setRiskLevel(RiskLevel.CRITICAL);
                    $f.addRiskFactor("DELETE_WITHOUT_WHERE");
                end
                """);

        for (SqlDialect dialect : SqlDialect.values()) {
            SqlAuditFact fact = new SqlAuditFact("DELETE FROM users", "DELETE");
            fact.setDialect(dialect);
            assertTrue(SqlAuditTool.audit(fact, List.of(commonRule)).hasIssues(),
                    "通用规则应对 " + dialect + " 方言生效");
        }

        // 方言未声明的 SQL（null）同样适用通用规则
        SqlAuditFact noDialect = new SqlAuditFact("DELETE FROM users", "DELETE");
        assertTrue(SqlAuditTool.audit(noDialect, List.of(commonRule)).hasIssues(),
                "方言未声明的 SQL 应命中通用规则");
    }

    @Test
    @Timeout(120)
    void oracleRule_dialectConditionInsideDrl() {
        // 规则内部使用 dialect 条件（如 init.sql 的 Oracle 规则写法），
        // 验证 DRL 中引用 SqlDialect 枚举可编译执行
        AuditRule oracleRule = rule("ORACLE_OLD_JOIN", "oracle", """
                rule "oracle old outer join"
                when
                    $f : SqlAuditFact(sql != null, dialect == SqlDialect.ORACLE)
                    eval($f.getSql().toUpperCase().matches("(?i).*\\\\(\\\\+\\\\).*"))
                then
                    $f.setRiskLevel(RiskLevel.WARNING);
                    $f.addRiskFactor("OLD_OUTER_JOIN");
                end
                """);

        SqlAuditFact fact = new SqlAuditFact(
                "SELECT a.id FROM emp a, dept b WHERE a.dept_id = b.id(+)", "SELECT");
        fact.setDialect(SqlDialect.ORACLE);
        SqlAuditResult result = SqlAuditTool.audit(fact, List.of(oracleRule));
        assertTrue(result.hasIssues(), "Oracle (+) 外连接写法应命中 Oracle 专属规则");
    }
}
