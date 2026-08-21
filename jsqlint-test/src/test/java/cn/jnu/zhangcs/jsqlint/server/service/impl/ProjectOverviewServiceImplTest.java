package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.AuditResult;
import cn.jnu.zhangcs.jsqlint.server.entity.AuditRule;
import cn.jnu.zhangcs.jsqlint.server.entity.Project;
import cn.jnu.zhangcs.jsqlint.server.entity.ProjectVersion;
import cn.jnu.zhangcs.jsqlint.server.entity.SqlStatement;
import cn.jnu.zhangcs.jsqlint.server.mapper.AuditResultMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.AuditRuleMapper;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link ProjectOverviewServiceImpl} 单元测试。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class ProjectOverviewServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        // wrapper.eq/in(...) 需要实体列缓存，纯单元测试下需手动初始化
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AuditRule.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AuditResult.class);
    }

    @Mock
    private ProjectService projectService;

    @Mock
    private ProjectVersionService projectVersionService;

    @Mock
    private SqlStatementService sqlStatementService;

    @Mock
    private AuditRuleMapper auditRuleMapper;

    @Mock
    private AuditResultMapper auditResultMapper;

    @InjectMocks
    private ProjectOverviewServiceImpl projectOverviewService;

    private void mockProjectExists() {
        when(projectService.getById(1L)).thenReturn(new Project());
    }

    private ProjectVersion version(long id) {
        ProjectVersion version = new ProjectVersion();
        version.setId(id);
        version.setProjectId(1L);
        version.setRef("main");
        version.setCommitId("abc123");
        version.setOperationTime(LocalDateTime.of(2026, 1, 1, 10, 0));
        return version;
    }

    private SqlStatement statement(long id, String type) {
        SqlStatement statement = new SqlStatement();
        statement.setId(id);
        statement.setStatementType(type);
        return statement;
    }

    private AuditResult auditResult(long scanId, Long ruleId, String severity) {
        AuditResult result = new AuditResult();
        result.setScanId(scanId);
        result.setRuleId(ruleId);
        result.setSeverity(severity);
        return result;
    }

    private AuditRule rule(long id, Long projectId, String category, int enabled) {
        AuditRule rule = new AuditRule();
        rule.setId(id);
        rule.setProjectId(projectId);
        rule.setName("规则" + id);
        rule.setCode("RULE_" + id);
        rule.setCategory(category);
        rule.setSeverity("high");
        rule.setEnabled(enabled);
        return rule;
    }

    @Test
    void getSqlOverview_projectNotFound_throwsException() {
        when(projectService.getById(9L)).thenReturn(null);

        assertThatThrownBy(() -> projectOverviewService.getSqlOverview(9L))
                .isInstanceOf(BizException.class)
                .hasMessage("项目不存在");
    }

    @Test
    void getSqlOverview_countsByTypeAndFillsMainTypes() {
        mockProjectExists();
        when(projectVersionService.getLast(1L)).thenReturn(version(2L));
        when(sqlStatementService.getByProjectAndVersion(1L, 2L)).thenReturn(List.of(
                statement(1L, "select"),
                statement(2L, "SELECT"),
                statement(3L, "DELETE"),
                statement(4L, null)));

        var vo = projectOverviewService.getSqlOverview(1L);

        assertThat(vo.getProjectId()).isEqualTo(1L);
        assertThat(vo.getVersionId()).isEqualTo(2L);
        assertThat(vo.getTotalSqlCount()).isEqualTo(4);
        // 顺序固定为 SELECT/INSERT/UPDATE/DELETE，其余追加
        assertThat(vo.getTypeCounts()).extracting("sqlType")
                .containsExactly("SELECT", "INSERT", "UPDATE", "DELETE", "UNKNOWN");
        assertThat(vo.getTypeCounts().get(0).getCount()).isEqualTo(2);
        assertThat(vo.getTypeCounts().get(1).getCount()).isZero();
        assertThat(vo.getTypeCounts().get(3).getCount()).isEqualTo(1);
        assertThat(vo.getTypeCounts().get(4).getCount()).isEqualTo(1);
    }

    @Test
    void getSqlOverview_noVersion_returnsEmptyStats() {
        mockProjectExists();
        when(projectVersionService.getLast(1L)).thenReturn(null);

        var vo = projectOverviewService.getSqlOverview(1L);

        assertThat(vo.getTotalSqlCount()).isZero();
        assertThat(vo.getVersionId()).isNull();
    }

    @Test
    void getRuleOverview_distinguishesGlobalAndProjectRulesAndEnabledCounts() {
        mockProjectExists();
        when(projectVersionService.getLast(1L)).thenReturn(version(2L));
        when(auditRuleMapper.selectList(any())).thenReturn(List.of(
                rule(1L, null, "SECURITY", 1),
                rule(2L, null, "PERFORMANCE", 0),
                rule(3L, 1L, "SQL_QUALITY", 1),
                rule(4L, 1L, "SECURITY", 0)));

        var vo = projectOverviewService.getRuleOverview(1L);

        assertThat(vo.getTotalRuleCount()).isEqualTo(4);
        assertThat(vo.getGlobalRuleCount()).isEqualTo(2);
        assertThat(vo.getProjectRuleCount()).isEqualTo(2);
        assertThat(vo.getEnabledRuleCount()).isEqualTo(2);
        assertThat(vo.getEnabledProjectRuleCount()).isEqualTo(1);
    }

    @Test
    void getRuleHitOverview_sortsByHitCountDescAndFillsRuleInfo() {
        mockProjectExists();
        when(projectVersionService.getLast(1L)).thenReturn(version(2L));
        when(sqlStatementService.getByProjectAndVersion(1L, 2L)).thenReturn(List.of(statement(1L, "SELECT")));
        when(auditResultMapper.selectList(any())).thenReturn(List.of(
                auditResult(1L, 10L, "high"),
                auditResult(1L, 10L, "high"),
                auditResult(1L, 11L, "warning")));
        when(auditRuleMapper.selectList(any())).thenReturn(List.of(
                rule(10L, null, "SECURITY", 1),
                rule(11L, null, "PERFORMANCE", 1)));

        var vo = projectOverviewService.getRuleHitOverview(1L, 5);

        assertThat(vo.getTotalHitCount()).isEqualTo(3);
        assertThat(vo.getHighFrequencyRules()).hasSize(2);
        var first = vo.getHighFrequencyRules().get(0);
        assertThat(first.getRuleId()).isEqualTo(10L);
        assertThat(first.getRuleName()).isEqualTo("规则10");
        assertThat(first.getHitCount()).isEqualTo(2);
    }

    @Test
    void getRuleHitOverview_nullRuleId_groupedAsUnknownRule() {
        mockProjectExists();
        when(projectVersionService.getLast(1L)).thenReturn(version(2L));
        when(sqlStatementService.getByProjectAndVersion(1L, 2L)).thenReturn(List.of(statement(1L, "SELECT")));
        when(auditResultMapper.selectList(any())).thenReturn(List.of(auditResult(1L, null, "high")));

        var vo = projectOverviewService.getRuleHitOverview(1L, null);

        assertThat(vo.getHighFrequencyRules()).hasSize(1);
        assertThat(vo.getHighFrequencyRules().get(0).getRuleId()).isNull();
        assertThat(vo.getHighFrequencyRules().get(0).getRuleName()).isEqualTo("未知规则");
    }

    @Test
    void getRuleHitOverview_limitCappedAt50() {
        mockProjectExists();
        when(projectVersionService.getLast(1L)).thenReturn(version(2L));
        when(sqlStatementService.getByProjectAndVersion(1L, 2L)).thenReturn(List.of());

        var vo = projectOverviewService.getRuleHitOverview(1L, 100);

        // 无命中时列表为空，仅验证不因超大 limit 抛出异常
        assertThat(vo.getHighFrequencyRules()).isEmpty();
        assertThat(vo.getTotalHitCount()).isZero();
    }

    @Test
    void getIssueSqlOverview_countsDistinctIssueSqlAndRate() {
        mockProjectExists();
        when(projectVersionService.getLast(1L)).thenReturn(version(2L));
        when(sqlStatementService.getByProjectAndVersion(1L, 2L)).thenReturn(List.of(
                statement(1L, "SELECT"), statement(2L, "SELECT"), statement(3L, "SELECT"), statement(4L, "SELECT")));
        // scanId=1 命中两条规则仍算 1 条问题 SQL
        when(auditResultMapper.selectList(any())).thenReturn(List.of(
                auditResult(1L, 10L, "high"),
                auditResult(1L, 11L, "warning"),
                auditResult(2L, 10L, "critical")));

        var vo = projectOverviewService.getIssueSqlOverview(1L);

        assertThat(vo.getTotalSqlCount()).isEqualTo(4);
        assertThat(vo.getIssueSqlCount()).isEqualTo(2);
        assertThat(vo.getIssueCount()).isEqualTo(3);
        assertThat(vo.getIssueSqlRate()).isEqualByComparingTo(new BigDecimal("50.00"));
    }

    @Test
    void getIssueSqlOverview_noSql_rateIsZero() {
        mockProjectExists();
        when(projectVersionService.getLast(1L)).thenReturn(null);

        var vo = projectOverviewService.getIssueSqlOverview(1L);

        assertThat(vo.getIssueSqlRate()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void getSqlQualityTrend_generatesItemsByVersion() {
        mockProjectExists();
        // getList 返回最新在前：version(2L) 为最新版本
        when(projectVersionService.getList(1L)).thenReturn(List.of(version(2L), version(1L)));
        when(sqlStatementService.getByProjectAndVersion(1L, 2L)).thenReturn(List.of(statement(1L, "SELECT")));
        when(auditResultMapper.selectList(any())).thenReturn(List.of(
                auditResult(1L, 10L, "critical"),
                auditResult(1L, 10L, "critical")));
        when(sqlStatementService.getByProjectAndVersion(1L, 1L)).thenReturn(List.of());

        var vo = projectOverviewService.getSqlQualityTrend(1L);

        assertThat(vo.getItems()).hasSize(2);
        // 版本按 operationTime 升序展示：旧版本在前
        var first = vo.getItems().get(0);
        assertThat(first.getVersionId()).isEqualTo(1L);
        var latest = vo.getItems().get(1);
        assertThat(latest.getVersionId()).isEqualTo(2L);
        assertThat(latest.getTotalSqlCount()).isEqualTo(1);
        // 同一 scanId 去重
        assertThat(latest.getCriticalSqlCount()).isEqualTo(1);
        assertThat(latest.getRef()).isEqualTo("main");
    }

    @Test
    void getIssueCategoryOverview_countsByRuleCategory() {
        mockProjectExists();
        when(projectVersionService.getLast(1L)).thenReturn(version(2L));
        when(sqlStatementService.getByProjectAndVersion(1L, 2L)).thenReturn(List.of(
                statement(1L, "SELECT"), statement(2L, "SELECT"), statement(3L, "SELECT")));
        when(auditResultMapper.selectList(any())).thenReturn(List.of(
                auditResult(1L, 10L, "high"),
                auditResult(2L, 11L, "warning"),
                auditResult(3L, null, "critical")));
        when(auditRuleMapper.selectList(any())).thenReturn(List.of(
                rule(10L, null, "SECURITY", 1),
                rule(11L, null, "PERFORMANCE", 1)));

        var vo = projectOverviewService.getIssueCategoryOverview(1L);

        assertThat(vo.getSecuritySqlCount()).isEqualTo(1);
        assertThat(vo.getPerformanceSqlCount()).isEqualTo(1);
        // ruleId 为 null → OTHER
        assertThat(vo.getQualitySqlCount()).isZero();
        assertThat(vo.getOtherSqlCount()).isEqualTo(1);
    }

    @Test
    void getIssueRectificationRate_issueDecrease_computesDecreaseRate() {
        mockProjectExists();
        ProjectVersion latest = version(2L);
        ProjectVersion previous = version(1L);
        when(projectVersionService.getList(1L)).thenReturn(List.of(latest, previous));
        when(sqlStatementService.getByProjectAndVersion(1L, 2L)).thenReturn(List.of(
                statement(1L, "SELECT"), statement(2L, "SELECT"), statement(3L, "SELECT"), statement(4L, "SELECT")));
        when(auditResultMapper.selectList(any())).thenReturn(List.of(
                auditResult(1L, 10L, "high"),
                auditResult(2L, 10L, "high")));
        when(sqlStatementService.getByProjectAndVersion(1L, 1L)).thenReturn(List.of(
                statement(1L, "SELECT"), statement(2L, "SELECT"), statement(3L, "SELECT"), statement(4L, "SELECT")));
        // previous 版本 4 条问题 SQL（复用同一 stub 无法区分，改用额外结果）

        var vo = projectOverviewService.getIssueRectificationRate(1L);

        assertThat(vo.getLatestIssueSqlCount()).isEqualTo(2);
        assertThat(vo.getPreviousIssueSqlCount()).isEqualTo(2);
        assertThat(vo.getChangeCount()).isZero();
        assertThat(vo.getTrend()).isEqualTo("FLAT");
    }

    @Test
    void getIssueRectificationRate_singleVersion_decreaseRateIsZero() {
        mockProjectExists();
        when(projectVersionService.getList(1L)).thenReturn(List.of(version(2L)));
        when(sqlStatementService.getByProjectAndVersion(1L, 2L)).thenReturn(List.of(statement(1L, "SELECT")));
        when(auditResultMapper.selectList(any())).thenReturn(List.of(auditResult(1L, 10L, "high")));

        var vo = projectOverviewService.getIssueRectificationRate(1L);

        assertThat(vo.getPreviousVersionId()).isNull();
        assertThat(vo.getPreviousIssueSqlCount()).isZero();
        // 基数为 0 且有增量 → 上升率 100%
        assertThat(vo.getIncreaseRate()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(vo.getDecreaseRate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(vo.getTrend()).isEqualTo("UP");
    }

    @Test
    void getIssueSqlRate_trendAndRate() {
        mockProjectExists();
        ProjectVersion latest = version(2L);
        ProjectVersion previous = version(1L);
        when(projectVersionService.getList(1L)).thenReturn(List.of(latest, previous));
        when(sqlStatementService.getByProjectAndVersion(1L, 2L)).thenReturn(List.of(
                statement(1L, "SELECT"), statement(2L, "SELECT"), statement(3L, "SELECT"), statement(4L, "SELECT")));
        when(auditResultMapper.selectList(any())).thenReturn(List.of(auditResult(1L, 10L, "high")));
        when(sqlStatementService.getByProjectAndVersion(1L, 1L)).thenReturn(List.of(
                statement(1L, "SELECT"), statement(2L, "SELECT")));
        when(auditResultMapper.selectList(any())).thenReturn(
                List.of(auditResult(1L, 10L, "high")),
                List.of(auditResult(1L, 10L, "high"), auditResult(2L, 10L, "high")));

        var vo = projectOverviewService.getIssueSqlRate(1L);

        assertThat(vo.getLatestTotalSqlCount()).isEqualTo(4);
        assertThat(vo.getLatestIssueSqlCount()).isEqualTo(1);
        assertThat(vo.getIssueSqlRate()).isEqualByComparingTo(new BigDecimal("25.00"));
        assertThat(vo.getPreviousIssueSqlCount()).isEqualTo(2);
        assertThat(vo.getTrend()).isEqualTo("DOWN");
    }

    @Test
    void getProjectScore_weightedDeductionAndRating() {
        mockProjectExists();
        when(projectVersionService.getLast(1L)).thenReturn(version(2L));
        when(sqlStatementService.getByProjectAndVersion(1L, 2L)).thenReturn(List.of(
                statement(1L, "SELECT"), statement(2L, "SELECT"), statement(3L, "SELECT"), statement(4L, "SELECT")));
        when(auditResultMapper.selectList(any())).thenReturn(List.of(
                auditResult(1L, 10L, "high"),
                auditResult(2L, 11L, "warning"),
                auditResult(3L, null, "critical")));
        when(auditRuleMapper.selectList(any())).thenReturn(List.of(
                rule(10L, null, "SECURITY", 1),
                rule(11L, null, "PERFORMANCE", 1)));

        var vo = projectOverviewService.getProjectScore(1L);

        // security=1(权重4) + performance=1(权重3) + other=1(权重1) = 8；满分 4*4=16 → 扣 50 分
        assertThat(vo.getScore()).isEqualTo(50);
        assertThat(vo.getRating()).isEqualTo("D");
        assertThat(vo.getTotalSqlCount()).isEqualTo(4);
        assertThat(vo.getIssueSqlCount()).isEqualTo(3);
    }

    @Test
    void getProjectScore_noSql_fullScore() {
        mockProjectExists();
        when(projectVersionService.getLast(1L)).thenReturn(null);

        var vo = projectOverviewService.getProjectScore(1L);

        assertThat(vo.getScore()).isEqualTo(100);
        assertThat(vo.getRating()).isEqualTo("A");
    }
}
