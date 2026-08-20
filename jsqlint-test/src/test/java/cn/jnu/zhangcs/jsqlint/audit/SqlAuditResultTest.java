package cn.jnu.zhangcs.jsqlint.audit;

import cn.jnu.zhangcs.jsqlint.audit.model.RiskLevel;
import cn.jnu.zhangcs.jsqlint.audit.model.SqlAuditFact;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlAuditResult} 与 {@link RiskLevel} 的纯逻辑测试。
 */
class SqlAuditResultTest {

    @Test
    void canonicalConstructorNormalizesNullCollectionsAndLevel() {
        SqlAuditResult result = new SqlAuditResult("select 1", "SELECT", null, null, null, null, 3, 0);

        assertEquals(RiskLevel.INFO, result.riskLevel());
        assertTrue(result.riskFactors().isEmpty());
        assertTrue(result.matchedRules().isEmpty());
        assertEquals(3, result.evaluatedRuleCount());
        assertEquals(0, result.firedRuleCount());
        assertTrue(result.passed());
        assertFalse(result.hasIssues());
    }

    @Test
    void fromFactCarriesFactStateIntoResult() {
        SqlAuditFact fact = new SqlAuditFact("DELETE FROM users", "DELETE");
        fact.setRiskLevel(RiskLevel.CRITICAL);
        fact.addRiskFactor("DELETE_WITHOUT_WHERE");
        fact.setSuggestion("补充where条件");
        MatchedAuditRule matched = MatchedAuditRule.from("delete without where", factAuditRule());

        SqlAuditResult result = SqlAuditResult.fromFact(fact, List.of(matched), 1, 1);

        assertEquals("DELETE FROM users", result.sql());
        assertEquals("DELETE", result.sqlType());
        assertEquals(RiskLevel.CRITICAL, result.riskLevel());
        assertEquals(List.of("DELETE_WITHOUT_WHERE"), result.riskFactors());
        assertEquals("补充where条件", result.suggestion());
        assertEquals(List.of(matched), result.matchedRules());
        assertTrue(result.hasIssues());
    }

    @Test
    void passedRequiresInfoLevelNoFactorsAndNoMatches() {
        assertTrue(new SqlAuditResult("select 1", "SELECT",
                RiskLevel.INFO, null, null, null, 0, 0).passed());

        assertFalse(new SqlAuditResult("select 1", "SELECT",
                RiskLevel.WARNING, null, null, null, 0, 0).passed());
        assertFalse(new SqlAuditResult("select 1", "SELECT",
                RiskLevel.INFO, List.of("F"), null, null, 0, 0).passed());
        assertFalse(new SqlAuditResult("select 1", "SELECT",
                RiskLevel.INFO, null, null,
                List.of(MatchedAuditRule.from("r", null)), 0, 0).passed());
    }

    @Test
    void riskLevelResolvesByCodeAndFallsBackToInfo() {
        assertEquals(RiskLevel.INFO, RiskLevel.from("info"));
        assertEquals(RiskLevel.WARNING, RiskLevel.from("warning"));
        assertEquals(RiskLevel.HIGH, RiskLevel.from("high"));
        assertEquals(RiskLevel.CRITICAL, RiskLevel.from("critical"));
        assertEquals(RiskLevel.INFO, RiskLevel.from("unknown"));
        assertEquals(RiskLevel.INFO, RiskLevel.from(null));
        assertEquals("高危", RiskLevel.HIGH.getDescription());
    }

    @Test
    void matchedRuleFromNullMetadataKeepsDroolsNameOnly() {
        MatchedAuditRule matched = MatchedAuditRule.from("some rule", null);

        assertEquals("some rule", matched.droolsRuleName());
        assertNullFields(matched);
    }

    private void assertNullFields(MatchedAuditRule matched) {
        assertEquals(null, matched.ruleId());
        assertEquals(null, matched.ruleCode());
        assertEquals(null, matched.ruleName());
        assertEquals(null, matched.severity());
        assertEquals(null, matched.category());
        assertEquals(null, matched.suggestion());
    }

    private AuditRule factAuditRule() {
        AuditRule rule = new AuditRule();
        rule.setId(99L);
        rule.setCode("CODE_99");
        rule.setName("规则99");
        rule.setSeverity("high");
        rule.setCategory("安全风险");
        rule.setSuggestion("建议");
        return rule;
    }
}
