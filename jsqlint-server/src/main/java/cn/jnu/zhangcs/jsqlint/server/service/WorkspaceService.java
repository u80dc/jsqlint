package cn.jnu.zhangcs.jsqlint.server.service;

import cn.jnu.zhangcs.jsqlint.server.entity.Project;
import cn.jnu.zhangcs.jsqlint.server.entity.Workspace;

import java.util.List;

/**
 * 工作空间服务接口
 *
 * @author zhangcs
 */
public interface WorkspaceService {

    /**
     * 删除工作空间（如果工作空间下有项目则不允许删除）
     *
     * @param id 工作空间ID
     */
    void delete(Long id);

    /**
     * 获取工作空间
     *
     * @param id 工作空间ID
     * @return 工作空间
     */
    Workspace getById(Long id);

    /**
     * 获取工作空间列表
     *
     * @return 工作空间列表
     */
    List<Workspace> list(Long userId);

    /**
     * 添加项目到工作空间
     *
     * @param workspaceId 工作空间ID
     * @param projectId  项目ID
     */
    void addProject(Long workspaceId, Long projectId);

    /**
     * 从工作空间移除项目
     *
     * @param workspaceId 工作空间ID
     * @param projectId  项目ID
     */
    void removeProject(Long workspaceId, Long projectId);

    /**
     * 获取工作空间下的项目列表
     *
     * @param workspaceId 工作空间ID
     * @return 项目列表
     */
    List<Project> getProjects(Long workspaceId);

    /**
     * 获取项目所属的工作空间
     *
     * @param projectId 项目ID
     * @return 工作空间
     */
    Workspace getWorkspaceByProjectId(Long projectId);

    /**
     * 创建工作空间
     *
     * @param userId 创建者ID
     * @param name   工作空间名称
     * @return 创建的工作空间
     */
    Workspace create(Long userId, String name);
}