package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.model.vo.*;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectOverviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "项目概览", description = "项目最新版本的 SQL、规则和审核结果统计")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/project/overview")
public class ProjectOverviewController {

    private final ProjectOverviewService projectOverviewService;

    @Operation(summary = "查询项目SQL统计", description = "根据项目最新版本统计SQL总数和不同类型SQL数量")
    @GetMapping("/{projectId}/sql")
    public ResponseEntity<ProjectOverviewSqlVO> getSqlOverview(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable Long projectId) {
        return ResponseEntity.ok(projectOverviewService.getSqlOverview(projectId));
    }

    @Operation(summary = "查询项目规则统计", description = "统计项目关联审核规则总数和项目特定规则数")
    @GetMapping("/{projectId}/rules")
    public ResponseEntity<ProjectOverviewRuleVO> getRuleOverview(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable Long projectId) {
        return ResponseEntity.ok(projectOverviewService.getRuleOverview(projectId));
    }

    @Operation(summary = "查询项目规则触发统计", description = "根据项目最新版本统计触发规则总次数和高频规则触发次数")
    @GetMapping("/{projectId}/rule-hits")
    public ResponseEntity<ProjectOverviewRuleHitVO> getRuleHitOverview(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable Long projectId,
            @Parameter(name = "limit", description = "高频规则数量", example = "10") @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(projectOverviewService.getRuleHitOverview(projectId, limit));
    }

    @Operation(summary = "查询项目问题SQL统计", description = "根据项目最新版本统计存在审核问题的SQL数量")
    @GetMapping("/{projectId}/issue-sql")
    public ResponseEntity<ProjectOverviewIssueSqlVO> getIssueSqlOverview(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable Long projectId) {
        return ResponseEntity.ok(projectOverviewService.getIssueSqlOverview(projectId));
    }

    @Operation(summary = "查询SQL质量趋势", description = "返回项目近10个版本的SQL总数、致命SQL数、高危SQL数和警告SQL数")
    @GetMapping("/{projectId}/sql-quality-trend")
    public ResponseEntity<ProjectOverviewSqlQualityTrendVO> getSqlQualityTrend(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable Long projectId) {
        return ResponseEntity.ok(projectOverviewService.getSqlQualityTrend(projectId));
    }

    @Operation(summary = "查询SQL问题分类统计", description = "返回项目最新版本的问题分类SQL数量：安全、性能、质量和其他")
    @GetMapping("/{projectId}/issue-categories")
    public ResponseEntity<ProjectOverviewIssueCategoryVO> getIssueCategoryOverview(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable Long projectId) {
        return ResponseEntity.ok(projectOverviewService.getIssueCategoryOverview(projectId));
    }

    @Operation(summary = "查询问题整改率", description = "对比项目最近两次版本的问题SQL数量，返回整改率、上升率和下降率")
    @GetMapping("/{projectId}/issue-rectification-rate")
    public ResponseEntity<ProjectOverviewIssueRectificationVO> getIssueRectificationRate(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable Long projectId) {
        return ResponseEntity.ok(projectOverviewService.getIssueRectificationRate(projectId));
    }

    @Operation(summary = "查询问题SQL占比", description = "计算最新版本问题SQL与总SQL数的比例，并和上一版本问题SQL总数对比返回上升率和下降率")
    @GetMapping("/{projectId}/issue-sql-rate")
    public ResponseEntity<ProjectOverviewIssueSqlRateVO> getIssueSqlRate(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable Long projectId) {
        return ResponseEntity.ok(projectOverviewService.getIssueSqlRate(projectId));
    }

    @Operation(summary = "查询项目评分", description = "根据SQL总数和不同类别问题SQL数量计算项目得分及A、B、C、D评级")
    @GetMapping("/{projectId}/score")
    public ResponseEntity<ProjectOverviewScoreVO> getProjectScore(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable Long projectId) {
        return ResponseEntity.ok(projectOverviewService.getProjectScore(projectId));
    }
}
