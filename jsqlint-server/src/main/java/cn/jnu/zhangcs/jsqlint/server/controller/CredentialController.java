package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.converter.CredentialConverter;
import cn.jnu.zhangcs.jsqlint.server.common.enums.Visibility;
import cn.jnu.zhangcs.jsqlint.server.entity.Credential;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.model.dto.CredentialDTO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.CredentialVO;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.CredentialService;
import cn.jnu.zhangcs.jsqlint.server.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@Tag(name = "凭证管理", description = "Git 凭证（账号密码/Token）的创建、更新、删除和查询")
@RestController
@RequestMapping("/api/credentials")
@RequiredArgsConstructor
public class CredentialController {

    private final UserService userService;

    private final CredentialService credentialService;

    @Operation(summary = "创建凭证", description = "创建一个新的 Git 凭证，用于访问私有仓库，Token 和密码至少需要配置一个")
    @PostMapping
    public ResponseEntity<CredentialVO> createCredential(
            @Valid @RequestBody CredentialDTO credentialDTO,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        Long userId = loginUser.getUserId();

        String token = credentialDTO.getToken();
        String password = credentialDTO.getPassword();
        if ((token == null || token.isBlank()) && (password == null || password.isBlank())) {
            throw new IllegalArgumentException("Token和密码至少需要配置一个");
        }

        Credential credential = new Credential();
        credential.setName(credentialDTO.getName());
        credential.setVisibility(credentialDTO.getVisibility() != null ? credentialDTO.getVisibility() : Visibility.PRIVATE.getValue());
        credential.setStatus(credentialDTO.getStatus() != null ? credentialDTO.getStatus() : 1);
        credential.setAccount(credentialDTO.getAccount());
        credential.setToken(credentialDTO.getToken());
        credential.setPassword(credentialDTO.getPassword());
        credential.setCreateUserId(userId);
        Credential created = credentialService.createCredential(credential);
        return ResponseEntity.ok(CredentialConverter.toVO(created, getUserName(created.getCreateUserId())));
    }

    @Operation(summary = "更新凭证", description = "更新指定ID的凭证信息")
    @PutMapping("/{id}")
    public ResponseEntity<CredentialVO> updateCredential(
            @Parameter(name = "id", description = "凭证ID", example = "1") @PathVariable(value = "id") Long id,
            @RequestBody CredentialDTO credentialDTO) {
        Credential existing = credentialService.getCredentialById(id);
        if (existing == null || existing.getStatus() == -1) {
            throw new IllegalArgumentException("凭证不存在");
        }

        CredentialConverter.updateEntity(existing, credentialDTO);
        Credential updated = credentialService.updateCredential(existing);
        return ResponseEntity.ok(CredentialConverter.toVO(updated, getUserName(updated.getCreateUserId())));
    }

    @Operation(summary = "删除凭证", description = "根据凭证ID删除指定的凭证")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCredential(
            @Parameter(name = "id", description = "凭证ID", example = "1") @PathVariable(value = "id") Long id) {
        credentialService.deleteCredential(id);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "查询凭证详情", description = "根据凭证ID查询凭证的详细信息")
    @GetMapping("/{id}")
    public ResponseEntity<CredentialVO> getCredential(
            @Parameter(name = "id", description = "凭证ID", example = "1") @PathVariable(value = "id") Long id) {
        Credential credential = credentialService.getCredentialById(id);
        if (credential == null || credential.getStatus() == -1) {
            throw new IllegalArgumentException("凭证不存在");
        }
        return ResponseEntity.ok(CredentialConverter.toVO(credential, getUserName(credential.getCreateUserId())));
    }

    @Operation(summary = "查询凭证列表", description = "查询所有凭证列表，可根据状态筛选")
    @GetMapping
    public ResponseEntity<List<CredentialVO>> listCredentials(
            @Parameter(name = "status", description = "凭证状态（1:启用, 0:禁用）", example = "1") @RequestParam(value = "status", required = false) Integer status) {
        List<Credential> list = credentialService.listCredentials(status);
        return ResponseEntity.ok(toVOList(list));
    }

    @Operation(summary = "切换凭证状态", description = "启用或禁用指定的凭证")
    @PutMapping("/{id}/toggle")
    public ResponseEntity<CredentialVO> toggleEnabled(
            @Parameter(name = "id", description = "凭证ID", example = "1") @PathVariable(value = "id") Long id,
            @RequestBody Map<String, Integer> body) {
        Integer status = body.get("status");
        Credential credential = credentialService.toggleEnabled(id, status);
        return ResponseEntity.ok(CredentialConverter.toVO(credential, getUserName(credential.getCreateUserId())));
    }

    private String getUserName(Long userId) {
        if (userId == null) {
            return null;
        }
        return userService.getById(userId).getUsername();
    }

    public List<CredentialVO> toVOList(List<Credential> credentials) {
        if (credentials.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Long> userIds = credentials.stream()
                .map(Credential::getCreateUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, String> userNames = userIds.isEmpty() ? Collections.emptyMap()
                : userService.getByIds(userIds).stream()
                  .collect(Collectors.toMap(User::getId, User::getUsername));

        return credentials.stream()
                .map(c -> CredentialConverter.toVO(c, userNames.get(c.getCreateUserId())))
                .toList();
    }
}