package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.model.vo.OverviewCurrentTaskVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.OverviewProjectScoreVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.OverviewProjectSummaryVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.OverviewRecentProjectVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.OverviewRuleHitVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.OverviewSeverityDistributionVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.OverviewTrendVO;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.OverviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "概览", description = "首页概览统计、趋势、最近分析和当前任务接口")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/overview")
public class OverviewController {

    private final OverviewService overviewService;

    @Operation(summary = "概览统计", description = "根据当前用户查询可见项目总数及最新版本的安全/警告/危险SQL数量")
    @GetMapping("/projects/summary")
    public ResponseEntity<OverviewProjectSummaryVO> getProjectSummary(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(overviewService.getProjectSummary(loginUser.getUserId()));
    }

    @Operation(summary = "最近分析项目列表", description = "根据当前用户可见项目查询最近10条分析记录")
    @GetMapping("/recent-projects")
    public ResponseEntity<List<OverviewRecentProjectVO>> listRecentProjects(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(overviewService.listRecentProjects(loginUser.getUserId()));
    }

    @Operation(summary = "分析趋势", description = "根据当前用户可见项目统计安全、警告、危险三个类别的趋势数据，period支持week/month/year")
    @GetMapping("/analysis-trend")
    public ResponseEntity<OverviewTrendVO> getAnalysisTrend(
            @Parameter(name = "period", description = "统计周期: week/month/year", example = "week")
            @RequestParam(defaultValue = "week") String period,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(overviewService.getAnalysisTrend(loginUser.getUserId(), period));
    }

    @Operation(summary = "当前任务", description = "根据当前用户可见项目查询执行中、待执行、等待重试任务")
    @GetMapping("/current-tasks")
    public ResponseEntity<List<OverviewCurrentTaskVO>> listCurrentTasks(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(overviewService.listCurrentTasks(loginUser.getUserId()));
    }

    @Operation(summary = "规则命中TOP N", description = "跨用户可见项目统计高频命中规则，按命中次数降序")
    @GetMapping("/rule-hits")
    public ResponseEntity<OverviewRuleHitVO> getRuleHitOverview(
            @Parameter(name = "limit", description = "返回条数上限（1-50，默认10）", example = "10")
            @RequestParam(required = false) Integer limit,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(overviewService.getRuleHitOverview(loginUser.getUserId(), limit));
    }

    @Operation(summary = "问题严重级别分布", description = "跨用户可见项目统计 critical/high/warning/info 级别问题数")
    @GetMapping("/severity-distribution")
    public ResponseEntity<OverviewSeverityDistributionVO> getSeverityDistribution(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(overviewService.getSeverityDistribution(loginUser.getUserId()));
    }

    @Operation(summary = "项目健康度排行", description = "跨用户可见项目按质量评分升序排列，最差项目在前")
    @GetMapping("/project-scores")
    public ResponseEntity<OverviewProjectScoreVO> getProjectScoreRanking(
            @Parameter(name = "limit", description = "返回条数上限（1-50，默认10）", example = "10")
            @RequestParam(required = false) Integer limit,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(overviewService.getProjectScoreRanking(loginUser.getUserId(), limit));
    }

}
