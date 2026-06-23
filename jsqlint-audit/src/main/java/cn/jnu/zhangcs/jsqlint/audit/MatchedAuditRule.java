package cn.jnu.zhangcs.jsqlint.audit;

/**
 * 一条被 Drools 实际触发的业务审核规则。
 *
 * @param droolsRuleName Drools 运行时触发的 rule 名称
 * @param ruleId         业务规则ID，可能为空
 * @param ruleCode       业务规则编码，可能为空
 * @param ruleName       业务规则名称，可能为空
 * @param severity       业务风险等级，可能为空
 * @param category       业务规则分类，可能为空
 * @param suggestion     修复建议，可能为空
 */
public record MatchedAuditRule(
        String droolsRuleName,
        Long ruleId,
        String ruleCode,
        String ruleName,
        String severity,
        String category,
        String suggestion) {

    /**
     * 将 Drools 触发的规则名与数据库规则元数据合并为审核结果中的命中规则。
     */
    static MatchedAuditRule from(String droolsRuleName, AuditRule rule) {
        if (rule == null) {
            return new MatchedAuditRule(droolsRuleName, null, null, null, null, null, null);
        }
        return new MatchedAuditRule(
                droolsRuleName,
                rule.getId(),
                rule.getCode(),
                rule.getName(),
                rule.getSeverity(),
                rule.getCategory(),
                rule.getSuggestion());
    }
}
