package cn.jnu.zhangcs.jsqlint.server.service;

import cn.jnu.zhangcs.jsqlint.server.common.enums.TaskStatus;
import cn.jnu.zhangcs.jsqlint.server.common.enums.TaskType;
import cn.jnu.zhangcs.jsqlint.server.entity.ScheduledTask;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Optional;

/**
 * 任务服务接口
 * <p>负责任务的提交、取消、查询以及执行流转（抢占、成功、失败重试），是数据库驱动调度引擎的核心入口。
 *
 * @author zhangcs
 */
public interface TaskService {

    /**
     * 提交 Git 拉取任务
     */
    long submitGitPullTask(Long projectId, Long versionId, Long operatorId);

    /**
     * 提交周期巡检任务
     */
    long submitInspectTask(Long projectId, String cronExpr, Long operatorId);

    /**
     * 提交告警任务
     */
    long submitAlertTask(Long projectId, String payload, Long operatorId);

    /**
     * 取消任务
     */
    void cancelTask(Long taskId);

    /**
     * 取消项目的周期巡检任务
     */
    void cancelInspectByProject(Long projectId);

    /**
     * 检查项目是否有进行中的任务
     */
    boolean hasRunningTask(Long projectId, TaskType type);

    /**
     * 查询任务状态
     */
    Optional<ScheduledTask> getTask(Long taskId);

    /**
     * 查询项目的任务列表
     */
    List<ScheduledTask> getTasksByProject(Long projectId, TaskType type);

    /**
     * 查询进行中的任务
     */
    List<ScheduledTask> getRunningTasks(Long projectId);

    /**
     * 提交任务
     *
     * @param task 任务对象
     * @return 返回任务ID
     */
    Long submit(@NonNull ScheduledTask task);

    /**
     * 提交任务
     *
     * @param projectId  项目
     * @param userId     操作用户
     * @param taskType   任务类型
     * @param retryCount 重试次数
     * @param maxRetries 最大重试次数
     * @return 返回任务ID
     */
    Long submit(Long projectId, Long userId, TaskType taskType, int retryCount, int maxRetries);

    /**
     * 获取任务
     *
     * @param taskType   类型
     * @param taskStatus 状态
     * @param batchSize  数量
     * @return 返回任务列表
     */
    List<ScheduledTask> getTasks(TaskType taskType, TaskStatus taskStatus, int batchSize);

    /**
     * 查询已到执行时间的待执行任务。
     */
    List<ScheduledTask> getExecutableTasks(int batchSize);

    /**
     * 原子抢占任务，成功后任务进入 RUNNING。
     */
    boolean tryStart(@NonNull Long taskId);

    /**
     * 标记任务成功。
     */
    void markSuccess(@NonNull Long taskId, Long versionId, String codeDir);

    /**
     * 标记任务失败；可重试任务会进入 RETRYING 并设置下一次执行时间。
     */
    void markFailedOrRetry(@NonNull ScheduledTask task, String errorMessage, boolean retryable);

    /**
     * 更新任务
     *
     * @param id   任务ID
     * @param task 任务信息
     */
    void update(@NonNull Long id, @NonNull ScheduledTask task);
}
