package cn.jnu.zhangcs.jsqlint.server.scheduler.task;

import cn.jnu.zhangcs.jsqlint.server.common.enums.TaskType;
import cn.jnu.zhangcs.jsqlint.server.entity.ScheduledTask;
import cn.jnu.zhangcs.jsqlint.server.service.TaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 数据库任务调度器
 *
 * @author zhangcs
 */
@Slf4j
@Service
public class TaskDispatcher {

    private static final int BATCH_SIZE = 20;

    private final TaskService taskService;

    private final ExecutorService executor;

    private final ObjectMapper objectMapper;

    private final Map<TaskType, TaskHandler> handlers = new EnumMap<>(TaskType.class);

    public TaskDispatcher(TaskService taskService,
                          @Qualifier("taskExecutor") ExecutorService executor,
                          ObjectMapper objectMapper,
                          List<TaskHandler> taskHandlers) {
        this.taskService = taskService;
        this.executor = executor;
        this.objectMapper = objectMapper;
        for (TaskHandler handler : taskHandlers) {
            handlers.put(handler.type(), handler);
        }
    }

    @Scheduled(fixedDelay = 5_000)
    public void poll() {
        List<ScheduledTask> tasks = taskService.getExecutableTasks(BATCH_SIZE);
        if (tasks.isEmpty()) {
            return;
        }
        log.debug("读取到可执行任务{}个", tasks.size());
        for (ScheduledTask task : tasks) {
            TaskHandler handler = handlers.get(task.getType());
            if (handler == null) {
                taskService.markFailedOrRetry(task, "不支持的任务类型: " + task.getType(), false);
                continue;
            }
            if (!taskService.tryStart(task.getId())) {
                continue;
            }
            executor.submit(() -> execute(task, handler));
        }
    }

    /**
     * 周期回收僵死 RUNNING 任务（进程重启/执行线程异常退出后遗留）。
     * 时限远大于正常任务时长，独立于 poll 低频执行，避免每次轮询都额外扫描。
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void reclaimStuckTasks() {
        try {
            int reclaimed = taskService.reclaimStuckRunningTasks();
            if (reclaimed > 0) {
                log.warn("回收僵死RUNNING任务{}个", reclaimed);
            }
        } catch (Exception e) {
            log.error("回收僵死RUNNING任务失败", e);
        }
    }

    private void execute(ScheduledTask task, TaskHandler handler) {
        try {
            TaskResult result = handler.execute(task);
            if (result.success()) {
                taskService.markSuccess(task.getId(), result.versionId(), result.codeDir());
                submitAuditAlertTask(task, result);
            } else {
                task.setVersionId(result.versionId());
                taskService.markFailedOrRetry(task, result.errorMessage(), result.retryable());
                submitTaskFailedAlertTask(task, result);
            }
        } catch (Exception e) {
            log.error("任务执行异常: taskId={}, type={}", task.getId(), task.getType(), e);
            taskService.markFailedOrRetry(task, e.getMessage(), true);
        }
    }

    private void submitAuditAlertTask(ScheduledTask task, TaskResult result) {
        if (task.getType() != TaskType.AUDIT || result.versionId() == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", ProjectAlertTaskExecutor.EVENT_AUDIT_COMPLETED);
        payload.put("taskId", task.getId());
        payload.put("versionId", result.versionId());
        submitAlertTask(task, payload);
    }

    private void submitTaskFailedAlertTask(ScheduledTask task, TaskResult result) {
        if (task.getType() == TaskType.ALERT || result.retryable()) {
            return;
        }
        Long versionId = result.versionId() != null ? result.versionId() : task.getVersionId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", ProjectAlertTaskExecutor.EVENT_TASK_FAILED);
        payload.put("taskId", task.getId());
        payload.put("taskType", task.getType().getCode());
        payload.put("versionId", versionId);
        payload.put("errorMessage", result.errorMessage());
        submitAlertTask(task, payload);
    }

    /**
     * 提交衍生告警任务。
     * <p>
     * 这里统一使用 Jackson 生成 payload，避免手写 JSON 时遗漏转义或 null 值处理。
     */
    private void submitAlertTask(ScheduledTask sourceTask, Map<String, Object> payload) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            taskService.submitAlertTask(sourceTask.getProjectId(), payloadJson, sourceTask.getOperatorId());
        } catch (JacksonException e) {
            log.error("构造告警任务载荷失败: sourceTaskId={}, payload={}", sourceTask.getId(), payload, e);
        }
    }
}
