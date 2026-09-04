package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.enums.AlertStatus;
import cn.jnu.zhangcs.jsqlint.server.common.enums.TaskType;
import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.AlertInstance;
import cn.jnu.zhangcs.jsqlint.server.entity.AlertRule;
import cn.jnu.zhangcs.jsqlint.server.entity.Project;
import cn.jnu.zhangcs.jsqlint.server.entity.ProjectMember;
import cn.jnu.zhangcs.jsqlint.server.entity.ScheduledTask;
import cn.jnu.zhangcs.jsqlint.server.entity.SqlStatement;
import cn.jnu.zhangcs.jsqlint.server.mapper.AlertInstanceMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.AlertRuleMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.AuditResultMapper;
import cn.jnu.zhangcs.jsqlint.server.model.dto.AlertRuleDTO;
import cn.jnu.zhangcs.jsqlint.server.model.dto.AlertRuleUpdateDTO;
import cn.jnu.zhangcs.jsqlint.server.service.NotificationService;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectService;
import cn.jnu.zhangcs.jsqlint.server.service.SqlStatementService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AlertServiceImpl} 单元测试。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class AlertServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        // wrapper.eq(...) 需要实体列缓存，纯单元测试下需手动初始化
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AlertRule.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AlertInstance.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), cn.jnu.zhangcs.jsqlint.server.entity.AuditResult.class);
    }

    @Mock
    private AlertRuleMapper alertRuleMapper;

    @Mock
    private AlertInstanceMapper alertInstanceMapper;

    @Mock
    private AuditResultMapper auditResultMapper;

    @Mock
    private SqlStatementService sqlStatementService;

    @Mock
    private ProjectService projectService;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private AlertServiceImpl alertService;

    private AlertRule auditCountRule() {
        AlertRule rule = new AlertRule();
        rule.setId(1L);
        rule.setName("审核问题过多");
        rule.setRuleType("AUDIT_ISSUE_COUNT");
        rule.setSeverity("warning");
        rule.setThresholdCount(10);
        rule.setNotifyCreator(1);
        rule.setNotifyProjectMembers(0);
        rule.setEnabled(1);
        return rule;
    }

    @Test
    void getRules_delegatesToPagedQuery() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<AlertRule> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, Integer.MAX_VALUE);
        page.setRecords(List.of(auditCountRule()));
        when(alertRuleMapper.selectPage(any(), any())).thenReturn(page);

        assertThat(alertService.getRules(1L, "AUDIT_ISSUE_COUNT", true)).hasSize(1);
    }

    @Test
    void addRule_fillsDefaultsAndInserts() {
        AlertRuleDTO dto = new AlertRuleDTO();
        dto.setName("审核问题过多");
        dto.setRuleType("AUDIT_ISSUE_COUNT");
        dto.setProjectId(1L);

        AlertRule result = alertService.addRule(dto, 10L);

        ArgumentCaptor<AlertRule> captor = ArgumentCaptor.forClass(AlertRule.class);
        verify(alertRuleMapper).insert(captor.capture());
        AlertRule rule = captor.getValue();
        assertThat(rule.getName()).isEqualTo("审核问题过多");
        // 未填字段取默认值
        assertThat(rule.getSeverity()).isEqualTo("warning");
        assertThat(rule.getMinSeverity()).isEqualTo("warning");
        assertThat(rule.getThresholdCount()).isEqualTo(1);
        assertThat(rule.getNotifyProjectMembers()).isEqualTo(1);
        assertThat(rule.getNotifyCreator()).isEqualTo(1);
        assertThat(rule.getEnabled()).isEqualTo(1);
        assertThat(rule.getCreateUserId()).isEqualTo(10L);
        assertThat(result).isSameAs(rule);
    }

    @Test
    void addRule_unsupportedType_throwsException() {
        AlertRuleDTO dto = new AlertRuleDTO();
        dto.setRuleType("UNKNOWN_TYPE");

        assertThatThrownBy(() -> alertService.addRule(dto, 10L))
                .isInstanceOf(BizException.class)
                .hasMessage("不支持的告警规则类型");
    }

    @Test
    void updateRule_notFound_throwsException() {
        when(alertRuleMapper.selectById(9L)).thenReturn(null);

        assertThatThrownBy(() -> alertService.updateRule(9L, new AlertRuleUpdateDTO()))
                .isInstanceOf(BizException.class)
                .hasMessage("告警规则不存在");
    }

    @Test
    void updateRule_updatesOnlyProvidedFields() {
        AlertRule rule = auditCountRule();
        when(alertRuleMapper.selectById(1L)).thenReturn(rule);
        AlertRuleUpdateDTO dto = new AlertRuleUpdateDTO();
        dto.setName("新名称");
        dto.setThresholdCount(20);
        dto.setEnabled(false);

        alertService.updateRule(1L, dto);

        verify(alertRuleMapper).updateById(rule);
        assertThat(rule.getName()).isEqualTo("新名称");
        assertThat(rule.getThresholdCount()).isEqualTo(20);
        assertThat(rule.getEnabled()).isZero();
        // 未传入字段保持不变
        assertThat(rule.getSeverity()).isEqualTo("warning");
    }

    @Test
    void deleteRule_delegatesToMapper() {
        alertService.deleteRule(1L);

        verify(alertRuleMapper).deleteById(1L);
    }

    @Test
    void toggleRule_updatesEnabledStatus() {
        AlertRule rule = auditCountRule();
        when(alertRuleMapper.selectById(1L)).thenReturn(rule);

        alertService.toggleRule(1L, false);

        assertThat(rule.getEnabled()).isZero();
        verify(alertRuleMapper).updateById(rule);
    }

    @Test
    void getInstances_delegatesToPagedQuery() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<AlertInstance> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, Integer.MAX_VALUE);
        page.setRecords(List.of(new AlertInstance()));
        when(alertInstanceMapper.selectPage(any(), any())).thenReturn(page);

        assertThat(alertService.getInstances(1L, "warning", "OPEN")).hasSize(1);
    }

    @Test
    void updateInstanceStatus_notFound_throwsException() {
        when(alertInstanceMapper.selectById(9L)).thenReturn(null);

        assertThatThrownBy(() -> alertService.updateInstanceStatus(9L, "ACKED", 10L))
                .isInstanceOf(BizException.class)
                .hasMessage("告警不存在");
    }

    @Test
    void updateInstanceStatus_ack_recordsAckUserAndTime() {
        AlertInstance instance = new AlertInstance();
        when(alertInstanceMapper.selectById(1L)).thenReturn(instance);

        alertService.updateInstanceStatus(1L, "ACKED", 10L);

        assertThat(instance.getStatus()).isEqualTo(AlertStatus.ACKED.getCode());
        assertThat(instance.getAckTime()).isNotNull();
        assertThat(instance.getAssigneeId()).isEqualTo(10L);
        verify(alertInstanceMapper).updateById(instance);
    }

    @Test
    void updateInstanceStatus_resolve_recordsResolvedTime() {
        AlertInstance instance = new AlertInstance();
        when(alertInstanceMapper.selectById(1L)).thenReturn(instance);

        alertService.updateInstanceStatus(1L, "RESOLVED", 10L);

        assertThat(instance.getStatus()).isEqualTo(AlertStatus.RESOLVED.getCode());
        assertThat(instance.getResolvedTime()).isNotNull();
        assertThat(instance.getAssigneeId()).isEqualTo(10L);
    }

    @Test
    void evaluateAuditCompleted_thresholdReached_triggersAlertAndNotifies() {
        AlertRule rule = auditCountRule();
        when(alertRuleMapper.selectList(any())).thenReturn(List.of(rule));
        SqlStatement statement = new SqlStatement();
        statement.setId(100L);
        when(sqlStatementService.getByProjectAndVersion(1L, 2L)).thenReturn(List.of(statement));
        when(auditResultMapper.selectCount(any())).thenReturn(15L);
        when(alertInstanceMapper.selectOne(any())).thenReturn(null);
        Project project = new Project();
        project.setCreateUserId(10L);
        when(projectService.getById(1L)).thenReturn(project);

        int triggered = alertService.evaluateAuditCompleted(1L, 2L, 3L);

        assertThat(triggered).isEqualTo(1);
        ArgumentCaptor<AlertInstance> captor = ArgumentCaptor.forClass(AlertInstance.class);
        verify(alertInstanceMapper).insert(captor.capture());
        AlertInstance instance = captor.getValue();
        assertThat(instance.getRuleId()).isEqualTo(1L);
        assertThat(instance.getProjectId()).isEqualTo(1L);
        assertThat(instance.getVersionId()).isEqualTo(2L);
        assertThat(instance.getStatus()).isEqualTo(AlertStatus.OPEN.getCode());
        assertThat(instance.getTriggerCount()).isEqualTo(1);
        assertThat(instance.getTitle()).contains("审核问题过多");
        assertThat(instance.getContent()).contains("15");
        // notifyCreator=1 → 通知创建者
        verify(notificationService).create(eq(10L), eq("ALERT_TRIGGERED"), anyString(), anyString());
    }

    @Test
    void evaluateAuditCompleted_belowThreshold_noTrigger() {
        AlertRule rule = auditCountRule();
        when(alertRuleMapper.selectList(any())).thenReturn(List.of(rule));
        SqlStatement statement = new SqlStatement();
        statement.setId(100L);
        when(sqlStatementService.getByProjectAndVersion(1L, 2L)).thenReturn(List.of(statement));
        when(auditResultMapper.selectCount(any())).thenReturn(5L);

        int triggered = alertService.evaluateAuditCompleted(1L, 2L, 3L);

        assertThat(triggered).isZero();
        verify(alertInstanceMapper, never()).insert(any(AlertInstance.class));
    }

    @Test
    void evaluateAuditCompleted_noSqlStatements_noTrigger() {
        when(alertRuleMapper.selectList(any())).thenReturn(List.of(auditCountRule()));
        when(sqlStatementService.getByProjectAndVersion(1L, 2L)).thenReturn(List.of());

        int triggered = alertService.evaluateAuditCompleted(1L, 2L, 3L);

        assertThat(triggered).isZero();
        verify(auditResultMapper, never()).selectCount(any());
    }

    @Test
    void evaluateAuditCompleted_existingInstance_incrementsTriggerCount() {
        AlertRule rule = auditCountRule();
        when(alertRuleMapper.selectList(any())).thenReturn(List.of(rule));
        SqlStatement statement = new SqlStatement();
        statement.setId(100L);
        when(sqlStatementService.getByProjectAndVersion(1L, 2L)).thenReturn(List.of(statement));
        when(auditResultMapper.selectCount(any())).thenReturn(15L);
        AlertInstance existing = new AlertInstance();
        existing.setTriggerCount(2);
        when(alertInstanceMapper.selectOne(any())).thenReturn(existing);
        when(projectService.getById(1L)).thenReturn(null);

        int triggered = alertService.evaluateAuditCompleted(1L, 2L, 3L);

        assertThat(triggered).isEqualTo(1);
        verify(alertInstanceMapper, never()).insert(any(AlertInstance.class));
        verify(alertInstanceMapper).updateById(existing);
        assertThat(existing.getTriggerCount()).isEqualTo(3);
        assertThat(existing.getLastTriggeredAt()).isNotNull();
    }

    @Test
    void evaluateAuditCompleted_notifiesProjectMembers() {
        AlertRule rule = auditCountRule();
        rule.setNotifyCreator(0);
        rule.setNotifyProjectMembers(1);
        when(alertRuleMapper.selectList(any())).thenReturn(List.of(rule));
        SqlStatement statement = new SqlStatement();
        statement.setId(100L);
        when(sqlStatementService.getByProjectAndVersion(1L, 2L)).thenReturn(List.of(statement));
        when(auditResultMapper.selectCount(any())).thenReturn(15L);
        when(alertInstanceMapper.selectOne(any())).thenReturn(null);
        Project project = new Project();
        when(projectService.getById(1L)).thenReturn(project);
        ProjectMember member = new ProjectMember();
        member.setUserId(20L);
        when(projectService.getMembers(1L)).thenReturn(List.of(member));

        alertService.evaluateAuditCompleted(1L, 2L, 3L);

        verify(notificationService).create(eq(20L), eq("ALERT_TRIGGERED"), anyString(), anyString());
    }

    @Test
    void evaluateAuditCompleted_highRiskRule_filtersByMinSeverity() {
        AlertRule rule = auditCountRule();
        rule.setRuleType("HIGH_RISK_AUDIT_COUNT");
        rule.setMinSeverity("high");
        when(alertRuleMapper.selectList(any())).thenReturn(List.of(rule));
        SqlStatement statement = new SqlStatement();
        statement.setId(100L);
        when(sqlStatementService.getByProjectAndVersion(1L, 2L)).thenReturn(List.of(statement));
        when(auditResultMapper.selectCount(any())).thenReturn(15L);
        when(alertInstanceMapper.selectOne(any())).thenReturn(null);
        when(projectService.getById(1L)).thenReturn(null);

        int triggered = alertService.evaluateAuditCompleted(1L, 2L, 3L);

        assertThat(triggered).isEqualTo(1);
    }

    @Test
    void evaluateTaskFailed_triggersAlertAndNotifies() {
        AlertRule rule = auditCountRule();
        rule.setRuleType("TASK_FAILED");
        when(alertRuleMapper.selectList(any())).thenReturn(List.of(rule));
        when(alertInstanceMapper.selectOne(any())).thenReturn(null);
        ScheduledTask task = new ScheduledTask();
        task.setId(30L);
        task.setProjectId(1L);
        task.setVersionId(2L);
        task.setType(TaskType.SYNC);
        Project project = new Project();
        project.setName("订单系统");
        when(projectService.getById(1L)).thenReturn(project);

        int triggered = alertService.evaluateTaskFailed(task, "同步超时");

        assertThat(triggered).isEqualTo(1);
        ArgumentCaptor<AlertInstance> captor = ArgumentCaptor.forClass(AlertInstance.class);
        verify(alertInstanceMapper).insert(captor.capture());
        assertThat(captor.getValue().getTitle()).contains("订单系统").contains("同步");
        assertThat(captor.getValue().getContent()).isEqualTo("同步超时");
        assertThat(captor.getValue().getTaskId()).isEqualTo(30L);
    }

    @Test
    void evaluateTaskFailed_noActiveRules_returnsZero() {
        when(alertRuleMapper.selectList(any())).thenReturn(List.of());

        ScheduledTask task = new ScheduledTask();
        task.setProjectId(1L);

        assertThat(alertService.evaluateTaskFailed(task, null)).isZero();
        verify(alertInstanceMapper, never()).insert(any(AlertInstance.class));
    }

    @Test
    void evaluateTaskFailed_noErrorMessage_usesDefaultContent() {
        AlertRule rule = auditCountRule();
        rule.setRuleType("TASK_FAILED");
        when(alertRuleMapper.selectList(any())).thenReturn(List.of(rule));
        when(alertInstanceMapper.selectOne(any())).thenReturn(null);
        ScheduledTask task = new ScheduledTask();
        task.setId(30L);
        task.setProjectId(1L);
        task.setType(null);
        when(projectService.getById(1L)).thenReturn(null);

        alertService.evaluateTaskFailed(task, null);

        ArgumentCaptor<AlertInstance> captor = ArgumentCaptor.forClass(AlertInstance.class);
        verify(alertInstanceMapper).insert(captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo("任务执行失败");
        // 项目不存在时标题回退为 "项目{id}"，任务类型为空取 UN_SUPPORT
        assertThat(captor.getValue().getTitle()).contains("项目1");
    }
}
