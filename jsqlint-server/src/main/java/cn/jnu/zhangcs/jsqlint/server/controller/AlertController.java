package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.audit.model.RiskLevel;
import cn.jnu.zhangcs.jsqlint.server.common.enums.AlertRuleType;
import cn.jnu.zhangcs.jsqlint.server.common.enums.AlertStatus;
import cn.jnu.zhangcs.jsqlint.server.entity.AlertInstance;
import cn.jnu.zhangcs.jsqlint.server.entity.AlertRule;
import cn.jnu.zhangcs.jsqlint.server.entity.Project;
import cn.jnu.zhangcs.jsqlint.server.model.dto.AlertRuleDTO;
import cn.jnu.zhangcs.jsqlint.server.model.dto.AlertRuleUpdateDTO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.AlertInstanceVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.AlertRuleVO;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.AlertService;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "告警管理", description = "告警规则配置、告警实例查询和处理")
@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final AlertService alertService;

    private final ProjectService projectService;

    @Operation(summary = "查询告警规则")
    @GetMapping("/rules")
    public ResponseEntity<List<AlertRuleVO>> listRules(@RequestParam(required = false) Long projectId,
                                                       @RequestParam(required = false) String ruleType,
                                                       @RequestParam(required = false) Boolean enabled) {
        return ResponseEntity.ok(alertService.getRules(projectId, ruleType, enabled).stream()
                .map(this::toRuleVO)
                .toList());
    }

    @Operation(summary = "创建告警规则")
    @PostMapping("/rules")
    public ResponseEntity<AlertRuleVO> createRule(@Valid @RequestBody AlertRuleDTO dto,
                                                  @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(toRuleVO(alertService.addRule(dto, loginUser.getUserId())));
    }

    @Operation(summary = "更新告警规则")
    @PutMapping("/rules/{id}")
    public ResponseEntity<AlertRuleVO> updateRule(@PathVariable Long id,
                                                  @Valid @RequestBody AlertRuleUpdateDTO dto) {
        return ResponseEntity.ok(toRuleVO(alertService.updateRule(id, dto)));
    }

    @Operation(summary = "删除告警规则")
    @DeleteMapping("/rules/{id}")
    public ResponseEntity<Map<String, Object>> deleteRule(@PathVariable Long id) {
        alertService.deleteRule(id);
        return ResponseEntity.ok(Map.of("message", "删除成功"));
    }

    @Operation(summary = "启用或禁用告警规则")
    @PutMapping("/rules/{id}/toggle")
    public ResponseEntity<AlertRuleVO> toggleRule(@PathVariable Long id,
                                                  @RequestBody Map<String, Boolean> body) {
        return ResponseEntity.ok(toRuleVO(alertService.toggleRule(id, Boolean.TRUE.equals(body.get("enabled")))));
    }

    @Operation(summary = "查询告警实例")
    @GetMapping
    public ResponseEntity<List<AlertInstanceVO>> listInstances(@RequestParam(required = false) Long projectId,
                                                               @RequestParam(required = false) String severity,
                                                               @RequestParam(required = false) String status) {
        return ResponseEntity.ok(alertService.getInstances(projectId, severity, status).stream()
                .map(this::toInstanceVO)
                .toList());
    }

    @Operation(summary = "更新告警状态")
    @PutMapping("/{id}/status")
    public ResponseEntity<Map<String, Object>> updateStatus(@PathVariable Long id,
                                                            @RequestBody Map<String, String> body,
                                                            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        alertService.updateInstanceStatus(id, body.get("status"), loginUser.getUserId());
        return ResponseEntity.ok(Map.of("message", "更新成功"));
    }

    private AlertRuleVO toRuleVO(AlertRule rule) {
        Project project = rule.getProjectId() == null ? null : projectService.getById(rule.getProjectId());
        AlertRuleType type = AlertRuleType.from(rule.getRuleType());
        return AlertRuleVO.builder()
                .id(rule.getId())
                .name(rule.getName())
                .ruleType(rule.getRuleType())
                .ruleTypeDescription(type.getDesc())
                .projectId(rule.getProjectId())
                .projectName(project == null ? "全部项目" : project.getName())
                .severity(rule.getSeverity())
                .severityDescription(RiskLevel.from(rule.getSeverity()).getDescription())
                .minSeverity(rule.getMinSeverity())
                .minSeverityDescription(RiskLevel.from(rule.getMinSeverity()).getDescription())
                .thresholdCount(rule.getThresholdCount())
                .notifyProjectMembers(Integer.valueOf(1).equals(rule.getNotifyProjectMembers()))
                .notifyCreator(Integer.valueOf(1).equals(rule.getNotifyCreator()))
                .enabled(rule.getEnabled())
                .enabledFlag(Integer.valueOf(1).equals(rule.getEnabled()))
                .createTime(rule.getCreateTime())
                .updateTime(rule.getUpdateTime())
                .build();
    }

    private AlertInstanceVO toInstanceVO(AlertInstance instance) {
        AlertRule rule = instance.getRuleId() == null ? null : alertService.getRules(null, null, null).stream()
                .filter(item -> instance.getRuleId().equals(item.getId()))
                .findFirst()
                .orElse(null);
        Project project = instance.getProjectId() == null ? null : projectService.getById(instance.getProjectId());
        AlertStatus status = AlertStatus.from(instance.getStatus());
        return AlertInstanceVO.builder()
                .id(instance.getId())
                .ruleId(instance.getRuleId())
                .ruleName(rule == null ? null : rule.getName())
                .projectId(instance.getProjectId())
                .projectName(project == null ? null : project.getName())
                .versionId(instance.getVersionId())
                .taskId(instance.getTaskId())
                .title(instance.getTitle())
                .content(instance.getContent())
                .severity(instance.getSeverity())
                .severityDescription(RiskLevel.from(instance.getSeverity()).getDescription())
                .status(instance.getStatus())
                .statusDescription(status.getDesc())
                .triggerCount(instance.getTriggerCount())
                .firstTriggeredAt(instance.getFirstTriggeredAt())
                .lastTriggeredAt(instance.getLastTriggeredAt())
                .ackTime(instance.getAckTime())
                .resolvedTime(instance.getResolvedTime())
                .createTime(instance.getCreateTime())
                .build();
    }
}
