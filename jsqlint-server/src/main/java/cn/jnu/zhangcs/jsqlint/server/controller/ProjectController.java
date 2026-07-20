package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.converter.CredentialConverter;
import cn.jnu.zhangcs.jsqlint.server.common.converter.ProjectConverter;
import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.Credential;
import cn.jnu.zhangcs.jsqlint.server.entity.Project;
import cn.jnu.zhangcs.jsqlint.server.entity.ProjectVersion;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.model.dto.ProjectDTO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.*;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 项目控制器
 *
 * @author zhangcs
 */
@Tag(name = "项目管理", description = "项目的创建、更新、删除、查询及代码同步、编译、分析等操作")
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/project")
@Validated
public class ProjectController {

    private final ProjectService projectService;

    private final WorkspaceService workspaceService;

    private final ProjectConverter converter;

    private final CredentialService credentialService;

    private final ProjectVersionService projectVersionService;

    private final UserService userService;

    private final AnalysisService analysisService;

    private final ProjectReportService projectReportService;

    private final ProjectVersionStepService projectVersionStepService;

    @Operation(summary = "创建项目", description = "创建一个新的项目，可指定 Git 仓库地址、分支、凭证等信息")
    @PostMapping
    public ResponseEntity<ProjectVO> create(
            @Valid @RequestBody ProjectDTO projectDTO,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        String gitUrl = projectDTO.getGitUrl();
        if (!StringUtils.hasText(gitUrl)) {
            return ResponseEntity.badRequest().build();
        }

        Long userId = loginUser.getUserId();
        Project project = converter.fromDTO(projectDTO, userId);
        Project created = projectService.create(project);

        if (projectDTO.getWorkspaceId() != null && projectDTO.getWorkspaceId() > 0) {
            workspaceService.addProject(projectDTO.getWorkspaceId(), created.getId());
        }

        return ResponseEntity.ok(converter.toVO(created, loginUser.getUsername()));
    }

    @Operation(summary = "更新项目", description = "更新指定ID的项目信息")
    @PutMapping("/{id}")
    public ResponseEntity<ProjectVO> updateProject(
            @Parameter(name = "id", description = "项目ID", example = "1") @PathVariable("id") Long id,
            @RequestBody ProjectDTO projectDTO) {
        Project existing = projectService.getById(id);
        converter.updateFromDTO(existing, projectDTO);
        Project updated = projectService.updateById(id, existing);
        return ResponseEntity.ok(converter.toVO(updated, getUserName(updated.getCreateUserId())));
    }

    @Operation(summary = "更新项目凭证", description = "更新项目关联的 Git 凭证")
    @PutMapping("/{id}/credential")
    public ResponseEntity<ProjectVO> updateCredential(
            @Parameter(name = "id", description = "项目ID", example = "1") @PathVariable("id") Long id,
            @Parameter(name = "credentialId", description = "凭证ID（-1或null表示取消关联）", example = "1") @RequestParam(value = "credentialId", required = false) Long credentialId) {
        Project existing = projectService.getById(id);
        if (existing == null) {
            throw new BizException("项目不存在");
        }
        if (credentialId == null || credentialId == -1) {
            existing.setCredentialId(null);
        } else {
            existing.setCredentialId(credentialId);
        }
        Project updated = projectService.updateById(id, existing);
        return ResponseEntity.ok(converter.toVO(updated, getUserName(updated.getCreateUserId())));
    }

    @Operation(summary = "删除项目", description = "根据项目ID删除指定的项目")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProject(
            @Parameter(name = "id", description = "项目ID", example = "1") @PathVariable("id") Long id) {
        projectService.delete(id);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "查询项目详情", description = "根据项目ID查询项目的详细信息")
    @GetMapping("/{id}")
    public ResponseEntity<ProjectVO> getProject(
            @Parameter(name = "id", description = "项目ID", example = "1") @PathVariable("id") Long id) {
        Project project = projectService.getById(id);
        if (project == null) {
            return ResponseEntity.ok().build();
        }

        // 获取创建者
        String userName = getUserName(project.getCreateUserId());
        return ResponseEntity.ok(converter.toVO(project, userName));
    }

    @Operation(summary = "查询项目列表", description = "查询所有项目列表，支持按关键字和启用状态筛选")
    @GetMapping
    public ResponseEntity<List<ProjectVO>> listProjects(
            @Parameter(name = "keyword", description = "搜索关键字（项目名称）", example = "jsqlint") @RequestParam(required = false) String keyword,
            @Parameter(name = "enabled", description = "是否启用", example = "true") @RequestParam(required = false) Boolean enabled) {
        List<Project> projects = projectService.getByKeyword(keyword, enabled);
        if (CollectionUtils.isEmpty(projects)) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        Set<Long> userIds = projects.stream().map(Project::getCreateUserId).collect(Collectors.toSet());
        List<User> users = userService.getByIds(userIds);
        return ResponseEntity.ok(converter.toProjectVOList(projects, users));
    }

    @Operation(summary = "查询项目版本列表", description = "查询指定项目的所有版本（分析记录）列表")
    @GetMapping("/{projectId}/versions")
    public ResponseEntity<List<ProjectVersionVO>> listProjectVersions(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable("projectId") Long projectId) {
        Project project = projectService.getById(projectId);
        if (project == null) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        List<ProjectVersion> versions = projectVersionService.getList(projectId);
        if (CollectionUtils.isEmpty(versions)) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        Set<Long> userIds = versions.stream()
                .map(ProjectVersion::getOperatorId)
                .collect(Collectors.toSet());
        List<User> users = userService.getByIds(userIds);
        Map<Long, String> operatorNames = users.stream()
                .collect(Collectors.toMap(User::getId, User::getUsername));

        String projectName = project.getName();
        List<ProjectVersionVO> list = versions.stream()
                .map(v -> converter.toVO(v, projectName, operatorNames.get(v.getOperatorId())))
                .toList();
        return ResponseEntity.ok(list);
    }

    @Operation(summary = "查询项目执行状态", description = "查询项目最新版本的同步、编译、SQL扫描、规则审核和报告生成步骤状态")
    @GetMapping("/{id}/status")
    public ResponseEntity<ProjectStatusVO> getProjectStatus(
            @Parameter(name = "id", description = "项目ID", example = "1") @PathVariable("id") Long id) {
        Project project = projectService.getById(id);
        if (project == null) {
            throw new BizException("项目不存在");
        }
        return ResponseEntity.ok(projectVersionStepService.getProjectStatus(id));
    }

    @Operation(summary = "查询项目报告", description = "根据项目ID查询SQL质量报告，传入版本ID时查询指定版本报告")
    @GetMapping("/{id}/reports")
    public ResponseEntity<ProjectReportVO> getReport(
            @Parameter(name = "id", description = "项目ID", example = "1") @PathVariable("id") Long id,
            @Parameter(name = "versionId", description = "版本ID", example = "1") @RequestParam(value = "versionId", required = false) Long versionId) {
        Project project = projectService.getById(id);
        if (project == null) {
            return ResponseEntity.ok().build();
        }
        if (versionId != null) {
            return ResponseEntity.ok(projectReportService.getByProjectIdAndVersionId(id, versionId));
        }
        return ResponseEntity.ok(projectReportService.getLatestByProjectId(id));
    }

    @Operation(summary = "下载项目报告", description = "根据项目ID下载SQL质量报告PDF文件，传入版本ID时下载指定版本报告")
    @GetMapping(value = "/{id}/reports/download", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> downloadReport(
            @Parameter(name = "id", description = "项目ID", example = "1") @PathVariable("id") Long id,
            @Parameter(name = "versionId", description = "版本ID", example = "1") @RequestParam(value = "versionId", required = false) Long versionId) {
        Project project = projectService.getById(id);
        if (project == null) {
            throw new BizException("项目不存在");
        }
        byte[] pdf = versionId == null
                ? projectReportService.downloadLatestPdf(id)
                : projectReportService.downloadPdf(id, versionId);
        String filename = String.format("%s-%d.pdf",project.getName(), id);
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + encodedFilename)
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.length)
                .body(pdf);
    }

    @Operation(summary = "查询可用凭证列表", description = "查询当前用户可用的 Git 凭证列表")
    @GetMapping("/credentials")
    public ResponseEntity<List<CredentialVO>> listCredentials(@Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        List<Credential> list = credentialService.listOptional(loginUser.getUserId());

        // 获取用户
        Set<Long> userIds = list.stream().map(Credential::getCreateUserId).collect(Collectors.toSet());
        List<User> users = userService.getByIds(userIds);
        Map<Long, String> userId2Name = users.stream().collect(Collectors.toMap(User::getId, User::getUsername, (k1, k2) -> k1));

        return ResponseEntity.ok(
                list.stream()
                        .map(c -> CredentialConverter.toVO(c, userId2Name.getOrDefault(c.getCreateUserId(), "未知")))
                        .toList()
        );
    }

    private String getUserName(Long userId) {
        if (userId == null) {
            return null;
        }
        User user = userService.getById(userId);
        if (user == null) {
            return null;
        }
        return user.getUsername();
    }

    @Operation(summary = "同步代码", description = "从 Git 仓库同步项目代码")
    @PostMapping("/{id}/sync")
    public ResponseEntity<Void> sync(
            @Parameter(name = "id", description = "项目ID", example = "1") @PathVariable(value = "id") Long id,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        analysisService.sync(id, loginUser.getUserId());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "编译项目", description = "使用 Maven 编译项目代码")
    @PostMapping("/{id}/compile")
    public ResponseEntity<Void> compile(
            @Parameter(name = "id", description = "项目ID", example = "1") @PathVariable(value = "id") Long id,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        analysisService.compile(id, loginUser.getUserId());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "分析项目", description = "对项目代码进行 SQL 注入漏洞分析")
    @PostMapping("/{id}/analyze")
    public ResponseEntity<Void> analyze(
            @Parameter(name = "id", description = "项目ID", example = "1") @PathVariable(value = "id") Long id,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        analysisService.analyze(id, loginUser.getUserId());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "审核项目", description = "对分析结果应用 Drools 审核规则")
    @PostMapping("/{id}/audit")
    public ResponseEntity<Void> audit(
            @Parameter(name = "id", description = "项目ID", example = "1") @PathVariable(value = "id") Long id,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        analysisService.audit(id, loginUser.getUserId());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "生成报告", description = "生成项目的审核报告")
    @PostMapping("/{id}/reports")
    public ResponseEntity<Long> report(
            @Parameter(name = "id", description = "项目ID", example = "1") @PathVariable(value = "id") Long id,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        Long taskId = analysisService.report(id, loginUser.getUserId());
        return ResponseEntity.ok(taskId);
    }

    @Operation(summary = "执行项目", description = "提交项目完整执行任务，依次完成同步代码、编译检查、SQL扫描、规则审核和生成报告")
    @PostMapping("/{id}/execute")
    public ResponseEntity<Boolean> execute(
            @Parameter(name = "id", description = "项目ID", example = "1") @PathVariable(value = "id") Long id,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(analysisService.execute(id, loginUser.getUserId()));
    }

}
