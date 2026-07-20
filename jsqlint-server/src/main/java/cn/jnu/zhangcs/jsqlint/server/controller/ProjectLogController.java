package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.entity.ProjectLog;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectLogService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 项目日志控制器
 *
 * @author zhangcs
 */
@Tag(name = "项目日志", description = "项目操作日志的分页查询和检索")
@RestController
@RequestMapping("/api/project/logs")
@RequiredArgsConstructor
public class ProjectLogController {

    private final ProjectLogService projectLogService;

    /**
     * 分页查询项目日志
     *
     * @param page            当前页
     * @param size            每页大小
     * @param projectId       项目ID
     * @param operationType   操作类型
     * @param operationUserId 操作用户ID
     * @param startTime       开始时间 (格式: yyyy-MM-dd HH:mm:ss)
     * @param endTime         结束时间 (格式: yyyy-MM-dd HH:mm:ss)
     * @return 分页结果
     */
    @Operation(summary = "分页查询项目日志", description = "分页查询项目操作日志，支持按项目、操作类型、用户、时间范围筛选")
    @GetMapping
    public ResponseEntity<Page<ProjectLog>> list(
            @Parameter(name = "page", description = "页码", example = "1") @RequestParam(value = "page", defaultValue = "1") int page,
            @Parameter(name = "size", description = "每页数量", example = "20") @RequestParam(value = "size", defaultValue = "20") int size,
            @Parameter(name = "projectId", description = "项目ID", example = "1") @RequestParam(value = "projectId", required = false) Long projectId,
            @Parameter(name = "operationType", description = "操作类型", example = "SYNC") @RequestParam(value = "operationType", required = false) String operationType,
            @Parameter(name = "operationUserId", description = "操作用户ID", example = "1") @RequestParam(value = "operationUserId", required = false) Long operationUserId,
            @Parameter(name = "startTime", description = "开始时间", example = "2024-01-01 00:00:00") @RequestParam(value = "startTime", required = false) String startTime,
            @Parameter(name = "endTime", description = "结束时间", example = "2024-12-31 23:59:59") @RequestParam(value = "endTime", required = false) String endTime) {

        Page<ProjectLog> result = projectLogService.getPage(
                page, size, projectId, operationType, operationUserId, startTime, endTime);
        return ResponseEntity.ok(result);
    }

    /**
     * 根据项目ID查询日志
     *
     * @param projectId 项目ID
     * @param page      当前页
     * @param size      每页大小
     * @return 分页结果
     */
    @Operation(summary = "根据项目查询日志", description = "查询指定项目的所有操作日志")
    @GetMapping("/{projectId}")
    public ResponseEntity<Page<ProjectLog>> getByProjectId(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable("projectId") Long projectId,
            @Parameter(name = "page", description = "页码", example = "1") @RequestParam(value = "page", defaultValue = "1") int page,
            @Parameter(name = "size", description = "每页数量", example = "20") @RequestParam(value = "size", defaultValue = "20") int size) {

        Page<ProjectLog> result = projectLogService.getByProjectId(page, size, projectId);
        return ResponseEntity.ok(result);
    }

    /**
     * 根据项目ID和关联资源ID查询日志
     *
     * @param projectId 项目ID
     * @param relatedId 关联资源ID
     * @param page      当前页
     * @param size      每页大小
     * @return 分页结果
     */
    @Operation(summary = "根据项目和关联资源查询日志", description = "查询指定项目中某个关联资源的相关日志")
    @GetMapping("/{projectId}/related/{relatedId}")
    public ResponseEntity<Page<ProjectLog>> getByRelatedId(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable("projectId") Long projectId,
            @Parameter(name = "relatedId", description = "关联资源ID", example = "1") @PathVariable("relatedId") Long relatedId,
            @Parameter(name = "page", description = "页码", example = "1") @RequestParam(value = "page", defaultValue = "1") int page,
            @Parameter(name = "size", description = "每页数量", example = "20") @RequestParam(value = "size", defaultValue = "20") int size) {

        Page<ProjectLog> result = projectLogService.getByRelatedId(page, size, projectId, relatedId);
        return ResponseEntity.ok(result);
    }
}
