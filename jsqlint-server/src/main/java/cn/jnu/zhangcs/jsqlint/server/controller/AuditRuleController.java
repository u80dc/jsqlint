package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.converter.AuditRuleConverter;
import cn.jnu.zhangcs.jsqlint.server.entity.AuditRule;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.model.dto.AuditRuleDTO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.AuditRuleVO;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.AuditRuleService;
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
 * 审核规则控制器
 */
@Tag(name = "审核规则管理", description = "SQL 审核规则（Drools 规则）的创建、更新、删除、查询和验证")
@RestController
@RequestMapping("/api/audit-rules")
@RequiredArgsConstructor
public class AuditRuleController {

    private final UserService userService;
    private final AuditRuleService auditRuleService;

    @Operation(summary = "查询规则列表", description = "分页查询审核规则列表，支持按名称、类别和启用状态筛选")
    @GetMapping
    public ResponseEntity<Map<String, Object>> listRules(
            @Parameter(name = "page", description = "页码", example = "1") @RequestParam(value = "page", defaultValue = "1") int page,
            @Parameter(name = "pageSize", description = "每页数量", example = "10") @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
            @Parameter(name = "name", description = "规则名称（模糊匹配）", example = "SQL注入") @RequestParam(value = "name", required = false) String name,
            @Parameter(name = "category", description = "规则类别", example = "SECURITY") @RequestParam(value = "category", required = false) String category,
            @Parameter(name = "enabled", description = "是否启用", example = "true") @RequestParam(value = "enabled", required = false) Boolean enabled) {

        List<AuditRule> list = auditRuleService.listRules(page, pageSize, name, category, enabled);
        long total = auditRuleService.countRules(name, category, enabled);

        return ResponseEntity.ok(Map.of(
                "list", toVOList(list),
                "total", total
        ));
    }

    @Operation(summary = "查询规则详情", description = "根据规则ID查询审核规则的详细信息")
    @GetMapping("/{id}")
    public ResponseEntity<AuditRuleVO> getRule(
            @Parameter(name = "id", description = "规则ID", example = "1") @PathVariable(value = "id") Long id) {
        AuditRule rule = auditRuleService.getRuleById(id);
        if (!isGlobalRule(rule)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toVO(rule));
    }

    @Operation(summary = "创建规则", description = "创建一个新的 SQL 审核规则")
    @PostMapping
    public ResponseEntity<AuditRuleVO> createRule(
            @Valid @RequestBody AuditRuleDTO dto,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        Long userId = loginUser.getUserId();

        AuditRule rule = AuditRuleConverter.toEntity(dto);
        AuditRule created = auditRuleService.createRule(rule, userId);
        return ResponseEntity.ok(toVO(created));
    }

    @Operation(summary = "更新规则", description = "更新指定ID的审核规则信息")
    @PutMapping("/{id}")
    public ResponseEntity<AuditRuleVO> updateRule(
            @Parameter(name = "id", description = "规则ID", example = "1") @PathVariable(value = "id") Long id,
            @RequestBody AuditRuleDTO dto) {
        AuditRule existing = auditRuleService.getRuleById(id);
        if (!isGlobalRule(existing)) {
            return ResponseEntity.notFound().build();
        }

        AuditRuleDTO mergedDto = new AuditRuleDTO();
        mergedDto.setName(dto.getName() != null ? dto.getName() : existing.getName());
        mergedDto.setCode(existing.getCode()); // 保持原有代码不变
        mergedDto.setDescription(dto.getDescription());
        mergedDto.setSeverity(dto.getSeverity() != null ? dto.getSeverity() : existing.getSeverity());
        mergedDto.setCategory(dto.getCategory() != null ? dto.getCategory() : existing.getCategory());
        mergedDto.setDroolsContent(dto.getDroolsContent() != null ? dto.getDroolsContent() : existing.getDroolsContent());
        mergedDto.setSuggestion(dto.getSuggestion() != null ? dto.getSuggestion() : existing.getSuggestion());

        AuditRule updated = auditRuleService.updateRule(id, AuditRuleConverter.toEntity(mergedDto));
        return ResponseEntity.ok(toVO(updated));
    }

    @Operation(summary = "删除规则", description = "根据规则ID删除指定的审核规则")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRule(
            @Parameter(name = "id", description = "规则ID", example = "1") @PathVariable(value = "id") Long id) {
        AuditRule existing = auditRuleService.getRuleById(id);
        if (!isGlobalRule(existing)) {
            return ResponseEntity.notFound().build();
        }
        auditRuleService.deleteRule(id);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "切换规则状态", description = "启用或禁用指定的审核规则")
    @PutMapping("/{id}/toggle")
    public ResponseEntity<AuditRuleVO> toggleRule(
            @Parameter(name = "id", description = "规则ID", example = "1") @PathVariable(value = "id") Long id,
            @RequestBody Map<String, Boolean> body) {
        AuditRule existing = auditRuleService.getRuleById(id);
        if (!isGlobalRule(existing)) {
            return ResponseEntity.notFound().build();
        }
        Boolean enabled = body.get("enabled");
        auditRuleService.toggleRule(id, enabled);
        AuditRule rule = auditRuleService.getRuleById(id);
        return ResponseEntity.ok(toVO(rule));
    }

    @Operation(summary = "验证规则", description = "验证 Drools 规则内容的语法是否正确")
    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validateRule(@RequestBody Map<String, String> body) {
        String ruleContent = body.get("ruleContent");
        boolean valid = auditRuleService.validateRule(ruleContent);
        return ResponseEntity.ok(Map.of("valid", valid));
    }

    @Operation(summary = "校验启用规则", description = "校验数据库中所有启用的审核规则能否被审核工具编译")
    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reloadRules() {
        auditRuleService.reloadRules();
        return ResponseEntity.ok(Map.of("success", true, "message", "启用规则校验成功"));
    }

    private AuditRuleVO toVO(AuditRule rule) {
        return AuditRuleConverter.toVO(rule, getUserName(rule.getCreateUserId()));
    }

    private boolean isGlobalRule(AuditRule rule) {
        return rule != null && rule.getProjectId() == null;
    }

    private List<AuditRuleVO> toVOList(List<AuditRule> rules) {
        if (rules.isEmpty()) {
            return Collections.emptyList();
        }

        List<AuditRuleVO> voList = rules.stream()
                .map(rule -> {
                    String userName = getUserName(rule.getCreateUserId());
                    return AuditRuleConverter.toVO(rule, userName);
                })
                .toList();

        return voList;
    }

    private String getUserName(Long userId) {
        if (userId == null) {
            return null;
        }
        User user = userService.getById(userId);
        return user != null ? user.getUsername() : null;
    }
}
