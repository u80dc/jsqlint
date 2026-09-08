package cn.jnu.zhangcs.jsqlint.server.common.converter;

import cn.jnu.zhangcs.jsqlint.server.entity.AuditRule;
import cn.jnu.zhangcs.jsqlint.server.model.dto.AuditRuleDTO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.AuditRuleVO;

/**
 * 审核规则转换器
 */
public class AuditRuleConverter {

    public static AuditRuleVO toVO(AuditRule rule, String createUserName) {
        if (rule == null) {
            return null;
        }

        AuditRuleVO vo = new AuditRuleVO();
        vo.setId(rule.getId());
        vo.setName(rule.getName());
        vo.setCode(rule.getCode());
        vo.setDescription(rule.getDescription());
        vo.setSeverity(rule.getSeverity());
        vo.setSeverityDescription(rule.getSeverityDescription());
        vo.setCategory(rule.getCategory());
        vo.setCategoryDescription(rule.getCategoryDescription());
        vo.setProjectId(rule.getProjectId());
        vo.setDroolsContent(rule.getDroolsContent());
        vo.setSuggestion(rule.getSuggestion());
        vo.setEnabled(rule.getEnabled());
        vo.setIsBuiltin(rule.getIsBuiltin());
        vo.setCreateUserName(createUserName);
        vo.setCreateTime(rule.getCreateTime());
        vo.setUpdateTime(rule.getUpdateTime());
        return vo;
    }

    public static AuditRule toEntity(AuditRuleDTO dto) {
        if (dto == null) {
            return null;
        }

        AuditRule rule = new AuditRule();
        rule.setName(dto.getName());
        rule.setCode(dto.getCode());
        rule.setDescription(dto.getDescription());
        rule.setSeverity(dto.getSeverity());
        rule.setCategory(dto.getCategory());
        rule.setDroolsContent(dto.getDroolsContent());
        rule.setSuggestion(dto.getSuggestion());
        return rule;
    }

    public static void updateEntity(AuditRule rule, AuditRuleDTO dto) {
        if (rule == null || dto == null) {
            return;
        }

        rule.setName(dto.getName());
        rule.setDescription(dto.getDescription());
        rule.setSeverity(dto.getSeverity());
        rule.setCategory(dto.getCategory());
        rule.setDroolsContent(dto.getDroolsContent());
        rule.setSuggestion(dto.getSuggestion());
    }
}
