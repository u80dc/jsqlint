package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.entity.SystemLog;
import cn.jnu.zhangcs.jsqlint.server.service.SystemLogService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 系统日志控制器
 *
 * @author zhangcs
 */
@Tag(name = "系统日志", description = "系统操作日志的分页查询和检索")
@RestController
@RequestMapping("/api/system/logs")
@RequiredArgsConstructor
public class SystemLogController {

    private final SystemLogService systemLogService;

    /**
     * 分页查询系统日志
     *
     * @param page            当前页
     * @param size            每页大小
     * @param operationType   操作类型
     * @param operationUserId 操作用户ID
     * @param startTime       开始时间 (格式: yyyy-MM-dd HH:mm:ss)
     * @param endTime         结束时间 (格式: yyyy-MM-dd HH:mm:ss)
     * @return 分页结果
     */
    @Operation(summary = "分页查询系统日志", description = "分页查询系统操作日志，支持按操作类型、用户、时间范围筛选")
    @GetMapping
    public ResponseEntity<Page<SystemLog>> list(
            @Parameter(name = "page", description = "页码", example = "1") @RequestParam(value = "page", defaultValue = "1") int page,
            @Parameter(name = "size", description = "每页数量", example = "20") @RequestParam(value = "size", defaultValue = "20") int size,
            @Parameter(name = "operationType", description = "操作类型", example = "CREATE") @RequestParam(value = "operationType", required = false) String operationType,
            @Parameter(name = "operationUserId", description = "操作用户ID", example = "1") @RequestParam(value = "operationUserId", required = false) Long operationUserId,
            @Parameter(name = "startTime", description = "开始时间", example = "2024-01-01 00:00:00") @RequestParam(value = "startTime", required = false) String startTime,
            @Parameter(name = "endTime", description = "结束时间", example = "2024-12-31 23:59:59") @RequestParam(value = "endTime", required = false) String endTime) {

        Page<SystemLog> result = systemLogService.getPage(
                page, size, operationType, operationUserId, startTime, endTime);
        return ResponseEntity.ok(result);
    }

    /**
     * 根据关联资源ID查询日志
     *
     * @param relatedId 关联资源ID
     * @param page      当前页
     * @param size      每页大小
     * @return 分页结果
     */
    @Operation(summary = "根据关联资源查询日志", description = "根据关联资源ID查询相关的系统日志")
    @GetMapping("/related/{relatedId}")
    public ResponseEntity<Page<SystemLog>> getByRelatedId(
            @Parameter(name = "relatedId", description = "关联资源ID", example = "1") @PathVariable("relatedId") Long relatedId,
            @Parameter(name = "page", description = "页码", example = "1") @RequestParam(value = "page", defaultValue = "1") int page,
            @Parameter(name = "size", description = "每页数量", example = "20") @RequestParam(value = "size", defaultValue = "20") int size) {

        Page<SystemLog> result = systemLogService.getByRelatedId(page, size, relatedId);
        return ResponseEntity.ok(result);
    }
}
