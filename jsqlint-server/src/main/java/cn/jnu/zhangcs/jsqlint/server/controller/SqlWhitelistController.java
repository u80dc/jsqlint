package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.entity.AuditRule;
import cn.jnu.zhangcs.jsqlint.server.entity.SqlWhitelist;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.mapper.AuditRuleMapper;
import cn.jnu.zhangcs.jsqlint.server.model.dto.SqlWhitelistDTO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.SqlWhitelistVO;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.SqlWhitelistService;
import cn.jnu.zhangcs.jsqlint.server.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * SQL 白名单控制器
 *
 * @author zhangcs
 */
@Tag(name = "SQL白名单管理", description = "项目 SQL 白名单的新增、查询和删除")
@RestController
@RequestMapping("/api/project/{projectId}/whitelist")
@RequiredArgsConstructor
public class SqlWhitelistController {

    private final SqlWhitelistService sqlWhitelistService;
    private final AuditRuleMapper auditRuleMapper;
    private final UserService userService;

    @Operation(summary = "查询白名单列表", description = "分页查询指定项目的 SQL 白名单")
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable("projectId") Long projectId,
            @Parameter(name = "page", description = "页码", example = "1") @RequestParam(value = "page", defaultValue = "1") int page,
            @Parameter(name = "pageSize", description = "每页数量", example = "10") @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
            @Parameter(name = "ruleId", description = "规则ID（可选过滤）") @RequestParam(value = "ruleId", required = false) Long ruleId) {
        List<SqlWhitelist> list = sqlWhitelistService.list(projectId, page, pageSize, ruleId);
        long total = sqlWhitelistService.count(projectId, ruleId);
        return ResponseEntity.ok(Map.of(
                "list", toVOList(list),
                "total", total
        ));
    }

    @Operation(summary = "新增白名单", description = "为指定项目新增一条 SQL 白名单记录")
    @PostMapping
    public ResponseEntity<SqlWhitelistVO> create(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable("projectId") Long projectId,
            @Valid @RequestBody SqlWhitelistDTO dto,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        SqlWhitelist created = sqlWhitelistService.create(projectId, dto, loginUser.getUserId());
        return ResponseEntity.ok(toVO(created));
    }

    @Operation(summary = "删除白名单", description = "删除指定项目下某条 SQL 白名单记录")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable("projectId") Long projectId,
            @Parameter(name = "id", description = "白名单ID", example = "1") @PathVariable("id") Long id) {
        sqlWhitelistService.delete(projectId, id);
        return ResponseEntity.ok().build();
    }

    private SqlWhitelistVO toVO(SqlWhitelist entity) {
        SqlWhitelistVO vo = new SqlWhitelistVO();
        vo.setId(entity.getId());
        vo.setProjectId(entity.getProjectId());
        vo.setSqlFingerprint(entity.getSqlFingerprint());
        vo.setSqlSample(entity.getSqlSample());
        vo.setRuleId(entity.getRuleId());
        vo.setRuleName(getRuleName(entity.getRuleId()));
        vo.setReason(entity.getReason());
        vo.setCreateUserName(getUserName(entity.getCreateUserId()));
        vo.setCreateTime(entity.getCreateTime());
        return vo;
    }

    private List<SqlWhitelistVO> toVOList(List<SqlWhitelist> list) {
        if (list.isEmpty()) {
            return Collections.emptyList();
        }
        return list.stream()
                .map(this::toVO)
                .toList();
    }

    private String getRuleName(Long ruleId) {
        if (ruleId == null) {
            return null;
        }
        AuditRule rule = auditRuleMapper.selectById(ruleId);
        return rule != null ? rule.getName() : null;
    }

    private String getUserName(Long userId) {
        if (userId == null) {
            return null;
        }
        User user = userService.getById(userId);
        return user != null ? user.getUsername() : null;
    }
}
