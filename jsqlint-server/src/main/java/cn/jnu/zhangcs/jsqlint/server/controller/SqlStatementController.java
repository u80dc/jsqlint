package cn.jnu.zhangcs.jsqlint.server.controller;

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

/**
 * SQL 语句存储控制器
 *
 * @author zhangcs
 */
@Tag(name = "SQL语句管理", description = "SQL 语句的查询、构造过程与参数传播路径")
@RestController
@RequestMapping("/api/sql-statements")
@RequiredArgsConstructor
public class SqlStatementController {

    private final SqlStatementService sqlStatementService;

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
}
