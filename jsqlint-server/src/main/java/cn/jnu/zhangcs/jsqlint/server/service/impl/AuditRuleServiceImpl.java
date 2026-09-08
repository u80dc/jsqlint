package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.audit.SqlAuditTool;
import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.AuditRule;
import cn.jnu.zhangcs.jsqlint.server.entity.AuditRuleVersion;
import cn.jnu.zhangcs.jsqlint.server.mapper.AuditRuleMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.AuditRuleVersionMapper;
import cn.jnu.zhangcs.jsqlint.server.service.AuditRuleService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AuditRuleServiceImpl implements AuditRuleService {

    private final AuditRuleMapper auditRuleMapper;
    private final AuditRuleVersionMapper auditRuleVersionMapper;

    @Override
    public List<AuditRule> listRules(int page, int pageSize, String name, String category, Boolean enabled) {
        Page<AuditRule> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<AuditRule> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.isNull(AuditRule::getProjectId);

        if (name != null && !name.isEmpty()) {
            queryWrapper.like(AuditRule::getName, name);
        }
        if (category != null && !category.isEmpty()) {
            queryWrapper.eq(AuditRule::getCategory, category);
        }
        if (enabled != null) {
            queryWrapper.eq(AuditRule::getEnabled, enabled ? 1 : 0);
        }

        queryWrapper.orderByDesc(AuditRule::getCreateTime);
        Page<AuditRule> result = auditRuleMapper.selectPage(pageParam, queryWrapper);

        // 填充描述字段
        for (AuditRule rule : result.getRecords()) {
            fillDescriptions(rule);
        }

        return result.getRecords();
    }

    @Override
    public List<AuditRule> listEffectiveRules(Long projectId, Boolean enabled) {
        LambdaQueryWrapper<AuditRule> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.and(scope -> scope.isNull(AuditRule::getProjectId).or().eq(AuditRule::getProjectId, projectId));
        if (enabled != null) {
            queryWrapper.eq(AuditRule::getEnabled, enabled ? 1 : 0);
        }
        queryWrapper.orderByAsc(AuditRule::getProjectId)
                .orderByDesc(AuditRule::getCreateTime);

        List<AuditRule> rules = auditRuleMapper.selectList(queryWrapper);
        for (AuditRule rule : rules) {
            fillDescriptions(rule);
        }
        return rules;
    }

    @Override
    public long countRules(String name, String category, Boolean enabled) {
        LambdaQueryWrapper<AuditRule> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.isNull(AuditRule::getProjectId);

        if (name != null && !name.isEmpty()) {
            queryWrapper.like(AuditRule::getName, name);
        }
        if (category != null && !category.isEmpty()) {
            queryWrapper.eq(AuditRule::getCategory, category);
        }
        if (enabled != null) {
            queryWrapper.eq(AuditRule::getEnabled, enabled ? 1 : 0);
        }

        return auditRuleMapper.selectCount(queryWrapper);
    }

    @Override
    public AuditRule getRuleById(Long id) {
        AuditRule rule = auditRuleMapper.selectById(id);
        if (rule != null) {
            fillDescriptions(rule);
        }
        return rule;
    }

    @Override
    public List<AuditRule> getRulesByIds(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<AuditRule> rules = auditRuleMapper.selectBatchIds(ids);
        rules.forEach(this::fillDescriptions);
        return rules;
    }

    @Override
    @Transactional
    public AuditRule createRule(AuditRule rule, Long userId) {
        // 检查规则代码唯一性
        LambdaQueryWrapper<AuditRule> checkWrapper = new LambdaQueryWrapper<>();
        checkWrapper.eq(AuditRule::getCode, rule.getCode());
        if (auditRuleMapper.selectCount(checkWrapper) > 0) {
            throw new BizException("规则代码已存在");
        }

        // 验证规则语法
        if (!validateRule(rule.getDroolsContent())) {
            throw new BizException("规则语法无效");
        }

        rule.setCreateUserId(userId);
        rule.setEnabled(1);
        auditRuleMapper.insert(rule);

        // 保存初始版本
        saveVersion(rule.getId(), rule.getDroolsContent(), rule.getSuggestion(), "初始版本", userId);

        return rule;
    }

    @Override
    @Transactional
    public AuditRule updateRule(Long id, AuditRule rule) {
        AuditRule existingRule = auditRuleMapper.selectById(id);
        if (existingRule == null) {
            throw new BizException("规则不存在");
        }

        boolean droolsChanged = !Objects.equals(existingRule.getDroolsContent(), rule.getDroolsContent());
        boolean suggestionChanged = !Objects.equals(existingRule.getSuggestion(), rule.getSuggestion());
        if (droolsChanged) {
            if (!validateRule(rule.getDroolsContent())) {
                throw new BizException("规则语法无效");
            }
        }
        if (droolsChanged || suggestionChanged) {
            saveVersion(id, rule.getDroolsContent(), rule.getSuggestion(), "规则更新", existingRule.getCreateUserId());
        }

        rule.setId(id);
        rule.setCreateUserId(existingRule.getCreateUserId());
        rule.setEnabled(existingRule.getEnabled());
        auditRuleMapper.updateById(rule);

        return auditRuleMapper.selectById(id);
    }

    @Override
    @Transactional
    public void deleteRule(Long id) {
        auditRuleMapper.deleteById(id);
    }

    @Override
    @Transactional
    public void toggleRule(Long id, boolean enabled) {
        if (enabled) {
            AuditRule rule = auditRuleMapper.selectById(id);
            if (rule == null) {
                throw new BizException("规则不存在");
            }
            if (!validateRule(rule.getDroolsContent())) {
                throw new BizException("规则语法无效，无法启用");
            }
        }

        LambdaUpdateWrapper<AuditRule> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.set(AuditRule::getEnabled, enabled ? 1 : 0)
                .eq(AuditRule::getId, id);
        auditRuleMapper.update(null, updateWrapper);
    }

    @Override
    public boolean validateRule(String ruleContent) {
        if (ruleContent == null || ruleContent.isBlank()) {
            return false;
        }
        try {
            SqlAuditTool.validateRules(List.of(toCommonRule(ruleContent)));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void reloadRules() {
        LambdaQueryWrapper<AuditRule> wrapper = new LambdaQueryWrapper<>();
        wrapper.isNull(AuditRule::getProjectId)
                .eq(AuditRule::getEnabled, 1);
        List<AuditRule> rules = auditRuleMapper.selectList(wrapper);
        SqlAuditTool.validateRules(toCommonRules(rules));
    }

    private void saveVersion(Long ruleId, String content, String suggestion, String changeLog, Long userId) {
        // 获取当前最大版本号
        LambdaQueryWrapper<AuditRuleVersion> versionWrapper = new LambdaQueryWrapper<>();
        versionWrapper.eq(AuditRuleVersion::getRuleId, ruleId)
                .orderByDesc(AuditRuleVersion::getVersion)
                .last("LIMIT 1");
        AuditRuleVersion lastVersion = auditRuleVersionMapper.selectOne(versionWrapper);

        int newVersion = (lastVersion == null) ? 1 : lastVersion.getVersion() + 1;

        AuditRuleVersion version = new AuditRuleVersion();
        version.setRuleId(ruleId);
        version.setVersion(newVersion);
        version.setDroolsContent(content);
        version.setSuggestion(suggestion);
        version.setChangeLog(changeLog);
        version.setCreateUserId(userId);
        auditRuleVersionMapper.insert(version);
    }

    private void fillDescriptions(AuditRule rule) {
        // 填充严重级别描述
        rule.setSeverityDescription(getSeverityDescription(rule.getSeverity()));
        // 填充分类描述
        rule.setCategoryDescription(getCategoryDescription(rule.getCategory()));
    }

    private String getSeverityDescription(String severity) {
        return switch (severity) {
            case "critical" -> "致命";
            case "high" -> "高危";
            case "warning" -> "警告";
            case "info" -> "信息";
            default -> severity;
        };
    }

    private String getCategoryDescription(String category) {
        return switch (category) {
            case "SQL_QUALITY" -> "SQL质量";
            case "SECURITY" -> "安全检查";
            case "PERFORMANCE" -> "性能优化";
            default -> category;
        };
    }

    private List<cn.jnu.zhangcs.jsqlint.audit.AuditRule> toCommonRules(List<AuditRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }

        List<cn.jnu.zhangcs.jsqlint.audit.AuditRule> commonRules = new ArrayList<>(rules.size());
        for (AuditRule rule : rules) {
            commonRules.add(toCommonRule(rule));
        }
        return commonRules;
    }

    private cn.jnu.zhangcs.jsqlint.audit.AuditRule toCommonRule(String ruleContent) {
        AuditRule rule = new AuditRule();
        rule.setName("validation");
        rule.setCode("validation");
        rule.setDroolsContent(ruleContent);
        rule.setEnabled(1);
        return toCommonRule(rule);
    }

    private cn.jnu.zhangcs.jsqlint.audit.AuditRule toCommonRule(AuditRule rule) {
        cn.jnu.zhangcs.jsqlint.audit.AuditRule commonRule =
                new cn.jnu.zhangcs.jsqlint.audit.AuditRule();
        commonRule.setId(rule.getId());
        commonRule.setName(rule.getName());
        commonRule.setCode(rule.getCode());
        commonRule.setDescription(rule.getDescription());
        commonRule.setSeverity(rule.getSeverity());
        commonRule.setCategory(rule.getCategory());
        commonRule.setDroolsContent(rule.getDroolsContent());
        commonRule.setSuggestion(rule.getSuggestion());
        commonRule.setEnabled(rule.getEnabled());
        commonRule.setCreateUserId(rule.getCreateUserId());
        commonRule.setCreateTime(rule.getCreateTime());
        commonRule.setUpdateTime(rule.getUpdateTime());
        commonRule.setCreateUserName(rule.getCreateUserName());
        commonRule.setSeverityDescription(rule.getSeverityDescription());
        commonRule.setCategoryDescription(rule.getCategoryDescription());
        return commonRule;
    }
}
