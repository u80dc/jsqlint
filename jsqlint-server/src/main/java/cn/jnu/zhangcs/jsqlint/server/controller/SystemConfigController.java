package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.model.dto.SystemSettingsDTO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.SystemSettingsVO;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.SystemConfigService;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/system")
public class SystemConfigController {

    private final SystemConfigService systemConfigService;

    @GetMapping("/settings")
    public ResponseEntity<SystemSettingsVO> getSettings() {
        return ResponseEntity.ok(systemConfigService.getSettings());
    }

    @PutMapping("/settings")
    public ResponseEntity<SystemSettingsVO> saveSettings(
            @Valid @RequestBody SystemSettingsDTO dto,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        Long operatorId = loginUser == null ? null : loginUser.getUserId();
        return ResponseEntity.ok(systemConfigService.saveSettings(dto, operatorId));
    }

    @PostMapping("/settings/reset")
    public ResponseEntity<SystemSettingsVO> resetSettings(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        Long operatorId = loginUser == null ? null : loginUser.getUserId();
        return ResponseEntity.ok(systemConfigService.resetSettings(operatorId));
    }
}
