package cn.jnu.zhangcs.jsqlint.server.service;

import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectVersionStepType;
import cn.jnu.zhangcs.jsqlint.server.entity.ProjectVersionStep;
import cn.jnu.zhangcs.jsqlint.server.model.vo.ProjectStatusVO;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * 项目版本执行步骤服务接口
 * <p>负责版本五阶段执行步骤（同步/编译/SQL扫描/规则审核/报告）的状态流转与查询，为流水线进度跟踪与前置依赖校验提供支撑。
 *
 * @author zhangcs
 */
public interface ProjectVersionStepService {

    /**
     * 为指定版本初始化五阶段执行步骤记录
     *
     * @param projectId 项目ID
     * @param versionId 版本ID
     */
    void initializeSteps(@NonNull Long projectId, @NonNull Long versionId);

    /**
     * 将指定步骤标记为执行中
     *
     * @param projectId 项目ID
     * @param versionId 版本ID
     * @param stepType  步骤类型
     * @param taskId    关联任务ID
     */
    void markRunning(@NonNull Long projectId, @NonNull Long versionId, @NonNull ProjectVersionStepType stepType, Long taskId);

    /**
     * 将指定步骤标记为执行成功
     *
     * @param projectId 项目ID
     * @param versionId 版本ID
     * @param stepType  步骤类型
     * @param taskId    关联任务ID
     */
    void markSuccess(@NonNull Long projectId, @NonNull Long versionId, @NonNull ProjectVersionStepType stepType, Long taskId);

    /**
     * 将指定步骤标记为执行失败
     *
     * @param projectId    项目ID
     * @param versionId    版本ID
     * @param stepType     步骤类型
     * @param taskId       关联任务ID
     * @param errorMessage 失败信息
     */
    void markFailed(@NonNull Long projectId, @NonNull Long versionId, @NonNull ProjectVersionStepType stepType, Long taskId, String errorMessage);

    /**
     * 判断指定步骤是否已执行成功（用于流水线前置依赖校验）
     *
     * @param versionId 版本ID
     * @param stepType  步骤类型
     * @return 已成功返回 true
     */
    boolean isStepSuccess(@NonNull Long versionId, @NonNull ProjectVersionStepType stepType);

    /**
     * 查询版本下全部执行步骤
     *
     * @param versionId 版本ID
     * @return 步骤列表
     */
    List<ProjectVersionStep> listByVersion(@NonNull Long versionId);

    /**
     * 删除项目的全部执行步骤
     *
     * @param projectId 项目ID
     */
    void deleteByProjectId(@NonNull Long projectId);

    /**
     * 获取项目最新版本的五阶段执行状态视图
     *
     * @param projectId 项目ID
     * @return 项目状态视图
     */
    ProjectStatusVO getProjectStatus(@NonNull Long projectId);
}
