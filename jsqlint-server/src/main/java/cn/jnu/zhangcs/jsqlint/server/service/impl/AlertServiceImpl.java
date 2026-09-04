package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.audit.model.RiskLevel;
import cn.jnu.zhangcs.jsqlint.server.common.enums.AlertRuleType;
import cn.jnu.zhangcs.jsqlint.server.common.enums.AlertStatus;
import cn.jnu.zhangcs.jsqlint.server.common.enums.TaskType;
import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.*;
import cn.jnu.zhangcs.jsqlint.server.mapper.AlertInstanceMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.AlertRuleMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.AuditResultMapper;
import cn.jnu.zhangcs.jsqlint.server.model.dto.AlertRuleDTO;
import cn.jnu.zhangcs.jsqlint.server.model.dto.AlertRuleUpdateDTO;
import cn.jnu.zhangcs.jsqlint.server.service.AlertService;
import cn.jnu.zhangcs.jsqlint.server.service.NotificationService;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectService;
import cn.jnu.zhangcs.jsqlint.server.service.SqlStatementService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class AlertServiceImpl implements AlertService {

    private final AlertRuleMapper alertRuleMapper;

    private final AlertInstanceMapper alertInstanceMapper;

    private final AuditResultMapper auditResultMapper;

    private final SqlStatementService sqlStatementService;

    private final ProjectService projectService;

    private final NotificationService notificationService;

    @Override
    public List<AlertRule> getRules(Long projectId, String ruleType, Boolean enabled) {
        return getRulePage(projectId, ruleType, enabled, 1, Integer.MAX_VALUE).getRecords();
    }

    @Override
    public Page<AlertRule> getRulePage(Long projectId, String ruleType, Boolean enabled, int page, int pageSize) {
        LambdaQueryWrapper<AlertRule> query = Wrappers.lambdaQuery();
        if (projectId != null) {
            query.and(wrapper -> wrapper.isNull(AlertRule::getProjectId).or().eq(AlertRule::getProjectId, projectId));
        }
        if (StringUtils.hasText(ruleType)) {
            query.eq(AlertRule::getRuleType, ruleType);
        }
        if (enabled != null) {
            query.eq(AlertRule::getEnabled, enabled ? 1 : 0);
        }
        query.orderByDesc(AlertRule::getCreateTime);
        return alertRuleMapper.selectPage(new Page<>(page, pageSize), query);
    }

    @Override
    @Transactional
    public AlertRule addRule(AlertRuleDTO dto, Long userId) {
        validateRuleType(dto.getRuleType());
        AlertRule rule = new AlertRule();
        rule.setName(dto.getName());
        rule.setRuleType(dto.getRuleType());
        rule.setProjectId(dto.getProjectId());
        rule.setSeverity(normalizeSeverity(dto.getSeverity(), "warning"));
        rule.setMinSeverity(normalizeSeverity(dto.getMinSeverity(), "warning"));
        rule.setThresholdCount(dto.getThresholdCount() == null ? 1 : dto.getThresholdCount());
        rule.setNotifyProjectMembers(Boolean.FALSE.equals(dto.getNotifyProjectMembers()) ? 0 : 1);
        rule.setNotifyCreator(Boolean.FALSE.equals(dto.getNotifyCreator()) ? 0 : 1);
        rule.setEnabled(1);
        rule.setCreateUserId(userId);
        alertRuleMapper.insert(rule);
        return rule;
    }

    @Override
    @Transactional
    public AlertRule updateRule(Long id, AlertRuleUpdateDTO dto) {
        AlertRule rule = requireRule(id);
        if (StringUtils.hasText(dto.getName())) {
            rule.setName(dto.getName());
        }
        rule.setProjectId(dto.getProjectId());
        if (StringUtils.hasText(dto.getSeverity())) {
            rule.setSeverity(normalizeSeverity(dto.getSeverity(), "warning"));
        }
        if (StringUtils.hasText(dto.getMinSeverity())) {
            rule.setMinSeverity(normalizeSeverity(dto.getMinSeverity(), "warning"));
        }
        if (dto.getThresholdCount() != null) {
            rule.setThresholdCount(dto.getThresholdCount());
        }
        if (dto.getNotifyProjectMembers() != null) {
            rule.setNotifyProjectMembers(dto.getNotifyProjectMembers() ? 1 : 0);
        }
        if (dto.getNotifyCreator() != null) {
            rule.setNotifyCreator(dto.getNotifyCreator() ? 1 : 0);
        }
        if (dto.getEnabled() != null) {
            rule.setEnabled(dto.getEnabled() ? 1 : 0);
        }
        alertRuleMapper.updateById(rule);
        return rule;
    }

    @Override
    @Transactional
    public void deleteRule(Long id) {
        alertRuleMapper.deleteById(id);
    }

    @Override
    @Transactional
    public AlertRule toggleRule(Long id, boolean enabled) {
        AlertRule rule = requireRule(id);
        rule.setEnabled(enabled ? 1 : 0);
        alertRuleMapper.updateById(rule);
        return rule;
    }

    @Override
    public List<AlertInstance> getInstances(Long projectId, String severity, String status) {
        return getInstancePage(projectId, severity, status, 1, Integer.MAX_VALUE).getRecords();
    }

    @Override
    public Page<AlertInstance> getInstancePage(Long projectId, String severity, String status, int page, int pageSize) {
        LambdaQueryWrapper<AlertInstance> query = Wrappers.lambdaQuery();
        if (projectId != null) {
            query.eq(AlertInstance::getProjectId, projectId);
        }
        if (StringUtils.hasText(severity)) {
            query.eq(AlertInstance::getSeverity, severity);
        }
        if (StringUtils.hasText(status)) {
            query.eq(AlertInstance::getStatus, status);
        }
        query.orderByDesc(AlertInstance::getLastTriggeredAt).orderByDesc(AlertInstance::getCreateTime);
        return alertInstanceMapper.selectPage(new Page<>(page, pageSize), query);
    }

    @Override
    @Transactional
    public void updateInstanceStatus(Long id, String status, Long userId) {
        AlertInstance instance = alertInstanceMapper.selectById(id);
        if (instance == null) {
            throw new BizException("告警不存在");
        }
        AlertStatus alertStatus = AlertStatus.from(status);
        instance.setStatus(alertStatus.getCode());
        if (alertStatus == AlertStatus.ACKED) {
            instance.setAckTime(LocalDateTime.now());
            instance.setAssigneeId(userId);
        } else if (alertStatus == AlertStatus.RESOLVED || alertStatus == AlertStatus.IGNORED) {
            instance.setResolvedTime(LocalDateTime.now());
            instance.setAssigneeId(userId);
        }
        alertInstanceMapper.updateById(instance);
    }

    @Override
    @Transactional
    public int evaluateAuditCompleted(Long projectId, Long versionId, Long taskId) {
        List<AlertRule> rules = activeRules(projectId, List.of(
                AlertRuleType.AUDIT_ISSUE_COUNT,
                AlertRuleType.HIGH_RISK_AUDIT_COUNT));
        int triggered = 0;
        for (AlertRule rule : rules) {
            int count = countAuditIssues(projectId, versionId, rule);
            if (count >= safeThreshold(rule)) {
                AlertInstance instance = upsertAuditAlert(rule, projectId, versionId, taskId, count);
                notifyReceivers(rule, projectId, instance);
                triggered++;
            }
        }
        return triggered;
    }

    @Override
    @Transactional
    public int evaluateTaskFailed(ScheduledTask failedTask, String errorMessage) {
        List<AlertRule> rules = activeRules(failedTask.getProjectId(), List.of(AlertRuleType.TASK_FAILED));
        int triggered = 0;
        for (AlertRule rule : rules) {
            AlertInstance instance = upsertTaskFailedAlert(rule, failedTask, errorMessage);
            notifyReceivers(rule, failedTask.getProjectId(), instance);
            triggered++;
        }
        return triggered;
    }

    private List<AlertRule> activeRules(Long projectId, List<AlertRuleType> types) {
        List<String> codes = types.stream().map(AlertRuleType::getCode).toList();
        return alertRuleMapper.selectList(Wrappers.<AlertRule>lambdaQuery()
                .eq(AlertRule::getEnabled, 1)
                .in(AlertRule::getRuleType, codes)
                .and(wrapper -> wrapper.isNull(AlertRule::getProjectId).or().eq(AlertRule::getProjectId, projectId)));
    }

    private int countAuditIssues(Long projectId, Long versionId, AlertRule rule) {
        List<Long> scanIds = scanIds(projectId, versionId);
        if (scanIds.isEmpty()) {
            return 0;
        }

        LambdaQueryWrapper<AuditResult> query = Wrappers.<AuditResult>lambdaQuery()
                .eq(AuditResult::getProjectId, projectId)
                .in(AuditResult::getScanId, scanIds);
        if (AlertRuleType.from(rule.getRuleType()) == AlertRuleType.HIGH_RISK_AUDIT_COUNT) {
            List<String> severities = severitiesAtLeast(rule.getMinSeverity());
            query.in(AuditResult::getSeverity, severities);
        }
        return auditResultMapper.selectCount(query).intValue();
    }

    private List<Long> scanIds(Long projectId, Long versionId) {
        return projectId == null || versionId == null
                ? List.of()
                : sqlStatementService.getByProjectAndVersion(projectId, versionId)
                .stream()
                .map(SqlStatement::getId)
                .filter(Objects::nonNull)
                .toList();
    }

    private AlertInstance upsertAuditAlert(AlertRule rule, Long projectId, Long versionId, Long taskId, int count) {
        Project project = projectService.getById(projectId);
        String projectName = project == null ? "项目" + projectId : project.getName();
        String fingerprint = String.format("AUDIT:%d:%d:%d", rule.getId(), projectId, versionId);
        String title = String.format("%s触发告警: %s", projectName, rule.getName());
        String content = String.format("版本 %d 审核命中 %d 个问题，达到阈值 %d。", versionId, count, safeThreshold(rule));
        return upsertInstance(rule, projectId, versionId, taskId, fingerprint, title, content);
    }

    private AlertInstance upsertTaskFailedAlert(AlertRule rule, ScheduledTask task, String errorMessage) {
        Project project = projectService.getById(task.getProjectId());
        String projectName = project == null ? "项目" + task.getProjectId() : project.getName();
        TaskType type = task.getType() == null ? TaskType.UN_SUPPORT : task.getType();
        String fingerprint = String.format("TASK_FAILED:%d:%d:%d", rule.getId(), task.getProjectId(), task.getId());
        String title = String.format("%s任务失败: %s", projectName, type.getDesc());
        String content = StringUtils.hasText(errorMessage) ? errorMessage : "任务执行失败";
        return upsertInstance(rule, task.getProjectId(), task.getVersionId(), task.getId(), fingerprint, title, content);
    }

    private AlertInstance upsertInstance(AlertRule rule,
                                         Long projectId,
                                         Long versionId,
                                         Long taskId,
                                         String fingerprint,
                                         String title,
                                         String content) {
        AlertInstance instance = alertInstanceMapper.selectOne(Wrappers.<AlertInstance>lambdaQuery()
                .eq(AlertInstance::getFingerprint, fingerprint)
                .last("LIMIT 1"));
        LocalDateTime now = LocalDateTime.now();
        if (instance == null) {
            instance = new AlertInstance();
            instance.setRuleId(rule.getId());
            instance.setProjectId(projectId);
            instance.setVersionId(versionId);
            instance.setTaskId(taskId);
            instance.setTitle(title);
            instance.setContent(content);
            instance.setSeverity(rule.getSeverity());
            instance.setStatus(AlertStatus.OPEN.getCode());
            instance.setFingerprint(fingerprint);
            instance.setTriggerCount(1);
            instance.setFirstTriggeredAt(now);
            instance.setLastTriggeredAt(now);
            alertInstanceMapper.insert(instance);
            return instance;
        }

        instance.setTitle(title);
        instance.setContent(content);
        instance.setSeverity(rule.getSeverity());
        instance.setStatus(AlertStatus.OPEN.getCode());
        instance.setTriggerCount(instance.getTriggerCount() == null ? 1 : instance.getTriggerCount() + 1);
        instance.setLastTriggeredAt(now);
        alertInstanceMapper.updateById(instance);
        return instance;
    }

    private void notifyReceivers(AlertRule rule, Long projectId, AlertInstance instance) {
        for (Long userId : receiverIds(rule, projectId)) {
            notificationService.create(userId, "ALERT_TRIGGERED", instance.getTitle(), instance.getContent());
        }
    }

    private Set<Long> receiverIds(AlertRule rule, Long projectId) {
        Set<Long> userIds = new LinkedHashSet<>();
        Project project = projectService.getById(projectId);
        if (Integer.valueOf(1).equals(rule.getNotifyCreator()) && project != null && project.getCreateUserId() != null) {
            userIds.add(project.getCreateUserId());
        }
        if (Integer.valueOf(1).equals(rule.getNotifyProjectMembers())) {
            List<ProjectMember> members = projectService.getMembers(projectId);
            for (ProjectMember member : members) {
                if (member.getUserId() != null) {
                    userIds.add(member.getUserId());
                }
            }
        }
        return userIds;
    }

    @Override
    public AlertRule getRuleById(Long id) {
        return id == null ? null : alertRuleMapper.selectById(id);
    }

    private AlertRule requireRule(Long id) {
        AlertRule rule = alertRuleMapper.selectById(id);
        if (rule == null) {
            throw new BizException("告警规则不存在");
        }
        return rule;
    }

    private void validateRuleType(String ruleType) {
        for (AlertRuleType type : AlertRuleType.values()) {
            if (type.getCode().equals(ruleType)) {
                return;
            }
        }
        throw new BizException("不支持的告警规则类型");
    }

    private int safeThreshold(AlertRule rule) {
        return rule.getThresholdCount() == null || rule.getThresholdCount() < 1 ? 1 : rule.getThresholdCount();
    }

    private String normalizeSeverity(String severity, String fallback) {
        if (!StringUtils.hasText(severity)) {
            return fallback;
        }
        return RiskLevel.from(severity).getCode();
    }

    private List<String> severitiesAtLeast(String severity) {
        int floor = severityRank(normalizeSeverity(severity, "warning"));
        return Stream.of("info", "warning", "high", "critical")
                .filter(item -> severityRank(item) >= floor)
                .toList();
    }

    private int severityRank(String severity) {
        return switch (severity) {
            case "critical" -> 4;
            case "high" -> 3;
            case "warning" -> 2;
            default -> 1;
        };
    }
}
