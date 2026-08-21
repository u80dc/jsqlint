package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.common.exception.GlobalExceptionHandler;
import cn.jnu.zhangcs.jsqlint.server.model.vo.ProjectOverviewIssueCategoryVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.ProjectOverviewIssueRectificationVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.ProjectOverviewIssueSqlRateVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.ProjectOverviewIssueSqlVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.ProjectOverviewRuleHitVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.ProjectOverviewRuleVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.ProjectOverviewScoreVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.ProjectOverviewSqlQualityTrendVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.ProjectOverviewSqlVO;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectOverviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ProjectOverviewController} 单元测试（standalone MockMvc，service 全部 Mock）。
 * <p>控制器为纯委托实现，测试关注路由与 VO 字段的透传。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class ProjectOverviewControllerTest {

    @Mock
    private ProjectOverviewService projectOverviewService;

    @InjectMocks
    private ProjectOverviewController projectOverviewController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(projectOverviewController)
                .setControllerAdvice(new GlobalExceptionHandler())
                // standalone 默认 StringHttpMessageConverter 为 ISO-8859-1，中文响应会丢字，需显式指定 UTF-8
                .setMessageConverters(
                        new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new JacksonJsonHttpMessageConverter())
                .build();
    }

    @Test
    void getSqlOverview_returnsTypeCounts() throws Exception {
        when(projectOverviewService.getSqlOverview(1L)).thenReturn(ProjectOverviewSqlVO.builder()
                .projectId(1L)
                .versionId(2L)
                .totalSqlCount(42)
                .typeCounts(List.of(ProjectOverviewSqlVO.SqlTypeCountVO.builder()
                        .sqlType("SELECT").count(30).build()))
                .build());

        mockMvc.perform(get("/api/project/overview/1/sql"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(1))
                .andExpect(jsonPath("$.totalSqlCount").value(42))
                .andExpect(jsonPath("$.typeCounts[0].sqlType").value("SELECT"))
                .andExpect(jsonPath("$.typeCounts[0].count").value(30));
    }

    @Test
    void getRuleOverview_returnsRuleCounts() throws Exception {
        when(projectOverviewService.getRuleOverview(1L)).thenReturn(ProjectOverviewRuleVO.builder()
                .projectId(1L)
                .versionId(2L)
                .totalRuleCount(20)
                .globalRuleCount(15)
                .projectRuleCount(5)
                .enabledRuleCount(18)
                .enabledProjectRuleCount(3)
                .build());

        mockMvc.perform(get("/api/project/overview/1/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRuleCount").value(20))
                .andExpect(jsonPath("$.globalRuleCount").value(15))
                .andExpect(jsonPath("$.projectRuleCount").value(5))
                .andExpect(jsonPath("$.enabledRuleCount").value(18))
                .andExpect(jsonPath("$.enabledProjectRuleCount").value(3));
    }

    @Test
    void getRuleHitOverview_withLimit_returnsHighFrequencyRules() throws Exception {
        when(projectOverviewService.getRuleHitOverview(1L, 5)).thenReturn(ProjectOverviewRuleHitVO.builder()
                .projectId(1L)
                .versionId(2L)
                .totalHitCount(100)
                .highFrequencyRules(List.of(ProjectOverviewRuleHitVO.RuleHitCountVO.builder()
                        .ruleId(7L)
                        .ruleName("禁止使用SELECT *")
                        .severity("HIGH")
                        .hitCount(30)
                        .build()))
                .build());

        mockMvc.perform(get("/api/project/overview/1/rule-hits").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalHitCount").value(100))
                .andExpect(jsonPath("$.highFrequencyRules[0].ruleId").value(7))
                .andExpect(jsonPath("$.highFrequencyRules[0].ruleName").value("禁止使用SELECT *"))
                .andExpect(jsonPath("$.highFrequencyRules[0].hitCount").value(30));

        verify(projectOverviewService).getRuleHitOverview(1L, 5);
    }

    @Test
    void getIssueSqlOverview_returnsIssueCountsAndRate() throws Exception {
        when(projectOverviewService.getIssueSqlOverview(1L)).thenReturn(ProjectOverviewIssueSqlVO.builder()
                .projectId(1L)
                .versionId(2L)
                .totalSqlCount(42)
                .issueSqlCount(10)
                .issueCount(25)
                .issueSqlRate(new BigDecimal("0.24"))
                .build());

        mockMvc.perform(get("/api/project/overview/1/issue-sql"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSqlCount").value(42))
                .andExpect(jsonPath("$.issueSqlCount").value(10))
                .andExpect(jsonPath("$.issueCount").value(25))
                .andExpect(jsonPath("$.issueSqlRate").value(0.24));
    }

    @Test
    void getSqlQualityTrend_returnsTrendItems() throws Exception {
        when(projectOverviewService.getSqlQualityTrend(1L)).thenReturn(ProjectOverviewSqlQualityTrendVO.builder()
                .projectId(1L)
                .items(List.of(ProjectOverviewSqlQualityTrendVO.TrendItemVO.builder()
                        .versionId(9L)
                        .ref("main")
                        .commitId("abc123")
                        .operationTime(LocalDateTime.of(2026, 1, 1, 10, 0))
                        .totalSqlCount(42)
                        .criticalSqlCount(2)
                        .highSqlCount(3)
                        .warningSqlCount(5)
                        .build()))
                .build());

        mockMvc.perform(get("/api/project/overview/1/sql-quality-trend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].versionId").value(9))
                .andExpect(jsonPath("$.items[0].ref").value("main"))
                .andExpect(jsonPath("$.items[0].totalSqlCount").value(42))
                .andExpect(jsonPath("$.items[0].criticalSqlCount").value(2))
                .andExpect(jsonPath("$.items[0].highSqlCount").value(3))
                .andExpect(jsonPath("$.items[0].warningSqlCount").value(5));
    }

    @Test
    void getIssueCategoryOverview_returnsCategoryCounts() throws Exception {
        when(projectOverviewService.getIssueCategoryOverview(1L)).thenReturn(ProjectOverviewIssueCategoryVO.builder()
                .projectId(1L)
                .versionId(2L)
                .securitySqlCount(4)
                .performanceSqlCount(3)
                .qualitySqlCount(2)
                .otherSqlCount(1)
                .build());

        mockMvc.perform(get("/api/project/overview/1/issue-categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.securitySqlCount").value(4))
                .andExpect(jsonPath("$.performanceSqlCount").value(3))
                .andExpect(jsonPath("$.qualitySqlCount").value(2))
                .andExpect(jsonPath("$.otherSqlCount").value(1));
    }

    @Test
    void getIssueRectificationRate_returnsRatesAndTrend() throws Exception {
        when(projectOverviewService.getIssueRectificationRate(1L)).thenReturn(ProjectOverviewIssueRectificationVO.builder()
                .projectId(1L)
                .latestVersionId(9L)
                .previousVersionId(8L)
                .latestIssueSqlCount(8)
                .previousIssueSqlCount(10)
                .changeCount(5)
                .rectificationRate(new BigDecimal("0.20"))
                .increaseRate(new BigDecimal("0.10"))
                .decreaseRate(new BigDecimal("0.30"))
                .trend("down")
                .build());

        mockMvc.perform(get("/api/project/overview/1/issue-rectification-rate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestVersionId").value(9))
                .andExpect(jsonPath("$.previousVersionId").value(8))
                .andExpect(jsonPath("$.latestIssueSqlCount").value(8))
                .andExpect(jsonPath("$.previousIssueSqlCount").value(10))
                .andExpect(jsonPath("$.rectificationRate").value(0.20))
                .andExpect(jsonPath("$.trend").value("down"));
    }

    @Test
    void getIssueSqlRate_returnsRateAndChangeCounts() throws Exception {
        when(projectOverviewService.getIssueSqlRate(1L)).thenReturn(ProjectOverviewIssueSqlRateVO.builder()
                .projectId(1L)
                .latestVersionId(9L)
                .previousVersionId(8L)
                .latestTotalSqlCount(42)
                .latestIssueSqlCount(8)
                .previousIssueSqlCount(10)
                .issueSqlRate(new BigDecimal("0.19"))
                .changeCount(5)
                .increaseRate(BigDecimal.ZERO)
                .decreaseRate(new BigDecimal("0.20"))
                .trend("down")
                .build());

        mockMvc.perform(get("/api/project/overview/1/issue-sql-rate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestTotalSqlCount").value(42))
                .andExpect(jsonPath("$.latestIssueSqlCount").value(8))
                .andExpect(jsonPath("$.issueSqlRate").value(0.19))
                .andExpect(jsonPath("$.trend").value("down"));
    }

    @Test
    void getProjectScore_returnsScoreAndRating() throws Exception {
        when(projectOverviewService.getProjectScore(1L)).thenReturn(ProjectOverviewScoreVO.builder()
                .projectId(1L)
                .versionId(2L)
                .totalSqlCount(42)
                .issueSqlCount(10)
                .securitySqlCount(4)
                .performanceSqlCount(3)
                .qualitySqlCount(2)
                .otherSqlCount(1)
                .score(85)
                .rating("B")
                .build());

        mockMvc.perform(get("/api/project/overview/1/score"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(85))
                .andExpect(jsonPath("$.rating").value("B"));
    }

    @Test
    void getSqlOverview_bizExceptionMappedTo400() throws Exception {
        when(projectOverviewService.getSqlOverview(99L)).thenThrow(new BizException("项目不存在"));

        mockMvc.perform(get("/api/project/overview/99/sql"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("项目不存在"));
    }
}
