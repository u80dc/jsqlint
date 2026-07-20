package cn.jnu.zhangcs.jsqlint.server.common.converter;

import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectVersionStatus;
import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectVersionStepType;
import cn.jnu.zhangcs.jsqlint.server.entity.Credential;
import cn.jnu.zhangcs.jsqlint.server.entity.Project;
import cn.jnu.zhangcs.jsqlint.server.entity.ProjectVersion;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.mapper.CredentialMapper;
import cn.jnu.zhangcs.jsqlint.server.model.dto.ProjectDTO;
import cn.jnu.zhangcs.jsqlint.server.model.dto.ProjectVersionDTO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.ProjectVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.ProjectVersionVO;
import cn.jnu.zhangcs.jsqlint.server.service.UserService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ProjectConverter {

    private final CredentialMapper credentialMapper;
    private final UserService userService;

    public ProjectConverter(CredentialMapper credentialMapper, UserService userService) {
        this.credentialMapper = credentialMapper;
        this.userService = userService;
    }

    public Project fromDTO(ProjectDTO dto, Long createUserId) {
        Project project = new Project();
        project.setName(dto.getName());
        project.setDescription(dto.getDescription());
        project.setGitUrl(dto.getGitUrl());
        project.setRef(dto.getRef());
        project.setStatus(dto.getEnabled() != null ? dto.getEnabled() : 1);
        project.setCreateUserId(createUserId);

        Long credentialId = dto.getCredentialId();
        project.setCredentialId(credentialId != null && credentialId != -1 ? credentialId : null);

        return project;
    }

    public void updateFromDTO(Project target, ProjectDTO dto) {
        if (dto.getDescription() != null) {
            target.setDescription(dto.getDescription());
        }
        if (dto.getRef() != null) {
            target.setRef(dto.getRef());
        }
        if (dto.getEnabled() != null) {
            target.setStatus(dto.getEnabled());
        }
        if (dto.getCredentialId() != null) {
            Long credentialId = dto.getCredentialId() == -1 ? null : dto.getCredentialId();
            target.setCredentialId(credentialId);
        }
    }

    public ProjectVO toVO(Project project, String createUserName) {
        ProjectVO vo = new ProjectVO();
        vo.setId(project.getId());
        vo.setName(project.getName());
        vo.setDescription(project.getDescription());
        vo.setGitUrl(project.getGitUrl());
        vo.setRef(project.getRef());
        vo.setEnabled(project.getStatus());
        vo.setCredentialId(project.getCredentialId());
        vo.setCreateUserId(project.getCreateUserId());
        vo.setCreateTime(project.getCreateTime());
        vo.setUpdateTime(project.getUpdateTime());
        vo.setCreateUserName(createUserName);

        // 填充凭证信息
        if (project.getCredentialId() != null) {
            Credential credential = credentialMapper.selectById(project.getCredentialId());
            if (credential != null) {
                vo.setCredentialName(credential.getName());
                vo.setCredentialVisibility(credential.getVisibility());

                // 获取凭证创建者信息
                User creator = null;
                if (credential.getCreateUserId() != null) {
                    creator = userService.getById(credential.getCreateUserId());
                }
                if (creator != null) {
                    vo.setCredentialCreatorId(creator.getId());
                    vo.setCredentialCreatorName(creator.getUsername());
                }
            }
        }

        return vo;
    }

    public List<ProjectVO> toProjectVOList(List<Project> projects, List<User> users) {
        if (projects.isEmpty()) {
            return List.of();
        }

        Set<Long> userIds = projects.stream()
                .map(Project::getCreateUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, String> userNames = userIds.isEmpty() ? Map.of()
                : users.stream().collect(Collectors.toMap(User::getId, User::getUsername));

        return projects.stream()
                .map(p -> toVO(p, userNames.get(p.getCreateUserId())))
                .toList();
    }

    public ProjectVersion fromDTO(ProjectVersionDTO dto, Long operatorId) {
        ProjectVersion version = new ProjectVersion();
        version.setRef(dto.getRef());
        version.setCommitId(dto.getCommitId());
        version.setCommitMessage(dto.getCommitMessage());
        version.setAuthor(dto.getAuthor());
        version.setOperatorId(operatorId);
        return version;
    }

    public ProjectVersionVO toVO(ProjectVersion version, String projectName, String operatorName) {
        ProjectVersionVO vo = new ProjectVersionVO();
        vo.setId(version.getId());
        vo.setProjectId(version.getProjectId());
        vo.setRef(version.getRef());
        vo.setCommitId(version.getCommitId());
        vo.setCommitMessage(version.getCommitMessage());
        vo.setAuthor(version.getAuthor());
        vo.setOperatorId(version.getOperatorId());
        vo.setOperationTime(version.getOperationTime());
        vo.setProjectName(projectName);
        vo.setOperatorName(operatorName);
        vo.setOverallStatus(version.getOverallStatus());
        vo.setOverallStatusName(ProjectVersionStatus.from(version.getOverallStatus()).getDesc());
        vo.setCurrentStep(version.getCurrentStep());
        ProjectVersionStepType currentStep = ProjectVersionStepType.from(version.getCurrentStep());
        vo.setCurrentStepName(currentStep == ProjectVersionStepType.UN_SUPPORT ? null : currentStep.getDesc());
        vo.setMessage(version.getMessage());
        return vo;
    }


}
