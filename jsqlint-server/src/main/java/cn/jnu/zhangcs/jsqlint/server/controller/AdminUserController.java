package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.converter.EntityConverter;
import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.entity.UserGroup;
import cn.jnu.zhangcs.jsqlint.server.model.dto.UserCreateDTO;
import cn.jnu.zhangcs.jsqlint.server.model.dto.UserUpdateDTO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.UserGroupVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.UserVO;
import cn.jnu.zhangcs.jsqlint.server.service.UserGroupService;
import cn.jnu.zhangcs.jsqlint.server.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 管理员用户控制器
 *
 * @author zhangcs
 */
@Tag(name = "管理员用户管理", description = "管理员对用户的创建、更新、删除、启用/禁用及用户组分配等操作")
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final EntityConverter converter;

    private final PasswordEncoder passwordEncoder;

    private final UserGroupService userGroupService;

    private final UserService userService;

    @PostMapping
    @Operation(summary = "创建用户", description = "管理员创建一个新的用户账号")
    public ResponseEntity<Map<String, Object>> createUser(@Valid @RequestBody UserCreateDTO dto) {
        User user = converter.fromCreateDTO(dto);
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        userService.create(user);
        return ResponseEntity.ok(Map.of("message", "用户创建成功"));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新用户", description = "管理员更新用户信息（可包含密码重置）")
    public ResponseEntity<Map<String, Object>> updateUser(
            @Parameter(name = "id", description = "用户ID", example = "1") @PathVariable("id") Long id,
            @Valid @RequestBody UserUpdateDTO dto) {
        User user = userService.getById(id);
        if (user == null) {
            throw new BizException("用户不存在");
        }
        converter.updateFromDTO(user, dto);
        if (dto.getPassword() != null && !dto.getPassword().isEmpty()) {
            user.setPassword(passwordEncoder.encode(dto.getPassword()));
        }
        userService.updateById(id, user);
        return ResponseEntity.ok(Map.of("message", "用户更新成功"));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除用户", description = "管理员删除指定的用户账号")
    public ResponseEntity<Map<String, Object>> deleteUser(
            @Parameter(name = "id", description = "用户ID", example = "1") @PathVariable("id") Long id) {
        userService.delete(id);
        return ResponseEntity.ok(Map.of("message", "用户删除成功"));
    }

    @GetMapping
    @Operation(summary = "查询用户列表", description = "管理员查询用户列表，支持按用户名、真实姓名、手机号、邮箱、状态筛选")
    public ResponseEntity<List<UserVO>> listUsers(
            @Parameter(name = "username", description = "用户名", example = "admin") @RequestParam(value = "username", required = false) String username,
            @Parameter(name = "realName", description = "真实姓名", example = "张三") @RequestParam(value = "realName", required = false) String realName,
            @Parameter(name = "phone", description = "手机号", example = "13800138000") @RequestParam(value = "phone", required = false) String phone,
            @Parameter(name = "email", description = "邮箱", example = "admin@example.com") @RequestParam(value = "email", required = false) String email,
            @Parameter(name = "status", description = "状态（1:启用, 0:禁用）", example = "1") @RequestParam(value = "status", required = false) Integer status) {
        List<User> users = userService.getUserList(username, realName, phone, email, status);
        return ResponseEntity.ok(toVOList(users));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询用户详情", description = "管理员根据用户ID查询用户的详细信息")
    public ResponseEntity<UserVO> getUserById(
            @Parameter(name = "id", description = "用户ID", example = "1") @PathVariable(value = "id") Long id) {
        User user = userService.getById(id);
        return ResponseEntity.ok(converter.toUserVO(user));
    }

    @PatchMapping("/{id}/enabled")
    @Operation(summary = "设置用户启用状态", description = "管理员启用或禁用指定的用户账号")
    public ResponseEntity<Map<String, Object>> setUserEnabled(
            @Parameter(name = "id", description = "用户ID", example = "1") @PathVariable("id") Long id,
            @Parameter(name = "enabled", description = "是否启用", example = "true") @RequestParam("enabled") boolean enabled) {
        if (enabled) {
            userService.enable(id);
        } else {
            userService.disable(id);
        }
        return ResponseEntity.ok(Map.of("message", enabled ? "用户启用成功" : "用户禁用成功"));
    }

    @PutMapping("/{userId}/groups")
    @Operation(summary = "设置用户组", description = "管理员为用户分配或移除用户组")
    public ResponseEntity<Map<String, Object>> setUserGroups(
            @Parameter(name = "userId", description = "用户ID", example = "1") @PathVariable("userId") Long userId,
            @Parameter(name = "groupId", description = "用户组ID（null表示移除所有用户组）", example = "1") @RequestParam(value = "groupId", required = false) Long groupId) {
        if (groupId != null) {
            userGroupService.addMember(groupId, userId);
        } else {
            List<UserGroup> currentGroups = userGroupService.getByUser(userId);
            for (UserGroup group : currentGroups) {
                userGroupService.removeMember(group.getId(), userId);
            }
        }
        return ResponseEntity.ok(Map.of("message", "用户组设置成功"));
    }

    private List<UserVO> toVOList(List<User> users) {
        if (users.isEmpty()) {
            return Collections.emptyList();
        }
        return users.stream()
                .map(user -> {
                    UserVO vo = converter.toUserVO(user);
                    List<UserGroup> groups = userGroupService.getByUser(user.getId());
                    vo.setGroups(groups.stream()
                            .map(g -> UserGroupVO.builder()
                                    .id(g.getId())
                                    .name(g.getName())
                                    .build())
                            .toList());
                    return vo;
                })
                .toList();
    }

}
