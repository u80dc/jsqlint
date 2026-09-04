package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.AnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目流水线控制器
 *
 * <p>承载项目分析流水线的任务触发端点（同步、编译、分析、审核、报告、完整执行）。
 * 这些动作语义上是提交任务而非修改项目本身，与 {@link ProjectController} 的项目管理职责分离。
 * URL 保持 {@code /api/projects/{id}/...} 前缀，对前端透明。</p>
 *
 * @author zhangcs
 */
@Tag(name = "项目流水线", description = "项目分析流水线任务触发：同步、编译、分析、审核、报告生成与完整执行")
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/projects")
public class ProjectPipelineController {

    private final AnalysisService analysisService;

    @Operation(summary = "同步代码", description = "从 Git 仓库同步项目代码")
    @PostMapping("/{id}/sync")
    public ResponseEntity<Void> sync(
            @Parameter(name = "id", description = "项目ID", example = "1") @PathVariable(value = "id") Long id,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        analysisService.sync(id, loginUser.getUserId());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "编译项目", description = "使用 Maven 编译项目代码")
    @PostMapping("/{id}/compile")
    public ResponseEntity<Void> compile(
            @Parameter(name = "id", description = "项目ID", example = "1") @PathVariable(value = "id") Long id,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        analysisService.compile(id, loginUser.getUserId());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "分析项目", description = "对项目代码进行 SQL 注入漏洞分析")
    @PostMapping("/{id}/analyze")
    public ResponseEntity<Void> analyze(
            @Parameter(name = "id", description = "项目ID", example = "1") @PathVariable(value = "id") Long id,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        analysisService.analyze(id, loginUser.getUserId());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "审核项目", description = "对分析结果应用 Drools 审核规则")
    @PostMapping("/{id}/audit")
    public ResponseEntity<Void> audit(
            @Parameter(name = "id", description = "项目ID", example = "1") @PathVariable(value = "id") Long id,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        analysisService.audit(id, loginUser.getUserId());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "生成报告", description = "生成项目的审核报告")
    @PostMapping("/{id}/reports")
    public ResponseEntity<Long> report(
            @Parameter(name = "id", description = "项目ID", example = "1") @PathVariable(value = "id") Long id,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        Long taskId = analysisService.report(id, loginUser.getUserId());
        return ResponseEntity.ok(taskId);
    }

    @Operation(summary = "执行项目", description = "提交项目完整执行任务，依次完成同步代码、编译检查、SQL扫描、规则审核和生成报告")
    @PostMapping("/{id}/execute")
    public ResponseEntity<Boolean> execute(
            @Parameter(name = "id", description = "项目ID", example = "1") @PathVariable(value = "id") Long id,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(analysisService.execute(id, loginUser.getUserId()));
    }
}
