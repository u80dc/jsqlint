package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.model.vo.SqlChangeLogVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.SqlConstructionDetailVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.SqlPropagationPathVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.SqlStatementVO;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.SqlStatementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * SQL 语句存储控制器
 *
 * @author zhangcs
 */
@Tag(name = "SQL语句管理", description = "SQL 语句的查询、比较、变更历史和统计等操作")
@RestController
@RequestMapping("/api/sql-statements")
@RequiredArgsConstructor
public class SqlStatementController {

    private final SqlStatementService sqlStatementService;

    /**
     * 根据版本ID查询SQL语句列表
     */
    @Operation(summary = "根据版本查询SQL语句", description = "查询指定项目版本的所有 SQL 语句列表")
    @GetMapping("/version/{versionId}")
    public ResponseEntity<List<SqlStatementVO>> listByVersion(
            @Parameter(name = "versionId", description = "版本ID", example = "1") @PathVariable("versionId") Long versionId,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        List<SqlStatementVO> statements = sqlStatementService.getSqlStatementsByVersion(versionId);
        return ResponseEntity.ok(statements);
    }

    /**
     * 根据项目ID和版本ID查询SQL语句
     */
    @Operation(summary = "根据项目和版本查询SQL", description = "查询指定项目和版本的所有 SQL 语句列表")
    @GetMapping("/project/{projectId}/version/{versionId}")
    public ResponseEntity<List<SqlStatementVO>> listByProjectAndVersion(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable("projectId") Long projectId,
            @Parameter(name = "versionId", description = "版本ID", example = "1") @PathVariable("versionId") Long versionId,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        List<SqlStatementVO> statements = sqlStatementService.getSqlStatementsByProjectAndVersion(projectId, versionId);
        return ResponseEntity.ok(statements);
    }

    /**
     * 查询 SQL 构造过程详情
     */
    @Operation(summary = "查询SQL构造过程", description = "查询 SQL 的定义位置、SqlNode 构造步骤、参数推断和传播路径")
    @GetMapping("/{id}/construction")
    public ResponseEntity<SqlConstructionDetailVO> getConstructionDetail(
            @Parameter(name = "id", description = "SQL语句记录ID", example = "1") @PathVariable("id") Long id,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(sqlStatementService.getConstructionDetail(id));
    }

    /**
     * 查询 SQL 参数的传播路径
     */
    @Operation(summary = "查询SQL参数传播路径", description = "返回指定 SQL 各参数从调用点向上游回溯得到的污点传播链路（调用链 + 方法内值流）")
    @GetMapping("/{id}/propagation-paths")
    public ResponseEntity<SqlPropagationPathVO> getPropagationPaths(
            @Parameter(name = "id", description = "SQL语句记录ID", example = "1") @PathVariable("id") Long id,
            @Parameter(name = "versionId", description = "项目版本ID，用于校验 SQL 归属", example = "1") @RequestParam(value = "versionId", required = false) Long versionId,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(sqlStatementService.getPropagationPaths(id, versionId));
    }

    /**
     * 比较两个版本的SQL差异
     */
    @Operation(summary = "比较两个版本的SQL差异", description = "对比两个项目版本之间的 SQL 语句变化")
    @GetMapping("/compare")
    public ResponseEntity<List<SqlChangeLogVO>> compareVersions(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @RequestParam("projectId") Long projectId,
            @Parameter(name = "fromVersionId", description = "起始版本ID", example = "1") @RequestParam("fromVersionId") Long fromVersionId,
            @Parameter(name = "toVersionId", description = "目标版本ID", example = "2") @RequestParam("toVersionId") Long toVersionId,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        List<SqlChangeLogVO> changes = sqlStatementService.compareTwoVersions(projectId, fromVersionId, toVersionId);
        return ResponseEntity.ok(changes);
    }

    /**
     * 根据SQL hash查询相同的SQL
     */
    @Operation(summary = "根据hash查询SQL", description = "根据 SQL Hash 值查询所有相同的 SQL 语句")
    @GetMapping("/by-hash/{sqlHash}")
    public ResponseEntity<List<SqlStatementVO>> listByHash(
            @Parameter(name = "sqlHash", description = "SQL Hash值", example = "abc123def456") @PathVariable("sqlHash") String sqlHash,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        List<SqlStatementVO> statements = sqlStatementService.getSqlStatementsByHash(sqlHash);
        return ResponseEntity.ok(statements);
    }

    /**
     * 查询项目的SQL变更历史
     */
    @Operation(summary = "查询项目SQL变更历史", description = "查询指定项目的所有 SQL 变更历史记录")
    @GetMapping("/changes/project/{projectId}")
    public ResponseEntity<List<SqlChangeLogVO>> getChangeHistory(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable("projectId") Long projectId,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        List<SqlChangeLogVO> changes = sqlStatementService.getChangeHistory(projectId);
        return ResponseEntity.ok(changes);
    }

    /**
     * 查询指定版本之间的变更详情
     */
    @Operation(summary = "查询版本间SQL变更", description = "查询指定两个版本之间的 SQL 变更详细信息")
    @GetMapping("/changes")
    public ResponseEntity<List<SqlChangeLogVO>> getChangesByVersions(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @RequestParam("projectId") Long projectId,
            @Parameter(name = "fromVersionId", description = "起始版本ID", example = "1") @RequestParam("fromVersionId") Long fromVersionId,
            @Parameter(name = "toVersionId", description = "目标版本ID", example = "2") @RequestParam("toVersionId") Long toVersionId,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        List<SqlChangeLogVO> changes = sqlStatementService.getChangesByVersions(projectId, fromVersionId, toVersionId);
        return ResponseEntity.ok(changes);
    }

    /**
     * 删除指定版本的所有SQL语句
     */
    @Operation(summary = "删除版本的SQL语句", description = "删除指定版本的所有 SQL 语句记录")
    @DeleteMapping("/version/{versionId}")
    public ResponseEntity<Map<String, Object>> deleteByVersion(
            @Parameter(name = "versionId", description = "版本ID", example = "1") @PathVariable("versionId") Long versionId,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        sqlStatementService.deleteSqlStatementsByVersion(versionId);
        return ResponseEntity.ok(Map.of("message", "删除成功"));
    }

    /**
     * 获取SQL语句统计信息
     */
    @Operation(summary = "获取SQL统计信息", description = "获取指定版本的 SQL 语句统计信息（总数、动态/静态SQL、类型分布等）")
    @GetMapping("/statistics/version/{versionId}")
    public ResponseEntity<Map<String, Object>> getStatistics(
            @Parameter(name = "versionId", description = "版本ID", example = "1") @PathVariable("versionId") Long versionId,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        List<SqlStatementVO> statements = sqlStatementService.getSqlStatementsByVersion(versionId);

        long totalCount = statements.size();
        long dynamicCount = statements.stream().filter(s -> Boolean.TRUE.equals(s.getIsDynamic())).count();
        long staticCount = totalCount - dynamicCount;

        Map<String, Long> typeCount = statements.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        SqlStatementVO::getStatementType,
                        java.util.stream.Collectors.counting()
                ));

        Map<String, Object> statistics = Map.of(
                "totalCount", totalCount,
                "dynamicCount", dynamicCount,
                "staticCount", staticCount,
                "typeCount", typeCount
        );

        return ResponseEntity.ok(statistics);
    }
}
