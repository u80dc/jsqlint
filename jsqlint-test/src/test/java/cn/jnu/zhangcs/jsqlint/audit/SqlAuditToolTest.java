package cn.jnu.zhangcs.jsqlint.audit;

import cn.jnu.zhangcs.jsqlint.audit.model.RiskLevel;
import cn.jnu.zhangcs.jsqlint.audit.model.SqlAuditFact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlAuditTool} 的行为测试。
 *
 * <p>每条用例通过构造小的 DRL 规则片段驱动真实的 Drools 编译与执行，
 * 覆盖规则过滤、SQL 类型推断、命中规则映射、结果规整与异常路径。</p>
 */
class SqlAuditToolTest {

    @Test
    @Timeout(120)
    void auditsDeleteWithoutWhereAndMapsBackToBusinessRule() {
        AuditRule rule = rule(1L, "DELETE_NO_WHERE", "删除必须带where", "high", """
                rule "delete without where"
                when
                    $f : SqlAuditFact(sqlType == "DELETE", sql not contains " where ")
                then
                    $f.setRiskLevel(RiskLevel.HIGH);
                    $f.addRiskFactor("DELETE_WITHOUT_WHERE");
                end
                """);

        SqlAuditResult result = SqlAuditTool.audit("DELETE FROM users", List.of(rule));

        assertEquals(RiskLevel.HIGH, result.riskLevel());
        assertEquals(List.of("DELETE_WITHOUT_WHERE"), result.riskFactors());
        assertEquals(1, result.evaluatedRuleCount());
        assertEquals(1, result.firedRuleCount());
        assertEquals(1, result.matchedRules().size());

        MatchedAuditRule matched = result.matchedRules().get(0);
        assertEquals(1L, matched.ruleId());
        assertEquals("DELETE_NO_WHERE", matched.ruleCode());
        assertEquals("删除必须带where", matched.ruleName());
        assertEquals("high", matched.severity());
        assertTrue(result.hasIssues());
    }

    @Test
    @Timeout(120)
    void passesWhenNoRuleMatches() {
        AuditRule rule = rule(2L, "SELECT_STAR", "禁止select *", "warning", """
                rule "select star"
                when
                    $f : SqlAuditFact(sqlType == "SELECT", sql contains "*")
                then
                    $f.setRiskLevel(RiskLevel.WARNING);
                end
                """);

        SqlAuditResult result = SqlAuditTool.audit("SELECT id, name FROM users WHERE id = 1", List.of(rule));

        assertEquals(RiskLevel.INFO, result.riskLevel());
        assertTrue(result.riskFactors().isEmpty());
        assertTrue(result.matchedRules().isEmpty());
        assertEquals(0, result.firedRuleCount());
        assertEquals(1, result.evaluatedRuleCount());
        assertTrue(result.passed());
    }

    @Test
    @Timeout(120)
    void infersSqlTypeSkippingLeadingComments() {
        AuditRule rule = deleteNoWhereRule(3L);

        SqlAuditResult blockComment = SqlAuditTool.audit("/* 头部注释 */ DELETE FROM orders", List.of(rule));
        assertEquals(RiskLevel.HIGH, blockComment.riskLevel());

        SqlAuditResult lineComment = SqlAuditTool.audit("-- 头部注释\nDELETE FROM orders", List.of(rule));
        assertEquals(RiskLevel.HIGH, lineComment.riskLevel());

        assertEquals("SELECT", SqlAuditTool.audit("  select 1", List.of()).sqlType());
        assertEquals("UNKNOWN", SqlAuditTool.audit("", List.of()).sqlType());
    }

    @Test
    @Timeout(120)
    void skipsDisabledEmptyAndNullRules() {
        AuditRule disabled = rule(4L, "DISABLED", "已禁用规则", "high", """
                rule "always fire disabled"
                when
                    $f : SqlAuditFact()
                then
                    $f.setRiskLevel(RiskLevel.CRITICAL);
                end
                """);
        disabled.setEnabled(0);

        SqlAuditResult result = SqlAuditTool.audit("DELETE FROM users", Arrays.asList(
                disabled,
                rule(5L, "NO_CONTENT", "空内容", "high", (String) null),
                rule(6L, "BLANK_CONTENT", "空白内容", "high", "   "),
                null));

        assertEquals(0, result.evaluatedRuleCount());
        assertEquals(0, result.firedRuleCount());
        assertTrue(result.passed());
    }

    @Test
    @Timeout(120)
    void emptyRuleListReturnsInfoResultWithoutCompilation() {
        SqlAuditResult result = SqlAuditTool.audit("DELETE FROM users", Collections.emptyList());

        assertEquals("DELETE", result.sqlType());
        assertEquals(RiskLevel.INFO, result.riskLevel());
        assertEquals(0, result.evaluatedRuleCount());
        assertEquals(0, result.firedRuleCount());
        assertTrue(result.matchedRules().isEmpty());
    }

    @Test
    @Timeout(120)
    void multipleRulesCanFireAndEachMapsBackToItsBusinessRule() {
        AuditRule deleteRule = deleteNoWhereRule(7L);
        AuditRule truncateRule = rule(8L, "TRUNCATE_TABLE", "禁止truncate", "critical", """
                rule "truncate table"
                when
                    $f : SqlAuditFact(sql.toLowerCase() contains "truncate")
                then
                    $f.setRiskLevel(RiskLevel.CRITICAL);
                    $f.addRiskFactor("TRUNCATE_TABLE");
                end
                """);

        SqlAuditResult result = SqlAuditTool.audit("TRUNCATE TABLE logs", List.of(deleteRule, truncateRule));

        assertEquals(RiskLevel.CRITICAL, result.riskLevel());
        assertEquals(List.of("TRUNCATE_TABLE"), result.riskFactors());
        assertEquals(2, result.evaluatedRuleCount());
        assertEquals(1, result.firedRuleCount());

        MatchedAuditRule matched = result.matchedRules().get(0);
        assertEquals(8L, matched.ruleId());
        assertEquals("TRUNCATE_TABLE", matched.ruleCode());
    }

    @Test
    @Timeout(120)
    void keepsOriginalDrlRuleNameInMatchedResultWhenRuntimeNameIsRewritten() {
        AuditRule rule = rule(9L, "DUP_NAME", "与其他规则同名", "warning", """
                rule "shared name"
                when
                    $f : SqlAuditFact(sqlType == "DELETE")
                then
                    $f.setRiskLevel(RiskLevel.WARNING);
                end
                """);
        AuditRule sameNameRule = rule(10L, "DUP_NAME_2", "重复展示名规则", "warning", """
                rule "shared name"
                when
                    $f : SqlAuditFact(sqlType == "DELETE")
                then
                    $f.addRiskFactor("DUP");
                end
                """);

        SqlAuditResult result = SqlAuditTool.audit("DELETE FROM users WHERE id = 1", List.of(rule, sameNameRule));

        assertEquals(2, result.firedRuleCount());
        assertEquals(2, result.matchedRules().size());
        assertEquals("shared name", result.matchedRules().get(0).droolsRuleName());
        assertEquals("shared name", result.matchedRules().get(1).droolsRuleName());
        assertNotNull(result.matchedRules().get(0).ruleId());
        assertNotNull(result.matchedRules().get(1).ruleId());
    }

    @Test
    @Timeout(120)
    void factOverloadUsesProvidedSqlTypeAndSourceContext() {
        AuditRule rule = deleteNoWhereRule(11L);
        SqlAuditFact fact = new SqlAuditFact("DELETE FROM users", "DELETE");
        fact.setSourceMethod("UserMapper.deleteAll");
        fact.setFilePath("UserMapper.java");
        fact.setLineNumber(42);

        SqlAuditResult result = SqlAuditTool.audit(fact, List.of(rule));

        assertEquals("DELETE", result.sqlType());
        assertEquals(RiskLevel.HIGH, result.riskLevel());
        assertEquals("UserMapper.deleteAll", fact.getSourceMethod());
        assertEquals("UserMapper.java", fact.getFilePath());
        assertEquals(42, fact.getLineNumber());
    }

    @Test
    @Timeout(120)
    void factWithoutSqlTypeGetsInferredType() {
        AuditRule rule = deleteNoWhereRule(12L);
        SqlAuditFact fact = new SqlAuditFact("DELETE FROM users", null);

        SqlAuditResult result = SqlAuditTool.audit(fact, List.of(rule));

        assertEquals("DELETE", fact.getSqlType());
        assertEquals("DELETE", result.sqlType());
        assertEquals(RiskLevel.HIGH, result.riskLevel());
    }

    @Test
    @Timeout(120)
    void invalidDrlContentFailsWithSqlAuditException() {
        AuditRule broken = rule(13L, "BROKEN", "语法错误规则", "high", """
                rule broken rule
                when
                    $f : SqlAuditFact(
                then
                end
                """);

        SqlAuditException exception = assertThrows(SqlAuditException.class,
                () -> SqlAuditTool.audit("DELETE FROM users", List.of(broken)));
        assertTrue(exception.getMessage().contains("规则编译失败"));
    }

    @Test
    @Timeout(120)
    void validateRulesAcceptsValidRulesAndRejectsBrokenOnes() {
        AuditRule valid = deleteNoWhereRule(14L);
        SqlAuditTool.validateRules(List.of(valid));
        SqlAuditTool.validateRules(Collections.emptyList());
        SqlAuditTool.validateRules(null);

        AuditRule broken = rule(15L, "BROKEN_VALIDATE", "语法错误规则", "high", """
                rule broken rule
                when
                    $f : SqlAuditFact(
                then
                end
                """);
        assertThrows(SqlAuditException.class, () -> SqlAuditTool.validateRules(List.of(broken)));
    }

    @Test
    @Timeout(120)
    void ruleSetCacheIsIsolatedByRuleContent() {
        AuditRule original = deleteNoWhereRule(16L);

        SqlAuditResult first = SqlAuditTool.audit("DELETE FROM users", List.of(original));
        assertEquals(RiskLevel.HIGH, first.riskLevel());

        AuditRule relaxed = deleteNoWhereRule(16L);
        relaxed.setDroolsContent("""
                rule "delete without where"
                when
                    $f : SqlAuditFact(sqlType == "SELECT")
                then
                    $f.setRiskLevel(RiskLevel.HIGH);
                end
                """);

        SqlAuditResult second = SqlAuditTool.audit("DELETE FROM users", List.of(relaxed));
        assertEquals(RiskLevel.INFO, second.riskLevel());

        SqlAuditResult third = SqlAuditTool.audit("DELETE FROM users", List.of(original));
        assertEquals(RiskLevel.HIGH, third.riskLevel());
    }

    @Test
    @Timeout(120)
    void ruleWithoutIdOrCodeStillAuditsAndReportsRuntimeName() {
        AuditRule anonymous = new AuditRule();
        anonymous.setName("匿名规则");
        anonymous.setSeverity("warning");
        anonymous.setEnabled(1);
        anonymous.setDroolsContent("""
                rule "anonymous rule"
                when
                    $f : SqlAuditFact(sqlType == "DELETE")
                then
                    $f.setRiskLevel(RiskLevel.WARNING);
                end
                """);

        SqlAuditResult result = SqlAuditTool.audit("DELETE FROM users WHERE id = 1", List.of(anonymous));

        assertEquals(RiskLevel.WARNING, result.riskLevel());
        assertEquals(1, result.matchedRules().size());
        assertEquals("anonymous rule", result.matchedRules().get(0).droolsRuleName());
    }

    @Test
    @Timeout(120)
    void singleFragmentContainingMultipleRulesFiresEach() {
        AuditRule multi = rule(17L, "MULTI", "片段含多条规则", "warning", """
                rule "first part"
                when
                    $f : SqlAuditFact(sqlType == "DELETE")
                then
                    $f.addRiskFactor("PART_1");
                end

                rule "second part"
                when
                    $f : SqlAuditFact(sql.contains("FROM users"))
                then
                    $f.addRiskFactor("PART_2");
                end
                """);

        SqlAuditResult result = SqlAuditTool.audit("DELETE FROM users WHERE id = 1", List.of(multi));

        assertEquals(2, result.firedRuleCount());
        List<String> factors = new ArrayList<>(result.riskFactors());
        Collections.sort(factors);
        assertEquals(List.of("PART_1", "PART_2"), factors);
        assertEquals(17L, result.matchedRules().get(0).ruleId());
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> SqlAuditTool.audit((String) null, List.of()));
        SqlAuditFact fact = new SqlAuditFact("DELETE FROM users", "DELETE");
        assertThrows(NullPointerException.class, () -> SqlAuditTool.audit((SqlAuditFact) null, List.of()));
        assertNotNull(fact);
    }

    private AuditRule deleteNoWhereRule(Long id) {
        return rule(id, "DELETE_NO_WHERE_" + id, "删除必须带where", "high", """
                rule "delete without where"
                when
                    $f : SqlAuditFact(sqlType == "DELETE", sql not contains " where ")
                then
                    $f.setRiskLevel(RiskLevel.HIGH);
                    $f.addRiskFactor("DELETE_WITHOUT_WHERE");
                end
                """);
    }

    private AuditRule rule(Long id, String code, String name, String severity, String droolsContent) {
        AuditRule rule = new AuditRule();
        rule.setId(id);
        rule.setCode(code);
        rule.setName(name);
        rule.setSeverity(severity);
        rule.setCategory("安全风险");
        rule.setSuggestion("请补充where条件");
        rule.setEnabled(1);
        rule.setDroolsContent(droolsContent);
        return rule;
    }
}
