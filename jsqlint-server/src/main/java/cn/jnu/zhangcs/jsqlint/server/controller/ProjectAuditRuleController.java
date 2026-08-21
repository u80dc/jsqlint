package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.converter.AuditRuleConverter;
import cn.jnu.zhangcs.jsqlint.server.entity.AuditRule;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.model.dto.ProjectAuditRuleDTO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.AuditRuleVO;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectAuditRuleService;
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
 * 项目审核规则控制器
 */
@Tag(name = "项目审核规则管理", description = "项目特有 SQL 审核规则的新增、查询、修改和删除")
@RestController
@RequestMapping("/api/project/{projectId}/rules")
@RequiredArgsConstructor
public class ProjectAuditRuleController {

    private final UserService userService;
    private final ProjectAuditRuleService projectAuditRuleService;

    @Operation(summary = "查询项目规则列表", description = "分页查询指定项目的特有审核规则")
    @GetMapping
    public ResponseEntity<Map<String, Object>> listRules(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable("projectId") Long projectId,
            @Parameter(name = "page", description = "页码", example = "1") @RequestParam(value = "page", defaultValue = "1") int page,
            @Parameter(name = "pageSize", description = "每页数量", example = "10") @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
            @Parameter(name = "name", description = "规则名称（模糊匹配）", example = "SQL注入") @RequestParam(value = "name", required = false) String name,
            @Parameter(name = "category", description = "规则类别", example = "SECURITY") @RequestParam(value = "category", required = false) String category,
            @Parameter(name = "enabled", description = "是否启用", example = "true") @RequestParam(value = "enabled", required = false) Boolean enabled) {
        List<AuditRule> rules = projectAuditRuleService.listRules(projectId, page, pageSize, name, category, enabled);
        long total = projectAuditRuleService.countRules(projectId, name, category, enabled);
        return ResponseEntity.ok(Map.of(
                "list", toVOList(rules),
                "total", total
        ));
    }

    @Operation(summary = "查询项目规则详情", description = "查询指定项目下某条项目特有审核规则")
    @GetMapping("/{id}")
    public ResponseEntity<AuditRuleVO> getRule(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable("projectId") Long projectId,
            @Parameter(name = "id", description = "规则ID", example = "1") @PathVariable("id") Long id) {
        AuditRule rule = projectAuditRuleService.getRule(projectId, id);
        if (rule == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toVO(rule));
    }

    @Operation(summary = "新增项目规则", description = "为指定项目新增一条项目特有 SQL 审核规则")
    @PostMapping
    public ResponseEntity<AuditRuleVO> createRule(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable("projectId") Long projectId,
            @Valid @RequestBody ProjectAuditRuleDTO dto,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        AuditRule created = projectAuditRuleService.createRule(projectId, dto, loginUser.getUserId());
        return ResponseEntity.ok(toVO(created));
    }

    @Operation(summary = "修改项目规则", description = "修改指定项目下某条项目特有审核规则，未传字段保持不变")
    @PutMapping("/{id}")
    public ResponseEntity<AuditRuleVO> updateRule(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable("projectId") Long projectId,
            @Parameter(name = "id", description = "规则ID", example = "1") @PathVariable("id") Long id,
            @Valid @RequestBody ProjectAuditRuleDTO dto,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        AuditRule updated = projectAuditRuleService.updateRule(projectId, id, dto, loginUser.getUserId());
        return ResponseEntity.ok(toVO(updated));
    }

    @Operation(summary = "删除项目规则", description = "删除指定项目下某条项目特有审核规则")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRule(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable("projectId") Long projectId,
            @Parameter(name = "id", description = "规则ID", example = "1") @PathVariable("id") Long id) {
        projectAuditRuleService.deleteRule(projectId, id);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "切换项目规则状态", description = "启用或禁用指定项目下某条项目特有审核规则")
    @PutMapping("/{id}/toggle")
    public ResponseEntity<AuditRuleVO> toggleRule(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable("projectId") Long projectId,
            @Parameter(name = "id", description = "规则ID", example = "1") @PathVariable("id") Long id,
            @RequestBody Map<String, Boolean> body,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        AuditRule updated = projectAuditRuleService.toggleRule(projectId, id, Boolean.TRUE.equals(body.get("enabled")), loginUser.getUserId());
        return ResponseEntity.ok(toVO(updated));
    }

    private AuditRuleVO toVO(AuditRule rule) {
        return AuditRuleConverter.toVO(rule, getUserName(rule.getCreateUserId()));
    }

    private List<AuditRuleVO> toVOList(List<AuditRule> rules) {
        if (rules.isEmpty()) {
            return Collections.emptyList();
        }
        return rules.stream()
                .map(this::toVO)
                .toList();
    }

    private String getUserName(Long userId) {
        if (userId == null) {
            return null;
        }
        User user = userService.getById(userId);
        return user != null ? user.getUsername() : null;
    }

}
