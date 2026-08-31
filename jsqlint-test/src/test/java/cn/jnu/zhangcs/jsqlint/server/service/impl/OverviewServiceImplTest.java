package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.enums.TaskStatus;
import cn.jnu.zhangcs.jsqlint.server.common.enums.TaskType;
import cn.jnu.zhangcs.jsqlint.server.entity.AuditResult;
import cn.jnu.zhangcs.jsqlint.server.entity.Project;
import cn.jnu.zhangcs.jsqlint.server.entity.ProjectVersion;
import cn.jnu.zhangcs.jsqlint.server.entity.ScheduledTask;
import cn.jnu.zhangcs.jsqlint.server.entity.SqlStatement;
import cn.jnu.zhangcs.jsqlint.server.model.vo.OverviewCurrentTaskVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.OverviewRecentProjectVO;
import cn.jnu.zhangcs.jsqlint.server.mapper.AuditResultMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.ProjectVersionMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.ScheduledTaskMapper;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectService;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectVersionService;
import cn.jnu.zhangcs.jsqlint.server.service.SqlStatementService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OverviewServiceImpl} 单元测试。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class OverviewServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        // wrapper.eq/in/orderBy(...) 需要实体列缓存，纯单元测试下需手动初始化
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ProjectVersion.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ScheduledTask.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AuditResult.class);
    }

    @Mock
    private ProjectService projectService;

    @Mock
    private ProjectVersionMapper projectVersionMapper;

    @Mock
    private ScheduledTaskMapper scheduledTaskMapper;

    @Mock
    private ProjectVersionService projectVersionService;

    @Mock
    private SqlStatementService sqlStatementService;

    @Mock
    private AuditResultMapper auditResultMapper;

    @Mock
    private cn.jnu.zhangcs.jsqlint.server.mapper.AuditRuleMapper auditRuleMapper;

    @Mock
    private cn.jnu.zhangcs.jsqlint.server.service.ProjectOverviewService projectOverviewService;

    @InjectMocks
    private OverviewServiceImpl overviewService;

    private Project project(long id, String name) {
        Project project = new Project();
        project.setId(id);
        project.setName(name);
        return project;
    }

    private ProjectVersion version(long id, long projectId, Integer overallStatus) {
        ProjectVersion version = new ProjectVersion();
        version.setId(id);
        version.setProjectId(projectId);
        version.setRef("main");
        version.setCommitId("abc123");
        version.setCommitMessage("提交信息");
        version.setOverallStatus(overallStatus);
        version.setOperationTime(LocalDateTime.of(2026, 1, 1, 10, 0));
        return version;
    }

    private SqlStatement statement(long id) {
        SqlStatement statement = new SqlStatement();
        statement.setId(id);
        statement.setCreateTime(LocalDate.now().atTime(10, 0));
        return statement;
    }

    private AuditResult auditResult(long scanId, String severity) {
        AuditResult result = new AuditResult();
        result.setScanId(scanId);
        result.setSeverity(severity);
        result.setCreateTime(LocalDate.now().atTime(10, 0));
        return result;
    }

    @Test
    void getProjectSummary_noVisibleProjects_returnsEmptySummary() {
        var vo = overviewService.getProjectSummary(null);

        assertThat(vo.getTotalProjects()).isZero();
        assertThat(vo.getSafeSqlCount()).isZero();
        assertThat(vo.getWarningSqlCount()).isZero();
        assertThat(vo.getDangerSqlCount()).isZero();
    }

    @Test
    void getProjectSummary_dedupesProblemSqlBySeverity() {
        when(projectService.getByUser(1L)).thenReturn(List.of(project(1L, "订单系统")));
        when(projectVersionService.getLast(1L)).thenReturn(version(2L, 1L, 2));
        when(sqlStatementService.getByProjectAndVersion(1L, 2L))
                .thenReturn(List.of(statement(100L), statement(101L)));
        // scanId=100 命中两条危险规则只计 1 条；scanId=101 重复 warning 与 null 严重级别不计
        when(auditResultMapper.selectList(any())).thenReturn(List.of(
                auditResult(100L, "high"),
                auditResult(100L, "critical"),
                auditResult(101L, "warning"),
                auditResult(101L, "warning"),
                auditResult(101L, null)));

        var vo = overviewService.getProjectSummary(1L);

        assertThat(vo.getTotalProjects()).isEqualTo(1);
        assertThat(vo.getDangerSqlCount()).isEqualTo(1);
        assertThat(vo.getWarningSqlCount()).isEqualTo(1);
        assertThat(vo.getSafeSqlCount()).isZero();
    }

    @Test
    void getProjectSummary_noVersion_skipsCounting() {
        when(projectService.getByUser(1L)).thenReturn(List.of(project(1L, "订单系统")));
        when(projectVersionService.getLast(1L)).thenReturn(null);

        var vo = overviewService.getProjectSummary(1L);

        assertThat(vo.getTotalProjects()).isEqualTo(1);
        assertThat(vo.getDangerSqlCount()).isZero();
        assertThat(vo.getWarningSqlCount()).isZero();
        verify(auditResultMapper, never()).selectList(any());
    }

    @Test
    void listRecentProjects_noVisibleProjects_returnsEmptyList() {
        assertThat(overviewService.listRecentProjects(null)).isEmpty();
    }

    @Test
    void listRecentProjects_fillsProjectNameAndStatusDescription() {
        when(projectService.getByUser(1L)).thenReturn(List.of(project(1L, "订单系统")));
        when(projectVersionMapper.selectList(any()))
                .thenReturn(List.of(version(5L, 1L, 2)));

        List<OverviewRecentProjectVO> result = overviewService.listRecentProjects(1L);

        assertThat(result).hasSize(1);
        var vo = result.get(0);
        assertThat(vo.getProjectId()).isEqualTo(1L);
        assertThat(vo.getProjectName()).isEqualTo("订单系统");
        assertThat(vo.getVersionId()).isEqualTo(5L);
        assertThat(vo.getRef()).isEqualTo("main");
        assertThat(vo.getCommitId()).isEqualTo("abc123");
        assertThat(vo.getStatus()).isEqualTo(2);
        assertThat(vo.getStatusDescription()).isEqualTo("执行成功");
        assertThat(vo.getAnalysisTime()).isNotNull();
    }

    @Test
    void getAnalysisTrend_noVisibleProjects_returnsEmptyBuckets() {
        var week = overviewService.getAnalysisTrend(null, "week");
        assertThat(week.getPeriod()).isEqualTo("week");
        assertThat(week.getLabels()).containsExactly("周一", "周二", "周三", "周四", "周五", "周六", "周日");
        assertThat(week.getSafe()).containsOnly(0);
        assertThat(week.getWarning()).containsOnly(0);
        assertThat(week.getDanger()).containsOnly(0);

        var month = overviewService.getAnalysisTrend(null, "MONTH");
        assertThat(month.getLabels()).hasSize(4);

        var year = overviewService.getAnalysisTrend(null, "year");
        assertThat(year.getLabels()).hasSize(12);
        assertThat(year.getLabels().get(0)).isEqualTo("1月");
    }

    @Test
    void getAnalysisTrend_countsSafeWarningDangerByTimeBucket() {
        when(projectService.getByUser(1L)).thenReturn(List.of(project(1L, "订单系统")));
        when(projectVersionService.getLast(1L)).thenReturn(version(2L, 1L, 2));
        when(sqlStatementService.getByProjectAndVersion(1L, 2L))
                .thenReturn(List.of(statement(1L), statement(2L), statement(3L)));
        // danger 去重后 1 条，warning 1 条，未知严重级别不计
        when(auditResultMapper.selectList(any())).thenReturn(List.of(
                auditResult(1L, "critical"),
                auditResult(2L, "warning"),
                auditResult(1L, "high"),
                auditResult(3L, "unknown")));

        var vo = overviewService.getAnalysisTrend(1L, "week");

        assertThat(vo.getLabels()).hasSize(7);
        // 归入今天对应的桶
        int todayIndex = LocalDate.now().getDayOfWeek().getValue() - 1;
        assertThat(vo.getDanger().get(todayIndex)).isEqualTo(1);
        assertThat(vo.getWarning().get(todayIndex)).isEqualTo(1);
        // safe = total(3) - danger(1) - warning(1)
        assertThat(vo.getSafe().get(todayIndex)).isEqualTo(1);
        int dangerSum = vo.getDanger().stream().mapToInt(Integer::intValue).sum();
        assertThat(dangerSum).isEqualTo(1);
    }

    @Test
    void listCurrentTasks_noVisibleProjects_returnsEmptyList() {
        assertThat(overviewService.listCurrentTasks(null)).isEmpty();
    }

    @Test
    void listCurrentTasks_fillsProjectNameAndDescription() {
        when(projectService.getByUser(1L)).thenReturn(List.of(project(1L, "订单系统")));
        ScheduledTask task = new ScheduledTask();
        task.setId(30L);
        task.setProjectId(1L);
        task.setVersionId(2L);
        task.setType(TaskType.SYNC);
        task.setStatus(TaskStatus.RUNNING);
        task.setNextExecTime(1000L);
        task.setRetryCount(1);
        task.setMaxRetries(3);
        when(scheduledTaskMapper.selectList(any())).thenReturn(List.of(task));

        List<OverviewCurrentTaskVO> result = overviewService.listCurrentTasks(1L);

        assertThat(result).hasSize(1);
        var vo = result.get(0);
        assertThat(vo.getId()).isEqualTo(30L);
        assertThat(vo.getProjectName()).isEqualTo("订单系统");
        assertThat(vo.getType()).isEqualTo(TaskType.SYNC.getCode());
        assertThat(vo.getStatus()).isEqualTo(TaskStatus.RUNNING.getCode());
        assertThat(vo.getStatusDescription()).isEqualTo(TaskStatus.RUNNING.getDesc());
        assertThat(vo.getNextExecTime()).isEqualTo(1000L);
    }

    @Test
    void listCurrentTasks_sortedByNextExecTimeAscending() {
        when(projectService.getByUser(1L)).thenReturn(List.of(project(1L, "订单系统")));
        ScheduledTask late = new ScheduledTask();
        late.setId(30L);
        late.setProjectId(1L);
        late.setNextExecTime(3000L);
        ScheduledTask early = new ScheduledTask();
        early.setId(31L);
        early.setProjectId(1L);
        early.setNextExecTime(1000L);
        when(scheduledTaskMapper.selectList(any())).thenReturn(List.of(late, early));

        List<OverviewCurrentTaskVO> result = overviewService.listCurrentTasks(1L);

        assertThat(result).extracting("id").containsExactly(31L, 30L);
    }

    @Test
    void listCurrentTasks_nullTypeAndStatus_voFieldsAreNull() {
        when(projectService.getByUser(1L)).thenReturn(List.of(project(1L, "订单系统")));
        ScheduledTask task = new ScheduledTask();
        task.setId(30L);
        task.setProjectId(1L);
        when(scheduledTaskMapper.selectList(any())).thenReturn(List.of(task));

        var vo = overviewService.listCurrentTasks(1L).get(0);

        assertThat(vo.getType()).isNull();
        assertThat(vo.getTypeDescription()).isNull();
        assertThat(vo.getStatus()).isNull();
        assertThat(vo.getStatusDescription()).isNull();
    }

    /**
     * 构造带规则ID的审核结果
     */
    private AuditResult ruleHit(long scanId, Long ruleId, String severity) {
        AuditResult result = auditResult(scanId, severity);
        result.setRuleId(ruleId);
        return result;
    }

    private cn.jnu.zhangcs.jsqlint.server.entity.AuditRule rule(long id, String name, String severity) {
        cn.jnu.zhangcs.jsqlint.server.entity.AuditRule rule = new cn.jnu.zhangcs.jsqlint.server.entity.AuditRule();
        rule.setId(id);
        rule.setName(name);
        rule.setCode("RULE_" + id);
        rule.setSeverity(severity);
        return rule;
    }

    @Test
    void getRuleHitOverview_aggregatesAcrossProjectsAndSortsByCount() {
        // 两个可见项目，最新版本审核结果合并后按规则计数
        when(projectService.getByUser(1L)).thenReturn(List.of(project(1L, "订单系统"), project(2L, "日志系统")));
        when(projectVersionService.getLast(1L)).thenReturn(version(10L, 1L, 2));
        when(projectVersionService.getLast(2L)).thenReturn(version(20L, 2L, 2));
        when(sqlStatementService.getByProjectAndVersion(1L, 10L)).thenReturn(List.of(statement(100L)));
        when(sqlStatementService.getByProjectAndVersion(2L, 20L)).thenReturn(List.of(statement(200L)));
        when(auditResultMapper.selectList(any())).thenReturn(
                List.of(ruleHit(100L, 10L, "critical"), ruleHit(200L, 10L, "critical")),
                List.of(ruleHit(200L, 11L, "warning"), ruleHit(200L, null, "info")));
        when(auditRuleMapper.selectList(any())).thenReturn(List.of(
                rule(10L, "禁止无WHERE删除", "critical"), rule(11L, "禁用SELECT*", "high")));

        var vo = overviewService.getRuleHitOverview(1L, 10);

        assertThat(vo.getTotalHitCount()).isEqualTo(4);
        // 规则10 命中2次居首；其余各1次按名称排序，"未知规则"与"禁用SELECT*"平手时按字典序
        assertThat(vo.getTopRules()).extracting("ruleName").containsExactly("禁止无WHERE删除", "未知规则", "禁用SELECT*");
        assertThat(vo.getTopRules().get(0).getHitCount()).isEqualTo(2);
        assertThat(vo.getTopRules().get(1).getRuleId()).isNull();
    }

    @Test
    void getRuleHitOverview_noVisibleProjects_returnsEmpty() {
        var vo = overviewService.getRuleHitOverview(null, 10);

        assertThat(vo.getTotalHitCount()).isZero();
        assertThat(vo.getTopRules()).isEmpty();
    }

    @Test
    void getSeverityDistribution_countsEachLevel() {
        when(projectService.getByUser(1L)).thenReturn(List.of(project(1L, "订单系统")));
        when(projectVersionService.getLast(1L)).thenReturn(version(10L, 1L, 2));
        when(sqlStatementService.getByProjectAndVersion(1L, 10L)).thenReturn(List.of(statement(100L)));
        when(auditResultMapper.selectList(any())).thenReturn(List.of(
                ruleHit(100L, 10L, "critical"),
                ruleHit(100L, 10L, "high"),
                ruleHit(100L, 11L, "high"),
                ruleHit(100L, 11L, "warning"),
                ruleHit(100L, null, "info")));

        var vo = overviewService.getSeverityDistribution(1L);

        assertThat(vo.getTotalIssueCount()).isEqualTo(5);
        assertThat(vo.getCriticalCount()).isEqualTo(1);
        assertThat(vo.getHighCount()).isEqualTo(2);
        assertThat(vo.getWarningCount()).isEqualTo(1);
        assertThat(vo.getInfoCount()).isEqualTo(1);
    }

    @Test
    void getProjectScoreRanking_sortsWorstFirst() {
        when(projectService.getByUser(1L)).thenReturn(List.of(project(1L, "订单系统"), project(2L, "日志系统")));
        cn.jnu.zhangcs.jsqlint.server.model.vo.ProjectOverviewScoreVO good =
                cn.jnu.zhangcs.jsqlint.server.model.vo.ProjectOverviewScoreVO.builder()
                        .projectId(2L).score(95).rating("A").issueSqlCount(1).build();
        cn.jnu.zhangcs.jsqlint.server.model.vo.ProjectOverviewScoreVO bad =
                cn.jnu.zhangcs.jsqlint.server.model.vo.ProjectOverviewScoreVO.builder()
                        .projectId(1L).score(50).rating("D").issueSqlCount(5).build();
        when(projectOverviewService.getProjectScore(1L)).thenReturn(bad);
        when(projectOverviewService.getProjectScore(2L)).thenReturn(good);

        var vo = overviewService.getProjectScoreRanking(1L, 10);

        // 评分升序：最差项目在前
        assertThat(vo.getProjects()).extracting("projectId").containsExactly(1L, 2L);
        assertThat(vo.getProjects().get(0).getProjectName()).isEqualTo("订单系统");
        assertThat(vo.getProjects().get(0).getRating()).isEqualTo("D");
    }

    @Test
    void getProjectScoreRanking_noVisibleProjects_returnsEmpty() {
        var vo = overviewService.getProjectScoreRanking(null, null);

        assertThat(vo.getProjects()).isEmpty();
    }
}
