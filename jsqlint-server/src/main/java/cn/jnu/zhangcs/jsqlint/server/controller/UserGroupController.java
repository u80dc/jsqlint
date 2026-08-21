package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.entity.UserGroup;
import cn.jnu.zhangcs.jsqlint.server.model.dto.UserGroupCreateDTO;
import cn.jnu.zhangcs.jsqlint.server.model.dto.UserGroupUpdateDTO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.UserGroupVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.UserVO;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.UserGroupService;
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

/**
 * 用户组控制器
 * @author zhangcs
 */
@Tag(name = "用户组管理", description = "用户组及组成员的创建、更新、删除和查询")
@RestController
@RequestMapping("/api/user-groups")
@RequiredArgsConstructor
public class UserGroupController {

    private final UserGroupService userGroupService;

    private final UserService userService;

    @Operation(summary = "创建用户组", description = "创建一个新的用户组")
    @PostMapping
    public ResponseEntity<UserGroupVO> create(
            @Valid @RequestBody UserGroupCreateDTO dto,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        Long userId = loginUser.getUserId();

        UserGroup userGroup = new UserGroup();
        userGroup.setName(dto.getName());
        userGroup.setDescription(dto.getDescription());
        userGroup.setCreatorId(userId);

        UserGroup created = userGroupService.create(userGroup);
        return ResponseEntity.ok(toVO(created));
    }

    @Operation(summary = "更新用户组", description = "更新指定ID的用户组信息")
    @PutMapping("/{id}")
    public ResponseEntity<UserGroupVO> update(
            @Parameter(name = "id", description = "用户组ID", example = "1") @PathVariable("id") Long id,
            @Valid @RequestBody UserGroupUpdateDTO dto) {
        UserGroup userGroup = userGroupService.getById(id);
        if (userGroup == null) {
            return ResponseEntity.notFound().build();
        }

        if (dto.getName() != null) {
            userGroup.setName(dto.getName());
        }
        if (dto.getDescription() != null) {
            userGroup.setDescription(dto.getDescription());
        }
        if (dto.getStatus() != null) {
            userGroup.setStatus(dto.getStatus());
        }

        UserGroup updated = userGroupService.update(userGroup);
        return ResponseEntity.ok(toVO(updated));
    }

    @Operation(summary = "删除用户组", description = "根据用户组ID删除指定的用户组")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @Parameter(name = "id", description = "用户组ID", example = "1") @PathVariable("id") Long id) {
        userGroupService.delete(id);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "查询用户组详情", description = "根据用户组ID查询用户组的详细信息")
    @GetMapping("/{id}")
    public ResponseEntity<UserGroupVO> getById(
            @Parameter(name = "id", description = "用户组ID", example = "1") @PathVariable("id") Long id) {
        UserGroup userGroup = userGroupService.getById(id);
        if (userGroup == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toVO(userGroup));
    }

    @Operation(summary = "查询用户组列表", description = "查询所有未删除的用户组列表")
    @GetMapping
    public ResponseEntity<List<UserGroupVO>> list() {
        List<UserGroup> groups = userGroupService.listNotDeleted();
        return ResponseEntity.ok(toVOList(groups));
    }

    // ==================== 用户组成员管理接口 ====================

    @Operation(summary = "添加用户组成员", description = "向用户组中添加一个用户")
    @PostMapping("/{userGroupId}/members")
    public ResponseEntity<Void> addMember(
            @Parameter(name = "userGroupId", description = "用户组ID", example = "1") @PathVariable("userGroupId") Long userGroupId,
            @Parameter(name = "userId", description = "用户ID", example = "1") @RequestParam Long userId,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        userGroupService.addMember(userGroupId, userId);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "移除用户组成员", description = "将指定的用户从用户组中移除")
    @DeleteMapping("/{userGroupId}/members/{userId}")
    public ResponseEntity<Void> removeMember(
            @Parameter(name = "userGroupId", description = "用户组ID", example = "1") @PathVariable("userGroupId") Long userGroupId,
            @Parameter(name = "userId", description = "用户ID", example = "1") @PathVariable("userId") Long userId) {
        userGroupService.removeMember(userGroupId, userId);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "查询用户组成员", description = "查询指定用户组的所有成员列表")
    @GetMapping("/{userGroupId}/members")
    public ResponseEntity<List<UserVO>> getMembers(
            @Parameter(name = "userGroupId", description = "用户组ID", example = "1") @PathVariable("userGroupId") Long userGroupId) {
        List<User> members = userGroupService.getMembers(userGroupId);
        return ResponseEntity.ok(members.stream()
                .map(u -> UserVO.builder()
                        .id(u.getId())
                        .username(u.getAccount())
                        .realName(u.getUsername())
                        .build())
                .toList());
    }

    @Operation(summary = "查询我的用户组", description = "查询当前用户所属的所有用户组")
    @GetMapping("/my")
    public ResponseEntity<List<UserGroup>> getMyGroups(@Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        Long userId = loginUser.getUserId();
        return ResponseEntity.ok(userGroupService.getByUser(userId));
    }

    private UserGroupVO toVO(UserGroup userGroup) {
        String creatorName = null;
        if (userGroup.getCreatorId() != null) {
            User creator = userService.getById(userGroup.getCreatorId());
            if (creator != null) {
                creatorName = creator.getUsername();
            }
        }

        return buildVO(userGroup, creatorName);
    }

    private UserGroupVO toVO(UserGroup userGroup, Map<Long, String> creatorNames) {
        String creatorName = userGroup.getCreatorId() == null ? null : creatorNames.get(userGroup.getCreatorId());
        return buildVO(userGroup, creatorName);
    }

    private UserGroupVO buildVO(UserGroup userGroup, String creatorName) {
        Integer memberCount = userGroupService.getMemberCount(userGroup.getId());

        return UserGroupVO.builder()
                .id(userGroup.getId())
                .name(userGroup.getName())
                .description(userGroup.getDescription())
                .creatorId(userGroup.getCreatorId())
                .creatorName(creatorName)
                .status(userGroup.getStatus())
                .memberCount(memberCount)
                .createTime(userGroup.getCreateTime())
                .updateTime(userGroup.getUpdateTime())
                .build();
    }

    private List<UserGroupVO> toVOList(List<UserGroup> groups) {
        if (groups.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Long> creatorIds = groups.stream()
                .map(UserGroup::getCreatorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, String> creatorNames = creatorIds.isEmpty() ? Collections.emptyMap()
                : userService.getByIds(creatorIds).stream()
                        .collect(Collectors.toMap(User::getId, User::getUsername));

        return groups.stream()
                .map(group -> toVO(group, creatorNames))
                .collect(Collectors.toList());
    }
}