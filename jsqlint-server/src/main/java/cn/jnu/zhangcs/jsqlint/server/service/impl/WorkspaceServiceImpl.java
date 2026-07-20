package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.Project;
import cn.jnu.zhangcs.jsqlint.server.entity.Workspace;
import cn.jnu.zhangcs.jsqlint.server.entity.WorkspaceProject;
import cn.jnu.zhangcs.jsqlint.server.mapper.WorkspaceMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.WorkspaceProjectMapper;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectService;
import cn.jnu.zhangcs.jsqlint.server.service.WorkspaceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceServiceImpl implements WorkspaceService {

    private final WorkspaceMapper workspaceMapper;

    private final WorkspaceProjectMapper workspaceProjectMapper;

    private final ProjectService projectService;

    @Override
    @Transactional
    public void delete(Long id) {
        Workspace workspace = workspaceMapper.selectById(id);
        if (workspace == null) {
            throw new BizException("工作空间不存在");
        }

        // 检查是否有项目
        int projectCount = getProjectCount(id);
        if (projectCount > 0) {
            throw new BizException("工作空间下有项目，不允许删除");
        }

        // 删除工作空间
        workspaceMapper.deleteById(id);
    }

    private int getProjectCount(Long id) {
        if (id == null) {
            return 0;
        }
        LambdaQueryWrapper<WorkspaceProject> query = Wrappers.<WorkspaceProject>lambdaQuery()
                .eq(WorkspaceProject::getWorkspaceId, id);
        Long count = workspaceProjectMapper.selectCount(query);
        return Math.toIntExact(count);
    }

    @Override
    public Workspace getById(Long id) {
        return workspaceMapper.selectById(id);
    }

    @Override
    public List<Workspace> list(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return workspaceMapper.selectList(
                new LambdaQueryWrapper<>(Workspace.class)
                        .eq(Workspace::getCreatorId, userId)
                        .orderByDesc(Workspace::getCreateTime)
        );
    }

    @Override
    @Transactional
    public void addProject(Long workspaceId, Long projectId) {
        Workspace workspace = workspaceMapper.selectById(workspaceId);
        if (workspace == null) {
            throw new BizException("工作空间不存在");
        }

        Project project = projectService.getById(projectId);
        if (project == null) {
            throw new BizException("项目不存在");
        }

        // 检查项目是否已在其他工作空间
        WorkspaceProject existingRelation = workspaceProjectMapper.selectOne(
                new LambdaQueryWrapper<>(WorkspaceProject.class)
                        .eq(WorkspaceProject::getProjectId, projectId));

        if (existingRelation != null) {
            throw new BizException("项目已在其他工作空间中");
        }

        // 添加关系
        WorkspaceProject relation = new WorkspaceProject();
        relation.setWorkspaceId(workspaceId);
        relation.setProjectId(projectId);
        workspaceProjectMapper.insert(relation);
    }

    @Override
    @Transactional
    public void removeProject(Long workspaceId, Long projectId) {
        WorkspaceProject relation = workspaceProjectMapper.selectOne(
                new LambdaQueryWrapper<>(WorkspaceProject.class)
                        .eq(WorkspaceProject::getWorkspaceId, workspaceId)
                        .eq(WorkspaceProject::getProjectId, projectId));

        if (relation == null) {
            throw new BizException("项目不在该工作空间中");
        }

        workspaceProjectMapper.deleteById(relation.getId());
    }

    @Override
    public List<Project> getProjects(Long workspaceId) {
        LambdaQueryWrapper<WorkspaceProject> params = new LambdaQueryWrapper<WorkspaceProject>()
                .eq(WorkspaceProject::getWorkspaceId, workspaceId)
                .select(WorkspaceProject::getProjectId);
        List<WorkspaceProject> list = workspaceProjectMapper.selectList(params);
        if (list.isEmpty()) {
            return List.of();
        }
        Set<Long> projectIds = list.stream()
                .map(WorkspaceProject::getProjectId)
                .collect(Collectors.toSet());
        return projectService.getByIds(projectIds);
    }

    @Override
    public Workspace getWorkspaceByProjectId(Long projectId) {
        WorkspaceProject relation = workspaceProjectMapper.selectOne(
                new LambdaQueryWrapper<>(WorkspaceProject.class)
                        .eq(WorkspaceProject::getProjectId, projectId));

        if (relation == null) {
            return null;
        }
        return workspaceMapper.selectById(relation.getWorkspaceId());
    }

    @Override
    public Workspace create(Long userId, String name) {
        LambdaQueryWrapper<Workspace> query = new LambdaQueryWrapper<>(Workspace.class)
                .eq(Workspace::getCreatorId, userId)
                .eq(Workspace::getName, name);
        List<Workspace> list = workspaceMapper.selectList(query);
        if (!CollectionUtils.isEmpty(list)) {
            throw new BizException("工作空间已存在");
        }

        Workspace workspace = new Workspace();
        workspace.setName(name);
        workspace.setCreatorId(userId);
        workspaceMapper.insert(workspace);
        return workspace;
    }
}