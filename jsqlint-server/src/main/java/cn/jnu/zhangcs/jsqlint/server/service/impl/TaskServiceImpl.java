package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.enums.TaskStatus;
import cn.jnu.zhangcs.jsqlint.server.common.enums.TaskType;
import cn.jnu.zhangcs.jsqlint.server.entity.ScheduledTask;
import cn.jnu.zhangcs.jsqlint.server.mapper.ScheduledTaskMapper;
import cn.jnu.zhangcs.jsqlint.server.service.SystemConfigService;
import cn.jnu.zhangcs.jsqlint.server.service.TaskService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class TaskServiceImpl implements TaskService {

    /**
     * RUNNING 任务超时回收时限兜底值（分钟）：系统配置缺失或非法时使用。
     */
    private static final int DEFAULT_RUNNING_TASK_TIMEOUT_MINUTES = 60;

    private final ScheduledTaskMapper mapper;

    private final SystemConfigService systemConfigService;

    @Override
    @Transactional
    public long submitGitPullTask(Long projectId, Long versionId, Long operatorId) {
        if (hasRunningTask(projectId, TaskType.SYNC)) {
            log.warn("项目已有进行中的 GitPull 任务: projectId={}", projectId);
            return -1;
        }

        ScheduledTask task = new ScheduledTask();
        task.setType(TaskType.SYNC);
        task.setProjectId(projectId);
        task.setVersionId(versionId);
        task.setStatus(TaskStatus.PENDING);
        task.setNextExecTime(System.currentTimeMillis());
        task.setRetryCount(0);
        task.setMaxRetries(3);
        task.setOperatorId(operatorId);

        mapper.insert(task);
        log.info("已提交 GitPull 任务: taskId={}, projectId={}, versionId={}", task.getId(), projectId, versionId);
        return task.getId();
    }

    @Override
    @Transactional
    public long submitInspectTask(Long projectId, String cronExpr, Long operatorId) {
        ScheduledTask task = new ScheduledTask();
        task.setType(TaskType.INSPECT);
        task.setProjectId(projectId);
        task.setStatus(TaskStatus.PENDING);
        task.setCronExpr(cronExpr);
        task.setNextExecTime(System.currentTimeMillis());
        task.setRetryCount(0);
        task.setMaxRetries(3);
        task.setOperatorId(operatorId);

        mapper.insert(task);
        log.info("已提交 Inspect 任务: taskId={}, projectId={}, cronExpr={}", task.getId(), projectId, cronExpr);
        return task.getId();
    }

    @Override
    @Transactional
    public long submitAlertTask(Long projectId, String payload, Long operatorId) {
        ScheduledTask task = new ScheduledTask();
        task.setType(TaskType.ALERT);
        task.setProjectId(projectId);
        task.setStatus(TaskStatus.PENDING);
        task.setNextExecTime(System.currentTimeMillis());
        task.setRetryCount(0);
        task.setMaxRetries(3);
        task.setPayload(payload);
        task.setOperatorId(operatorId);

        mapper.insert(task);
        log.info("已提交 Alert 任务: taskId={}, projectId={}", task.getId(), projectId);
        return task.getId();
    }

    @Override
    @Transactional
    public void cancelTask(Long taskId) {
        int updated = mapper.update(null, new LambdaUpdateWrapper<ScheduledTask>()
                .set(ScheduledTask::getStatus, TaskStatus.CANCELLED.getCode())
                .set(ScheduledTask::getUpdateTime, LocalDateTime.now())
                .eq(ScheduledTask::getId, taskId));
        if (updated > 0) {
            log.info("任务已取消: taskId={}", taskId);
        }
    }

    @Override
    @Transactional
    public void cancelInspectByProject(Long projectId) {
        List<ScheduledTask> tasks = getByProjectAndType(projectId, TaskType.INSPECT);
        for (ScheduledTask task : tasks) {
            mapper.update(null, new LambdaUpdateWrapper<ScheduledTask>()
                    .set(ScheduledTask::getStatus, TaskStatus.CANCELLED.getCode())
                    .set(ScheduledTask::getUpdateTime, LocalDateTime.now())
                    .eq(ScheduledTask::getId, task.getId()));
            log.info("已取消周期巡检任务: taskId={}, projectId={}", task.getId(), projectId);
        }
    }

    private List<ScheduledTask> getByProjectAndType(Long projectId, TaskType type) {
        return mapper.selectList(new LambdaQueryWrapper<ScheduledTask>()
                .eq(ScheduledTask::getProjectId, projectId)
                .eq(ScheduledTask::getType, type)
                .in(ScheduledTask::getStatus,
                        TaskStatus.PENDING.getCode(),
                        TaskStatus.RUNNING.getCode(),
                        TaskStatus.RETRYING.getCode()));
    }

    @Override
    public boolean hasRunningTask(Long projectId, TaskType type) {
        LambdaQueryWrapper<ScheduledTask> params = Wrappers.<ScheduledTask>lambdaQuery()
                .eq(ScheduledTask::getProjectId, projectId)
                .eq(ScheduledTask::getType, type)
                .in(ScheduledTask::getStatus, List.of(0, 1, 5));
        return mapper.selectCount(params) > 0;
    }

    @Override
    public Optional<ScheduledTask> getTask(Long taskId) {
        return Optional.ofNullable(mapper.selectById(taskId));
    }

    @Override
    public List<ScheduledTask> getTasksByProject(Long projectId, TaskType type) {
        return getByProjectAndType(projectId, type);
    }

    @Override
    public List<ScheduledTask> getRunningTasks(Long projectId) {
        LambdaQueryWrapper<ScheduledTask> params = Wrappers.<ScheduledTask>lambdaQuery()
                .eq(ScheduledTask::getProjectId, projectId)
                .in(ScheduledTask::getStatus, 1);
        Page<ScheduledTask> pages = mapper.selectPage(new Page<>(0, 100), params);
        return pages.getRecords();
    }

    @Override
    public Long submit(@NonNull ScheduledTask task) {
        if (task.getProjectId() != null && task.getType() != null) {
            List<ScheduledTask> activeTasks = getByProjectAndType(task.getProjectId(), task.getType());
            if (!activeTasks.isEmpty()) {
                ScheduledTask activeTask = activeTasks.getFirst();
                log.info("项目已有活跃任务，复用任务ID: projectId={}, type={}, taskId={}",
                        task.getProjectId(), task.getType(), activeTask.getId());
                return activeTask.getId();
            }
        }
        if (task.getStatus() == null) {
            task.setStatus(TaskStatus.PENDING);
        }
        if (task.getNextExecTime() == null) {
            task.setNextExecTime(System.currentTimeMillis());
        }
        if (task.getRetryCount() == null) {
            task.setRetryCount(0);
        }
        if (task.getMaxRetries() == null) {
            task.setMaxRetries(3);
        }
        mapper.insert(task);
        return task.getId();
    }

    @Override
    public Long submit(Long projectId, Long userId, TaskType taskType, int retryCount, int maxRetries) {
        ScheduledTask task = new ScheduledTask();
        task.setType(taskType);
        task.setProjectId(projectId);
        task.setVersionId(null);
        task.setStatus(TaskStatus.PENDING);
        task.setNextExecTime(System.currentTimeMillis());
        task.setRetryCount(retryCount);
        task.setMaxRetries(maxRetries);
        task.setOperatorId(userId);
        return submit(task);
    }

    @Override
    public List<ScheduledTask> getTasks(TaskType type, TaskStatus status, int size) {
        LambdaQueryWrapper<ScheduledTask> query = Wrappers.<ScheduledTask>lambdaQuery()
                .eq(ScheduledTask::getType, type.getCode())
                .eq(ScheduledTask::getStatus, status.getCode());
        Page<ScheduledTask> result = mapper.selectPage(new Page<>(1, size), query);
        return result.getRecords();
    }

    @Override
    public List<ScheduledTask> getExecutableTasks(int batchSize) {
        LambdaQueryWrapper<ScheduledTask> params = new LambdaQueryWrapper<ScheduledTask>()
                .in(ScheduledTask::getStatus,
                        TaskStatus.PENDING.getCode(),
                        TaskStatus.RETRYING.getCode())
                .le(ScheduledTask::getNextExecTime, System.currentTimeMillis())
                .orderByAsc(ScheduledTask::getNextExecTime);
        return mapper.selectPage(new Page<>(0, batchSize), params)
                .getRecords();
    }

    @Override
    @Transactional
    public boolean tryStart(@NonNull Long taskId) {
        LambdaUpdateWrapper<ScheduledTask> wrapper = new LambdaUpdateWrapper<ScheduledTask>()
                .set(ScheduledTask::getStatus, TaskStatus.RUNNING.getCode())
                .set(ScheduledTask::getErrorMessage, null)
                .set(ScheduledTask::getUpdateTime, LocalDateTime.now())
                .eq(ScheduledTask::getId, taskId)
                .in(ScheduledTask::getStatus,
                        TaskStatus.PENDING.getCode(),
                        TaskStatus.RETRYING.getCode())
                .le(ScheduledTask::getNextExecTime, System.currentTimeMillis());
        return mapper.update(wrapper) > 0;
    }

    @Override
    @Transactional
    public int reclaimStuckRunningTasks() {
        int timeoutMinutes = runningTaskTimeoutMinutes();
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(timeoutMinutes);
        List<ScheduledTask> stuckTasks = mapper.selectList(new LambdaQueryWrapper<ScheduledTask>()
                .eq(ScheduledTask::getStatus, TaskStatus.RUNNING.getCode())
                .lt(ScheduledTask::getUpdateTime, deadline));
        for (ScheduledTask task : stuckTasks) {
            mapper.update(null, new LambdaUpdateWrapper<ScheduledTask>()
                    .set(ScheduledTask::getStatus, TaskStatus.FAILED.getCode())
                    .set(ScheduledTask::getErrorMessage, "任务执行超时回收: 超过"
                            + timeoutMinutes + "分钟未更新")
                    .set(ScheduledTask::getUpdateTime, LocalDateTime.now())
                    .eq(ScheduledTask::getId, task.getId()));
            log.warn("回收僵死RUNNING任务: taskId={}, projectId={}, type={}, lastUpdate={}",
                    task.getId(), task.getProjectId(), task.getType(), task.getUpdateTime());
        }
        return stuckTasks.size();
    }

    /**
     * 从系统设置读取回收时限；配置缺失或非法时回退默认值，保证回收链路不因配置问题中断。
     */
    private int runningTaskTimeoutMinutes() {
        try {
            Integer configured = systemConfigService.getSettings()
                    .getProjectAnalysis()
                    .getRunningTaskTimeoutMinutes();
            if (configured != null && configured > 0) {
                return configured;
            }
        } catch (Exception e) {
            log.warn("读取任务超时配置失败，使用默认值{}分钟", DEFAULT_RUNNING_TASK_TIMEOUT_MINUTES, e);
        }
        return DEFAULT_RUNNING_TASK_TIMEOUT_MINUTES;
    }

    @Override
    @Transactional
    public void markSuccess(@NonNull Long taskId, Long versionId, String codeDir) {
        LambdaUpdateWrapper<ScheduledTask> wrapper = new LambdaUpdateWrapper<ScheduledTask>()
                .set(ScheduledTask::getStatus, TaskStatus.SUCCESS.getCode())
                .set(ScheduledTask::getVersionId, versionId)
                .set(codeDir != null, ScheduledTask::getCodeDir, codeDir)
                .set(ScheduledTask::getErrorMessage, null)
                .set(ScheduledTask::getUpdateTime, LocalDateTime.now())
                .eq(ScheduledTask::getId, taskId);
        mapper.update(wrapper);
    }

    @Override
    @Transactional
    public void markFailedOrRetry(@NonNull ScheduledTask task, String errorMessage, boolean retryable) {
        String message = errorMessage != null ? errorMessage : "任务执行失败";
        int retryCount = task.getRetryCount() != null ? task.getRetryCount() : 0;
        int maxRetries = task.getMaxRetries() != null ? task.getMaxRetries() : 0;
        if (retryable && retryCount < maxRetries) {
            long nextExecTime = System.currentTimeMillis() + retryDelayMillis(retryCount + 1);
            mapper.update(null, new LambdaUpdateWrapper<ScheduledTask>()
                    .set(ScheduledTask::getStatus, TaskStatus.RETRYING.getCode())
                    .set(task.getVersionId() != null, ScheduledTask::getVersionId, task.getVersionId())
                    .setSql("retry_count = retry_count + 1")
                    .set(ScheduledTask::getNextExecTime, nextExecTime)
                    .set(ScheduledTask::getErrorMessage, message)
                    .set(ScheduledTask::getUpdateTime, LocalDateTime.now())
                    .eq(ScheduledTask::getId, task.getId()));
            log.warn("任务执行失败，等待重试: taskId={}, retry={}/{}, message={}",
                    task.getId(), retryCount + 1, maxRetries, message);
            return;
        }

        mapper.update(null, new LambdaUpdateWrapper<ScheduledTask>()
                .set(ScheduledTask::getStatus, TaskStatus.FAILED.getCode())
                .set(task.getVersionId() != null, ScheduledTask::getVersionId, task.getVersionId())
                .set(ScheduledTask::getErrorMessage, message)
                .set(ScheduledTask::getUpdateTime, LocalDateTime.now())
                .eq(ScheduledTask::getId, task.getId()));
        log.warn("任务执行失败并终止: taskId={}, message={}", task.getId(), message);
    }

    @Override
    public void update(@NonNull Long id, @NonNull ScheduledTask task) {
        task.setId(id);
        mapper.updateById(task);
    }

    private long retryDelayMillis(int retryIndex) {
        return switch (retryIndex) {
            case 1 -> 60_000L;
            case 2 -> 5 * 60_000L;
            default -> 15 * 60_000L;
        };
    }

}
