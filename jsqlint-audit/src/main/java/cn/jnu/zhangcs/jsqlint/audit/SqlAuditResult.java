package cn.jnu.zhangcs.jsqlint.audit;


import cn.jnu.zhangcs.jsqlint.audit.model.RiskLevel;
import cn.jnu.zhangcs.jsqlint.audit.model.SqlAuditFact;

import java.util.List;

/**
 * SQL审核结果。
 *
 * @param sql                被审核的 SQL 文本
 * @param sqlType            SQL 类型，例如 SELECT、INSERT、UPDATE、DELETE
 * @param riskLevel          Drools 规则执行后得到的最高风险等级或最终风险等级
 * @param riskFactors        规则写入的风险因子列表
 * @param suggestion         规则写入的修复建议
 * @param matchedRules       本次审核实际触发的业务规则
 * @param evaluatedRuleCount 本次参与审核的有效规则数量
 * @param firedRuleCount     Drools 实际触发的规则次数
 *
 * @author zhangcs
 */
public record SqlAuditResult(
        String sql,
        String sqlType,
        RiskLevel riskLevel,
        List<String> riskFactors,
        String suggestion,
        List<MatchedAuditRule> matchedRules,
        int evaluatedRuleCount,
        int firedRuleCount) {

    /**
     * 规整结果中的集合和风险等级，避免调用方处理 null。
     */
    public SqlAuditResult {
        riskLevel = riskLevel == null ? RiskLevel.INFO : riskLevel;
        riskFactors = riskFactors == null ? List.of() : List.copyOf(riskFactors);
        matchedRules = matchedRules == null ? List.of() : List.copyOf(matchedRules);
    }

    /**
     * 从 Drools 执行后的事实对象生成对外返回的审核结果。
     */
    static SqlAuditResult fromFact(
            SqlAuditFact fact,
            List<MatchedAuditRule> matchedRules,
            int evaluatedRuleCount,
            int firedRuleCount) {

        return new SqlAuditResult(
                fact.getSql(),
                fact.getSqlType(),
                fact.getRiskLevel(),
                fact.getRiskFactors(),
                fact.getSuggestion(),
                matchedRules,
                evaluatedRuleCount,
                firedRuleCount);
    }

    /**
     * 判断 SQL 是否通过审核。
     *
     * <p>当前判定标准是风险等级仍为 INFO，且没有风险因子和命中规则。</p>
     */
    public boolean passed() {
        return riskLevel == RiskLevel.INFO && riskFactors.isEmpty() && matchedRules.isEmpty();
    }

    /**
     * 判断 SQL 是否存在审核问题。
     */
    public boolean hasIssues() {
        return !passed();
    }

}
