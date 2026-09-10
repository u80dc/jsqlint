package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.audit.SqlAuditTool;
import cn.jnu.zhangcs.jsqlint.server.common.annotation.ProjectLog;
import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.AuditRule;
import cn.jnu.zhangcs.jsqlint.server.entity.AuditRuleVersion;
import cn.jnu.zhangcs.jsqlint.server.entity.Project;
import cn.jnu.zhangcs.jsqlint.server.mapper.AuditRuleMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.AuditRuleVersionMapper;
import cn.jnu.zhangcs.jsqlint.server.model.dto.ProjectAuditRuleDTO;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectAuditRuleService;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ProjectAuditRuleServiceImpl implements ProjectAuditRuleService {

    private final AuditRuleMapper auditRuleMapper;
    private final AuditRuleVersionMapper auditRuleVersionMapper;
    private final ProjectService projectService;

    @Override
    public List<AuditRule> listRules(Long projectId, int page, int pageSize, String name, String category, Boolean enabled) {
        ensureProjectExists(projectId);

        Page<AuditRule> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<AuditRule> wrapper = baseProjectRuleWrapper(projectId, name, category, enabled);
        wrapper.orderByDesc(AuditRule::getCreateTime);

        Page<AuditRule> result = auditRuleMapper.selectPage(pageParam, wrapper);
        result.getRecords().forEach(this::fillDescriptions);
        return result.getRecords();
    }

    @Override
    public long countRules(Long projectId, String name, String category, Boolean enabled) {
        ensureProjectExists(projectId);
        return auditRuleMapper.selectCount(baseProjectRuleWrapper(projectId, name, category, enabled));
    }

    @Override
    public AuditRule getRule(Long projectId, Long id) {
        ensureProjectExists(projectId);
        AuditRule rule = requireProjectRule(projectId, id);
        fillDescriptions(rule);
        return rule;
    }

    @Override
    @Transactional
    @ProjectLog(
            projectId = "#projectId",
            operationType = "PROJECT_RULE_ADD",
            message = "添加项目审核规则: #{#result.name}",
            relatedId = "#result.id",
            includeResult = true)
    public AuditRule createRule(Long projectId, ProjectAuditRuleDTO dto, Long userId) {
        ensureProjectExists(projectId);
        requireCreateFields(dto);
        ensureEffectiveCodeUnique(projectId, null, dto.getCode());
        validateDrools(dto.getDroolsContent());

        AuditRule rule = new AuditRule();
        rule.setProjectId(projectId);
        rule.setName(dto.getName());
        rule.setCode(dto.getCode());
        rule.setDescription(dto.getDescription());
        rule.setSeverity(dto.getSeverity());
        rule.setCategory(dto.getCategory());
        rule.setDialect(dto.getDialect());
        rule.setDroolsContent(dto.getDroolsContent());
        rule.setSuggestion(dto.getSuggestion());
        rule.setEnabled(Boolean.FALSE.equals(dto.getEnabled()) ? 0 : 1);
        rule.setCreateUserId(userId);
        auditRuleMapper.insert(rule);

        saveVersion(rule.getId(), rule.getDroolsContent(), rule.getSuggestion(), "项目规则初始版本", userId);
        fillDescriptions(rule);
        return rule;
    }

    @Override
    @Transactional
    @ProjectLog(
            projectId = "#projectId",
            operationType = "PROJECT_RULE_UPDATE",
            message = "更新项目审核规则: #{#result.name}",
            relatedId = "#result.id",
            includeResult = true)
    public AuditRule updateRule(Long projectId, Long id, ProjectAuditRuleDTO dto, Long userId) {
        ensureProjectExists(projectId);
        AuditRule rule = requireProjectRule(projectId, id);
        String existingSuggestion = rule.getSuggestion();
        boolean droolsChanged = hasText(dto.getDroolsContent()) && !dto.getDroolsContent().equals(rule.getDroolsContent());

        if (hasText(dto.getName())) {
            rule.setName(dto.getName());
        }
        if (hasText(dto.getCode()) && !dto.getCode().equals(rule.getCode())) {
            ensureEffectiveCodeUnique(projectId, id, dto.getCode());
            rule.setCode(dto.getCode());
        }
        if (dto.getDescription() != null) {
            rule.setDescription(dto.getDescription());
        }
        if (hasText(dto.getSeverity())) {
            rule.setSeverity(dto.getSeverity());
        }
        if (hasText(dto.getCategory())) {
            rule.setCategory(dto.getCategory());
        }
        // 方言允许显式清空（传空串=改回通用规则），仅在字段出现时更新
        if (dto.getDialect() != null) {
            rule.setDialect(dto.getDialect().isBlank() ? null : dto.getDialect());
        }
        if (dto.getEnabled() != null) {
            rule.setEnabled(dto.getEnabled() ? 1 : 0);
        }
        if (dto.getSuggestion() != null) {
            rule.setSuggestion(dto.getSuggestion());
        }
        if (droolsChanged) {
            validateDrools(dto.getDroolsContent());
            rule.setDroolsContent(dto.getDroolsContent());
        }
        if (droolsChanged || (dto.getSuggestion() != null && !Objects.equals(existingSuggestion, dto.getSuggestion()))) {
            saveVersion(id, rule.getDroolsContent(), rule.getSuggestion(), "项目规则更新", userId);
        }

        auditRuleMapper.updateById(rule);
        AuditRule updated = requireProjectRule(projectId, id);
        fillDescriptions(updated);
        return updated;
    }

    @Override
    @Transactional
    @ProjectLog(
            projectId = "#projectId",
            operationType = "PROJECT_RULE_REMOVE",
            message = "删除项目审核规则: #{#id}",
            relatedId = "#id")
    public void deleteRule(Long projectId, Long id) {
        ensureProjectExists(projectId);
        requireProjectRule(projectId, id);
        auditRuleMapper.deleteById(id);
    }

    @Override
    @Transactional
    @ProjectLog(
            projectId = "#projectId",
            operationType = "PROJECT_RULE_UPDATE",
            message = "#{#enabled ? '启用项目审核规则: ' : '禁用项目审核规则: '}#{#result.name}",
            relatedId = "#result.id",
            includeResult = true)
    public AuditRule toggleRule(Long projectId, Long id, boolean enabled, Long userId) {
        ensureProjectExists(projectId);
        AuditRule rule = requireProjectRule(projectId, id);
        if (enabled) {
            validateDrools(rule.getDroolsContent());
        }
        rule.setEnabled(enabled ? 1 : 0);
        auditRuleMapper.updateById(rule);

        AuditRule updated = requireProjectRule(projectId, id);
        fillDescriptions(updated);
        return updated;
    }

    private LambdaQueryWrapper<AuditRule> baseProjectRuleWrapper(
            Long projectId,
            String name,
            String category,
            Boolean enabled) {
        LambdaQueryWrapper<AuditRule> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AuditRule::getProjectId, projectId);
        if (hasText(name)) {
            wrapper.like(AuditRule::getName, name);
        }
        if (hasText(category)) {
            wrapper.eq(AuditRule::getCategory, category);
        }
        if (enabled != null) {
            wrapper.eq(AuditRule::getEnabled, enabled ? 1 : 0);
        }
        return wrapper;
    }

    private void ensureProjectExists(Long projectId) {
        Project project = projectService.getById(projectId);
        if (project == null) {
            throw new BizException("项目不存在");
        }
    }

    private AuditRule requireProjectRule(Long projectId, Long id) {
        AuditRule rule = auditRuleMapper.selectOne(new LambdaQueryWrapper<AuditRule>()
                .eq(AuditRule::getId, id)
                .eq(AuditRule::getProjectId, projectId));
        if (rule == null) {
            throw new BizException("项目审核规则不存在");
        }
        return rule;
    }

    private void requireCreateFields(ProjectAuditRuleDTO dto) {
        if (dto == null) {
            throw new BizException("规则信息不能为空");
        }
        if (!hasText(dto.getName())) {
            throw new BizException("规则名称不能为空");
        }
        if (!hasText(dto.getCode())) {
            throw new BizException("规则代码不能为空");
        }
        if (!hasText(dto.getSeverity())) {
            throw new BizException("严重级别不能为空");
        }
        if (!hasText(dto.getCategory())) {
            throw new BizException("规则分类不能为空");
        }
        if (!hasText(dto.getDroolsContent())) {
            throw new BizException("Drools规则内容不能为空");
        }
    }

    private void ensureEffectiveCodeUnique(Long projectId, Long currentRuleId, String code) {
        if (!hasText(code)) {
            return;
        }
        LambdaQueryWrapper<AuditRule> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AuditRule::getCode, code);
        if (currentRuleId != null) {
            wrapper.ne(AuditRule::getId, currentRuleId);
        }
        if (auditRuleMapper.selectCount(wrapper) > 0) {
            throw new BizException("规则代码已存在");
        }
    }

    private void validateDrools(String droolsContent) {
        if (!hasText(droolsContent)) {
            throw new BizException("Drools规则内容不能为空");
        }
        try {
            SqlAuditTool.validateRules(List.of(toCommonRule(droolsContent)));
        } catch (Exception e) {
            throw new BizException("规则语法无效", e);
        }
    }

    private void saveVersion(Long ruleId, String content, String suggestion, String changeLog, Long userId) {
        LambdaQueryWrapper<AuditRuleVersion> versionWrapper = new LambdaQueryWrapper<>();
        versionWrapper.eq(AuditRuleVersion::getRuleId, ruleId)
                .orderByDesc(AuditRuleVersion::getVersion)
                .last("LIMIT 1");
        AuditRuleVersion lastVersion = auditRuleVersionMapper.selectOne(versionWrapper);

        AuditRuleVersion version = new AuditRuleVersion();
        version.setRuleId(ruleId);
        version.setVersion(lastVersion == null ? 1 : lastVersion.getVersion() + 1);
        version.setDroolsContent(content);
        version.setSuggestion(suggestion);
        version.setChangeLog(changeLog);
        version.setCreateUserId(userId);
        auditRuleVersionMapper.insert(version);
    }

    private void fillDescriptions(AuditRule rule) {
        rule.setSeverityDescription(getSeverityDescription(rule.getSeverity()));
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

    private cn.jnu.zhangcs.jsqlint.audit.AuditRule toCommonRule(String ruleContent) {
        cn.jnu.zhangcs.jsqlint.audit.AuditRule rule =
                new cn.jnu.zhangcs.jsqlint.audit.AuditRule();
        rule.setName("validation");
        rule.setCode("validation");
        rule.setDroolsContent(ruleContent);
        rule.setEnabled(1);
        return rule;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
