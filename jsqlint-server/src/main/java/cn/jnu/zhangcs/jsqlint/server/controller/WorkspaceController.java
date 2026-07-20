package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.Project;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.entity.Workspace;
import cn.jnu.zhangcs.jsqlint.server.model.dto.WorkspaceCreateDTO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.ProjectVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.WorkspaceVO;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectService;
import cn.jnu.zhangcs.jsqlint.server.service.UserService;
import cn.jnu.zhangcs.jsqlint.server.service.WorkspaceService;
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
 * 工作空间控制器
 *
 * @author zhangcs
 */
@Tag(name = "工作区管理", description = "工作区的创建、删除、查询及项目关联管理")
@RestController
@RequestMapping("/api/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    private final UserService userService;

    private final ProjectService projectService;

    @Operation(summary = "创建工作区", description = "创建一个新的工作区，用于组织和管理项目")
    @PostMapping
    public ResponseEntity<WorkspaceVO> create(@Valid @RequestBody WorkspaceCreateDTO dto,
                                              @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        Long userId = loginUser != null ? loginUser.getUserId() : null;
        if (userId == null) {
            throw new BizException("请先登录");
        }
        Workspace created = workspaceService.create(userId, dto.getName());
        return ResponseEntity.ok(toVO(created, userId));
    }

    @Operation(summary = "删除工作区", description = "根据工作区ID删除指定的工作区")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @Parameter(name = "id", description = "工作区ID", example = "1") @PathVariable("id") Long id) {
        workspaceService.delete(id);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "查询工作区详情", description = "根据工作区ID查询工作区的详细信息")
    @GetMapping("/{id}")
    public ResponseEntity<WorkspaceVO> getById(
            @Parameter(name = "id", description = "工作区ID", example = "1") @PathVariable("id") Long id,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        Workspace workspace = workspaceService.getById(id);
        if (workspace == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toVO(workspace, loginUser.getUserId()));
    }

    @Operation(summary = "查询工作区列表", description = "查询当前用户的所有工作区列表")
    @GetMapping
    public ResponseEntity<List<WorkspaceVO>> list(@Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        List<Workspace> workspaces = workspaceService.list(loginUser.getUserId());
        return ResponseEntity.ok(toVOList(workspaces));
    }

    @Operation(summary = "添加项目到工作区", description = "将指定的项目添加到工作区中")
    @PostMapping("/{workspaceId}/projects/{projectId}")
    public ResponseEntity<Void> addProject(
            @Parameter(name = "workspaceId", description = "工作区ID", example = "1") @PathVariable("workspaceId") Long workspaceId,
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable("projectId") Long projectId) {
        workspaceService.addProject(workspaceId, projectId);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "从工作区移除项目", description = "将指定的项目从工作区中移除")
    @DeleteMapping("/{workspaceId}/projects/{projectId}")
    public ResponseEntity<Void> removeProject(
            @Parameter(name = "workspaceId", description = "工作区ID", example = "1") @PathVariable("workspaceId") Long workspaceId,
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable("projectId") Long projectId) {
        workspaceService.removeProject(workspaceId, projectId);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "查询项目列表", description = "查询指定工作区的项目列表，若不指定工作区则返回当前用户的所有项目")
    @GetMapping("/projects")
    public ResponseEntity<List<ProjectVO>> getProjects(
            @Parameter(name = "workspaceId", description = "工作区ID（可选）", example = "1") @RequestParam(value = "workspaceId", required = false) Long workspaceId,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) throws InterruptedException {
        List<Project> projects;
        if (workspaceId != null) {
            projects = workspaceService.getProjects(workspaceId);
        } else {
            projects = projectService.getByUser(loginUser.getUserId());
        }
        return ResponseEntity.ok(projects.stream()
                .map(p -> {
                    Workspace workspace = workspaceService.getWorkspaceByProjectId(p.getId());
                    return ProjectVO.builder()
                            .id(p.getId())
                            .name(p.getName())
                            .description(p.getDescription())
                            .gitUrl(p.getGitUrl())
                            .ref(p.getRef())
                            .enabled(p.getStatus())
                            .createUserId(p.getCreateUserId())
                            .credentialId(p.getCredentialId())
                            .workspaceId(workspace != null ? workspace.getId() : null)
                            .workspaceName(workspace != null ? workspace.getName() : null)
                            .createTime(p.getCreateTime())
                            .updateTime(p.getUpdateTime())
                            .build();
                })
                .toList());
    }

    private WorkspaceVO toVO(Workspace workspace, Long userId) {
        String creatorName = null;
        if (workspace.getCreatorId() != null) {
            User creator = userService.getById(workspace.getCreatorId());
            if (creator != null) {
                creatorName = creator.getUsername();
            }
        }

        Integer projectCount = workspace.getProjectCount();
        if (projectCount == null) {
            projectCount = workspaceService.list(userId).stream()
                    .filter(w -> Objects.equals(w.getId(), workspace.getId()))
                    .findFirst()
                    .map(Workspace::getProjectCount)
                    .orElse(0);
        }

        return WorkspaceVO.builder()
                .id(workspace.getId())
                .name(workspace.getName())
                .creatorId(workspace.getCreatorId())
                .creatorName(creatorName)
                .projectCount(projectCount)
                .createTime(workspace.getCreateTime())
                .updateTime(workspace.getUpdateTime())
                .build();
    }

    private List<WorkspaceVO> toVOList(List<Workspace> workspaces) {
        if (workspaces.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Long> creatorIds = workspaces.stream()
                .map(Workspace::getCreatorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, String> creatorNames = creatorIds.isEmpty() ? Collections.emptyMap()
                : userService.getByIds(creatorIds).stream()
                        .collect(Collectors.toMap(User::getId, User::getUsername));

        return workspaces.stream()
                .map(w -> WorkspaceVO.builder()
                        .id(w.getId())
                        .name(w.getName())
                        .creatorId(w.getCreatorId())
                        .creatorName(creatorNames.get(w.getCreatorId()))
                        .projectCount(w.getProjectCount())
                        .createTime(w.getCreateTime())
                        .updateTime(w.getUpdateTime())
                        .build())
                .toList();
    }
}