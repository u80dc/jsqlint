package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.entity.InspectionConfig;
import cn.jnu.zhangcs.jsqlint.server.model.dto.InspectionConfigDTO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.InspectionConfigVO;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.InspectionConfigService;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/inspection-configs")
public class InspectionConfigController {

    private final InspectionConfigService inspectionConfigService;

    @GetMapping
    public ResponseEntity<List<InspectionConfigVO>> list(
            @RequestParam(value = "enabled", required = false) Boolean enabled) {
        return ResponseEntity.ok(inspectionConfigService.list(enabled).stream()
                .map(this::toVO)
                .toList());
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<InspectionConfigVO> getByProject(@PathVariable Long projectId) {
        InspectionConfig config = inspectionConfigService.getByProjectId(projectId);
        if (config == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toVO(config));
    }

    @PutMapping("/{projectId}")
    public ResponseEntity<InspectionConfigVO> saveOrUpdate(
            @PathVariable Long projectId,
            @Valid @RequestBody InspectionConfigDTO dto,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        InspectionConfig config = inspectionConfigService.saveOrUpdate(
                projectId,
                dto.getCronExpr(),
                dto.getEnabled(),
                loginUser.getUserId());
        return ResponseEntity.ok(toVO(config));
    }

    @PatchMapping("/{projectId}/enabled")
    public ResponseEntity<Void> setEnabled(
            @PathVariable Long projectId,
            @RequestParam(value = "enabled")  boolean enabled,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        inspectionConfigService.setEnabled(projectId, enabled, loginUser.getUserId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<Void> delete(
            @PathVariable Long projectId,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        inspectionConfigService.deleteByProjectId(projectId, loginUser.getUserId());
        return ResponseEntity.noContent().build();
    }

    private InspectionConfigVO toVO(InspectionConfig config) {
        return InspectionConfigVO.builder()
                .id(config.getId())
                .projectId(config.getProjectId())
                .projectName(config.getProjectName())
                .cronExpr(config.getCronExpr())
                .enabled(config.getEnabled() != null && config.getEnabled() == 1)
                .nextExecTime(config.getNextExecTime())
                .lastExecTime(config.getLastExecTime())
                .lastStatus(config.getLastStatus())
                .lastMessage(config.getLastMessage())
                .operatorId(config.getOperatorId())
                .createTime(config.getCreateTime())
                .updateTime(config.getUpdateTime())
                .build();
    }
}
