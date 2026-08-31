package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.annotation.ProjectLog;
import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectMemberRole;
import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectVersionStatus;
import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectVersionStepType;
import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.Project;
import cn.jnu.zhangcs.jsqlint.server.entity.ProjectMember;
import cn.jnu.zhangcs.jsqlint.server.entity.ProjectVersion;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.mapper.ProjectMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.ProjectMemberMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.ProjectVersionMapper;
import cn.jnu.zhangcs.jsqlint.server.service.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectServiceImpl implements ProjectService {

    private final ProjectMapper projectMapper;

    private final ProjectVersionService versionService;

    private final ProjectMemberMapper projectMemberMapper;

    private final UserService userService;

    private final UserGroupService userGroupService;

    private final ProjectVersionMapper versionMapper;

    private final ProjectVersionStepService projectVersionStepService;

    @Override
    @Transactional
    @ProjectLog(
            projectId = "#result.id",
            operationType = "PROJECT_CREATE",
            message = "创建项目: #{#result.name}",
            relatedId = "#result.id",
            includeResult = true)
    public Project create(Project project) {
        projectMapper.insert(project);

        // 创建者默认为项目管理员成员
        if (project.getCreateUserId() != null) {
            addMember(project.getId(), project.getCreateUserId(), true);
        }

        return project;
    }

    @Override
    @Transactional
    @ProjectLog(
            projectId = "#id",
            operationType = "PROJECT_UPDATE",
            message = "更新项目: #{#result.name}",
            relatedId = "#id",
            includeResult = true)
    public Project updateById(Long id, Project project) {
        Project exist = projectMapper.selectById(id);
        if (exist == null) {
            throw new BizException("项目不存在");
        }

        LambdaUpdateWrapper<Project> updateWrapper = new LambdaUpdateWrapper<>();
        if (project.getRef() != null) {
            updateWrapper.set(Project::getRef, project.getRef());
        }
        if (project.getStatus() != null) {
            updateWrapper.set(Project::getStatus, project.getStatus());
        }
        updateWrapper.set(Project::getCredentialId, project.getCredentialId());
        updateWrapper.eq(Project::getId, id);
        projectMapper.update(null, updateWrapper);

        return projectMapper.selectById(id);
    }

    @Override
    @Transactional
    @ProjectLog(
            projectId = "#id",
            operationType = "PROJECT_DELETE",
            message = "删除项目: #{#id}",
            relatedId = "#id")
    public void delete(Long id) {
        Project project = projectMapper.selectById(id);
        if (project == null) {
            throw new BizException("项目不存在");
        }
        // 删除项目成员关系
        projectMemberMapper.delete(
                new LambdaQueryWrapper<>(ProjectMember.class)
                        .eq(ProjectMember::getProjectId, id));
        // 删除项目版本步骤与版本
        projectVersionStepService.deleteByProjectId(id);
        versionService.delete(id);

        // 删除项目
        projectMapper.deleteById(id);
    }

    @Override
    public @Nullable Project getById(Long id) {
        if (id == null) {
            return null;
        }
        return projectMapper.selectById(id);
    }

    @Override
    public List<Project> getByKeyword(String keyword, Boolean enabled) {
        LambdaQueryWrapper<Project> queryWrapper = Wrappers.lambdaQuery();
        if (keyword != null && !keyword.isBlank()) {
            queryWrapper.like(Project::getName, keyword)
                    .or()
                    .like(Project::getGitUrl, keyword);
        }
        if (enabled != null) {
            queryWrapper.eq(Project::getStatus, enabled ? 1 : 0);
        }
        queryWrapper.orderByDesc(Project::getCreateTime);
        return projectMapper.selectList(queryWrapper);
    }

    @Override
    public @NonNull List<Project> getByIds(Collection<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return List.of();
        }
        return projectMapper.selectByIds(ids);
    }

    @Override
    public List<Project> getByUser(Long userId) {
        List<ProjectMember> members = projectMemberMapper.selectList(
                Wrappers.<ProjectMember>lambdaQuery()
                        .select(ProjectMember::getProjectId)
                        .eq(ProjectMember::getUserId, userId)
        );
        Set<Long> projectIds = members.stream()
                .map(ProjectMember::getProjectId)
                .collect(Collectors.toSet());
        return getByIds(projectIds);
    }

    @Override
    public @Nullable ProjectVersion getLastVersion(Long projectId) {
        if (projectId == null) {
            return null;
        }
        return versionService.getLast(projectId);
    }

    @Override
    @Transactional
    @ProjectLog(
            projectId = "#projectId",
            operationType = "MEMBER_ADD",
            message = "添加项目成员: #{#userId}",
            relatedId = "#userId")
    public void addMember(Long projectId, Long userId, boolean isAdmin) {
        Project project = getById(projectId);
        if (project == null) {
            throw new BizException("项目不存在");
        }

        User user = userService.getById(userId);
        if (user == null) {
            throw new BizException("用户不存在");
        }

        // 检查是否已是成员
        ProjectMember existMember = getMember(projectId, userId);
        if (existMember != null) {
            throw new BizException("用户已是项目成员");
        }

        ProjectMember member = new ProjectMember();
        member.setProjectId(projectId);
        member.setUserId(userId);
        member.setRole(isAdmin ? ProjectMemberRole.ADMIN.getCode() : ProjectMemberRole.NORMAL.getCode());
        projectMemberMapper.insert(member);
    }

    @Override
    @Transactional
    @ProjectLog(
            projectId = "#projectId",
            operationType = "MEMBER_ADD",
            message = "添加项目成员: #{T(java.lang.String).join(',', #userIds.![toString()])}")
    public void addMembers(Long projectId, List<Long> userIds, boolean isAdmin) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }

        Project project = getById(projectId);
        if (project == null) {
            throw new BizException("项目不存在");
        }

        int role = isAdmin ? ProjectMemberRole.ADMIN.getCode()
                : ProjectMemberRole.NORMAL.getCode();

        for (Long userId : userIds) {
            User user = userService.getById(userId);
            if (user == null) {
                continue;
            }

            ProjectMember existMember = getMember(projectId, userId);
            if (existMember != null) {
                continue;
            }

            ProjectMember member = new ProjectMember();
            member.setProjectId(projectId);
            member.setUserId(userId);
            member.setRole(role);
            projectMemberMapper.insert(member);
        }
    }

    @Override
    @Transactional
    @ProjectLog(
            projectId = "#projectId",
            operationType = "MEMBER_ADD_BY_GROUP",
            message = "添加项目成员: #{#groupId}",
            relatedId = "#groupId")
    public void addMembersByGroup(Long projectId, Long groupId, boolean isAdmin) {
        Project project = getById(projectId);
        if (project == null) {
            throw new BizException("项目不存在");
        }

        List<User> groupUsers = userGroupService.getMembers(groupId);
        if (groupUsers.isEmpty()) {
            return;
        }

        List<Long> userIds = groupUsers.stream()
                .map(User::getId)
                .toList();
        addMembers(projectId, userIds, isAdmin);
    }

    @Override
    @ProjectLog(
            projectId = "#projectId",
            operationType = "MEMBER_REMOVE",
            message = "移除项目成员: #{#userId}",
            relatedId = "#userId")
    public void removeMember(Long projectId, Long userId) {
        ProjectMember member = getMember(projectId, userId);
        if (member == null) {
            throw new BizException("用户不是项目成员");
        }

        projectMemberMapper.deleteById(member.getId());
    }

    @Override
    @ProjectLog(
            projectId = "#projectId",
            operationType = "MEMBER_ROLE_UPDATE",
            message = "更新项目成员角色: #{#userId}",
            relatedId = "#userId")
    public void updateMemberRole(Long projectId, Long userId, boolean isAdmin) {
        ProjectMember member = getMember(projectId, userId);
        if (member == null) {
            throw new BizException("用户不是项目成员");
        }

        member.setRole(isAdmin ? ProjectMemberRole.ADMIN.getCode()
                : ProjectMemberRole.NORMAL.getCode());
        projectMemberMapper.updateById(member);
    }

    @Override
    public List<ProjectMember> getMembers(Long projectId) {
        List<ProjectMember> members = projectMemberMapper.selectList(
                Wrappers.<ProjectMember>lambdaQuery()
                        .eq(ProjectMember::getProjectId, projectId)
                        .orderByDesc(ProjectMember::getJoinTime)
        );
        if (members.isEmpty()) {
            return List.of();
        }

        // 填充用户信息；用户可能已被删除（成员关系未级联清理），孤儿成员跳过填充
        List<Long> userIds = members.stream()
                .map(ProjectMember::getUserId)
                .toList();
        List<User> users = userService.getByIds(userIds);
        Map<Long, User> userMap = users.stream().collect(Collectors.toMap(
                User::getId,
                u -> u,
                (u1, u2) -> u2));
        for (ProjectMember member : members) {
            Long userId = member.getUserId();
            User user = userMap.get(userId);
            if (user == null) {
                continue;
            }
            member.setAccount(user.getAccount());
            member.setUsername(user.getUsername());
        }
        return members;
    }

    /**
     * 获取成员在项目中的信息
     *
     * @param projectId 项目
     * @param userId    用户
     * @return 返回成员信息
     */
    private @Nullable ProjectMember getMember(Long projectId, Long userId) {
        if (projectId == null || userId == null) {
            return null;
        }
        return projectMemberMapper.selectOne(
                Wrappers.<ProjectMember>lambdaQuery()
                        .eq(ProjectMember::getProjectId, projectId)
                        .eq(ProjectMember::getUserId, userId)
        );
    }

    @Override
    public boolean isAdmin(Long projectId, Long userId) {
        ProjectMember member = getMember(projectId, userId);
        if (member == null) {
            return false;
        }
        return member.isAdmin();
    }

    @Override
    public boolean isSyncing(Long id) {
        ProjectVersion lastVersion = getLastVersion(id);
        if (lastVersion == null) {
            return false;
        }
        return ProjectVersionStatus.is(lastVersion.getOverallStatus(), ProjectVersionStatus.RUNNING)
                && ProjectVersionStepType.from(lastVersion.getCurrentStep()) == ProjectVersionStepType.SYNC_CODE;
    }

    @Override
    public void addVersion(@NonNull Long id, @NonNull ProjectVersion version) {
        version.setProjectId(id);
        LocalDateTime operationTime = version.getOperationTime();
        if (operationTime == null) {
            version.setOperationTime(LocalDateTime.now());
        }
        versionMapper.insert(version);
    }

    @Override
    public boolean isAlreadySync(Long id) {
        Project project = getById(id);
        if (project == null) {
            return false;
        }
        // 检查项目版本
        ProjectVersion version = getLastVersion(id);
        if (version == null) {
            return false;
        }
        if (!projectVersionStepService.isStepSuccess(version.getId(), ProjectVersionStepType.SYNC_CODE)) {
            return false;
        }

        // 检查磁盘文件
        String name = project.getName();
        // TODO 检查项目文件是否存在
        return true;
    }

    @Override
    public boolean isAlreadyCompiled(Long id) {
        ProjectVersion version = getLastVersion(id);
        return version != null && projectVersionStepService.isStepSuccess(version.getId(), ProjectVersionStepType.COMPILE_CHECK);
    }

    @Override
    public boolean isAlreadyAnalysis(Long id) {
        ProjectVersion version = getLastVersion(id);
        return version != null && projectVersionStepService.isStepSuccess(version.getId(), ProjectVersionStepType.SQL_SCAN);
    }

    @Override
    public boolean isAlreadyAudit(Long id) {
        ProjectVersion version = getLastVersion(id);
        return version != null && projectVersionStepService.isStepSuccess(version.getId(), ProjectVersionStepType.RULE_AUDIT);
    }

    @Override
    public void updateVersion(@NonNull Long versionId, @NonNull ProjectVersion version) {
        version.setId(versionId);
        versionMapper.updateById(version);
    }

}
