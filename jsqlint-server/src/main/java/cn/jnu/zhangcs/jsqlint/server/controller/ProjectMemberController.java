package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.ProjectMember;
import cn.jnu.zhangcs.jsqlint.server.model.vo.ProjectMemberVO;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 项目成员控制器
 *
 * <p>写操作（添加、移除、角色变更）要求当前用户为项目管理员。</p>
 *
 * @author zhangcs
 */
@Tag(name = "项目成员管理", description = "项目成员的添加、删除、角色管理等操作")
@RestController
@RequestMapping("/api/projects/{projectId}/members")
@RequiredArgsConstructor
public class ProjectMemberController {

    private final ProjectService projectService;

    @Operation(summary = "添加项目成员", description = "向项目中添加一个成员，可指定是否为管理员（需项目管理员权限）")
    @PostMapping
    public ResponseEntity<Void> addMember(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable("projectId") Long projectId,
            @Parameter(name = "userId", description = "用户ID", example = "1") @RequestParam Long userId,
            @Parameter(name = "isAdmin", description = "是否为管理员", example = "false") @RequestParam(defaultValue = "false") boolean isAdmin,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        requireProjectAdmin(projectId, loginUser);
        projectService.addMember(projectId, userId, isAdmin);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "批量添加项目成员", description = "批量向项目中添加多个成员（需项目管理员权限）")
    @PostMapping("/batch")
    public ResponseEntity<Void> addMembers(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable("projectId") Long projectId,
            @RequestBody List<Long> userIds,
            @Parameter(name = "isAdmin", description = "是否为管理员", example = "false") @RequestParam(defaultValue = "false") boolean isAdmin,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        requireProjectAdmin(projectId, loginUser);
        projectService.addMembers(projectId, userIds, isAdmin);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "按用户组添加成员", description = "将指定用户组的所有成员添加到项目中（需项目管理员权限）")
    @PostMapping("/group")
    public ResponseEntity<Void> addGroupMembers(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable("projectId") Long projectId,
            @Parameter(name = "groupId", description = "用户组ID", example = "1") @RequestParam(value = "groupId") Long groupId,
            @Parameter(name = "isAdmin", description = "是否为管理员", example = "false") @RequestParam(value = "isAdmin", defaultValue = "false") boolean isAdmin,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        requireProjectAdmin(projectId, loginUser);
        projectService.addMembersByGroup(projectId, groupId, isAdmin);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "移除项目成员", description = "将指定的成员从项目中移除（需项目管理员权限）")
    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> removeMember(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable("projectId") Long projectId,
            @Parameter(name = "userId", description = "用户ID", example = "1") @PathVariable("userId") Long userId,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        requireProjectAdmin(projectId, loginUser);
        projectService.removeMember(projectId, userId);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "更新成员角色", description = "更新项目成员的角色权限（普通成员/管理员，需项目管理员权限）")
    @PutMapping("/{userId}/role")
    public ResponseEntity<Void> updateRole(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable("projectId") Long projectId,
            @Parameter(name = "userId", description = "用户ID", example = "1") @PathVariable("userId") Long userId,
            @Parameter(name = "isAdmin", description = "是否为管理员", example = "true") @RequestParam boolean isAdmin,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        requireProjectAdmin(projectId, loginUser);
        projectService.updateMemberRole(projectId, userId, isAdmin);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "查询项目成员列表", description = "查询指定项目的所有成员列表")
    @GetMapping
    public ResponseEntity<List<ProjectMemberVO>> getMembers(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable("projectId") Long projectId) {
        List<ProjectMember> members = projectService.getMembers(projectId);
        return ResponseEntity.ok(members.stream()
                .map(m -> ProjectMemberVO.builder()
                        .id(m.getId())
                        .projectId(m.getProjectId())
                        .userId(m.getUserId())
                        .account(m.getAccount())
                        .username(m.getUsername())
                        .role(m.getRole())
                        .roleDescription(m.getRoleEnum().getDesc())
                        .joinTime(m.getJoinTime())
                        .build())
                .toList());
    }

    @Operation(summary = "检查是否为项目管理员", description = "检查当前用户是否为指定项目的管理员")
    @GetMapping("/check")
    public ResponseEntity<Boolean> checkIsAdmin(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable("projectId") Long projectId,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        boolean isAdmin = projectService.isAdmin(projectId, loginUser.getUserId());
        return ResponseEntity.ok(isAdmin);
    }

    private void requireProjectAdmin(Long projectId, LoginUser loginUser) {
        if (!projectService.isAdmin(projectId, loginUser.getUserId())) {
            throw new BizException("该操作需要项目管理员权限");
        }
    }

}
