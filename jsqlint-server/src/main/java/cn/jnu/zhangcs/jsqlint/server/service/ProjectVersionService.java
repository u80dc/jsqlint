package cn.jnu.zhangcs.jsqlint.server.service;

import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectVersionStatus;
import cn.jnu.zhangcs.jsqlint.server.entity.ProjectVersion;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * 项目版本接口
 *
 * @author zhangcs
 */
public interface ProjectVersionService {

    /**
     * 删除项目所有的版本
     *
     * @param projectId 项目ID
     */
    void delete(Long projectId);

    /**
     * 根据项目获取
     *
     * @param projectId 项目ID
     * @return 返回版本列表，根据创建时间倒序
     */
    List<ProjectVersion> getList(Long projectId);

    /**
     * 根据项目获取
     *
     * @param projectId 项目ID
     * @param status    整体状态，为 null 时表示不限制
     * @return 返回版本列表，根据创建时间倒序
     */
    List<ProjectVersion> getList(Long projectId, @Nullable ProjectVersionStatus status);

    /**
     * 新增一个版本
     *
     * @param version 版本信息
     */
    void add(@NonNull ProjectVersion version);

    /**
     * 获取指定项目的最新版本
     *
     * @param projectId 项目ID
     * @return 返回版本信息
     */
    @Nullable
    ProjectVersion getLast(Long projectId);

    /**
     * 根据ID获取
     *
     * @param id 版本ID
     * @return 返回版本信息
     */
    @Nullable
    ProjectVersion getById(Long id);

    /**
     * 更新版本整体状态
     *
     * @param id      版本ID
     * @param status  状态
     * @param message 附加消息
     */
    void update(@NonNull Long id, ProjectVersionStatus status, String message);

    /**
     * 根据ID更新版本状态
     *
     * @param version 版本信息
     */
    void updateById(@NonNull Long id, ProjectVersion version);

}
