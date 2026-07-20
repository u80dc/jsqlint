package cn.jnu.zhangcs.jsqlint.server.service;

import cn.jnu.zhangcs.jsqlint.server.entity.Project;
import cn.jnu.zhangcs.jsqlint.server.entity.ProjectMember;
import cn.jnu.zhangcs.jsqlint.server.entity.ProjectVersion;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.List;

/**
 * 项目业务接口
 *
 * @author zhangcs
 */
public interface ProjectService {

    /**
     * 创建项目
     *
     * @param project 项目信息
     * @return 返回项目信息
     */
    Project create(Project project);

    /**
     * 根据ID更新项目
     *
     * @param id      项目ID
     * @param project 新的项目信息
     * @return 返回更新后的项目信息
     */
    Project updateById(Long id, Project project);

    /**
     * 删除项目
     * @param id 项目ID
     */
    void delete(Long id);

    /**
     * 根据ID获取项目
     * @param id 项目ID
     * @return 返回项目信息
     */
    @Nullable
    Project getById(Long id);

    List<Project> getByKeyword(String keyword, Boolean enabled);

    /**
     * 获取指定项目信息
     *
     * @param ids 项目
     * @return 返回项目信息列表
     */
    @NonNull
    List<Project> getByIds(Collection<Long> ids);

    /**
     * 获取用户所在的项目
     *
     * @param userId 用户
     * @return 返回项目列表
     */
    List<Project> getByUser(Long userId);

    /**
     * 获取最新一个版本
     *
     * @param projectId 项目
     * @return 返回版本对象，不存在时返回null
     */
    @Nullable
    ProjectVersion getLastVersion(Long projectId);

    /**
     * 项目添加成员
     *
     * @param projectId 项目
     * @param userId    用户
     * @param isAdmin   是否管理员
     */
    void addMember(Long projectId, Long userId, boolean isAdmin);

    /**
     * 项目添加成员
     *
     * @param projectId 项目
     * @param userIds   用户
     * @param isAdmin   是否管理员
     */
    void addMembers(Long projectId, List<Long> userIds, boolean isAdmin);

    /**
     * 通过用户组批量添加成员
     *
     * @param projectId 项目
     * @param groupId   用户组
     * @param isAdmin   是否管理员
     */
    void addMembersByGroup(Long projectId, Long groupId, boolean isAdmin);

    /**
     * 移除成员
     *
     * @param projectId 项目
     * @param userId    用户
     */
    void removeMember(Long projectId, Long userId);

    /**
     * 更改成员权限
     *
     * @param projectId 项目
     * @param userId    用户
     * @param isAdmin   是否管理员
     */
    void updateMemberRole(Long projectId, Long userId, boolean isAdmin);

    /**
     * 获取所有成员
     *
     * @param projectId 项目
     * @return 返回项目成员列表
     */
    List<ProjectMember> getMembers(Long projectId);

    /**
     * 检查成员是否项目管理员
     *
     * @param projectId 项目
     * @param userId    用户
     * @return 用户是该项目管理员时返回true
     */
    boolean isAdmin(Long projectId, Long userId);

    /**
     * 项目是否处于同步中
     *
     * @param id 项目
     * @return 同步中时返回true，否则返回false
     */
    boolean isSyncing(Long id);

    /**
     * 新增版本
     *
     * @param id      项目
     * @param version 版本
     */
    void addVersion(@NonNull Long id, @NonNull ProjectVersion version);

    /**
     * 项目是否已同步完成
     *
     * @param id 项目
     * @return 项目已同步完成时返回true
     */
    boolean isAlreadySync(Long id);

    /**
     * 项目是否已编译完成
     *
     * @param id 项目
     * @return 项目已编译完成时返回true
     */
    boolean isAlreadyCompiled(Long id);

    /**
     * 项目是否已分析完成
     *
     * @param id 项目
     * @return 项目已分析完成时返回true
     */
    boolean isAlreadyAnalysis(Long id);

    /**
     * 项目是否已审核完成
     *
     * @param id 项目
     * @return 项目已审核完成时返回true
     */
    boolean isAlreadyAudit(Long id);

    /**
     * 更新版本
     *
     * @param versionId 项目版本ID
     * @param version   版本信息
     */
    void updateVersion(@NonNull Long versionId, @NonNull ProjectVersion version);

}