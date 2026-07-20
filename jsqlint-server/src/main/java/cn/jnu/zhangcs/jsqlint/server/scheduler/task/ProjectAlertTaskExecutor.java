package cn.jnu.zhangcs.jsqlint.server.scheduler.task;

import cn.jnu.zhangcs.jsqlint.server.common.enums.TaskType;
import cn.jnu.zhangcs.jsqlint.server.entity.ScheduledTask;
import cn.jnu.zhangcs.jsqlint.server.service.AlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 项目告警任务执行器。
 * <p>
 * 告警任务由其他任务派生，用 payload 描述触发事件，再委托 {@link AlertService}
 * 按事件类型计算告警规则。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectAlertTaskExecutor implements TaskHandler {

    public static final String EVENT_AUDIT_COMPLETED = "AUDIT_COMPLETED";
    public static final String EVENT_TASK_FAILED = "TASK_FAILED";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final AlertService alertService;

    private final ObjectMapper objectMapper;

    @Override
    public TaskType type() {
        return TaskType.ALERT;
    }

    @Override
    public TaskResult execute(ScheduledTask task) {
        try {
            Map<String, Object> payload = parsePayload(task.getPayload());
            String eventType = stringValue(payload.get("eventType"));
            Long versionId = longValue(payload.get("versionId"));
            Long sourceTaskId = longValue(payload.get("taskId"));

            int triggered = switch (eventType == null ? "" : eventType) {
                case EVENT_AUDIT_COMPLETED -> alertService.evaluateAuditCompleted(task.getProjectId(), versionId, sourceTaskId);
                case EVENT_TASK_FAILED -> alertService.evaluateTaskFailed(toFailedTask(task, payload), stringValue(payload.get("errorMessage")));
                default -> 0;
            };
            log.info("告警任务执行完成: taskId={}, eventType={}, triggered={}", task.getId(), eventType, triggered);
            return TaskResult.success(versionId);
        } catch (Exception e) {
            log.error("告警任务执行失败: taskId={}", task.getId(), e);
            return TaskResult.failed("告警任务执行失败: " + e.getMessage(), true);
        }
    }

    private Map<String, Object> parsePayload(String payload) throws Exception {
        if (payload == null || payload.isBlank()) {
            return Map.of();
        }
        // payload 由 TaskDispatcher 统一生成；这里保持 Map 形式，便于兼容后续事件字段扩展。
        return objectMapper.readValue(payload, MAP_TYPE);
    }

    private ScheduledTask toFailedTask(ScheduledTask alertTask, Map<String, Object> payload) {
        ScheduledTask task = new ScheduledTask();
        task.setId(longValue(payload.get("taskId")));
        task.setType(TaskType.from(intValue(payload.get("taskType"))));
        task.setProjectId(alertTask.getProjectId());
        task.setVersionId(longValue(payload.get("versionId")));
        task.setOperatorId(alertTask.getOperatorId());
        return task;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.parseLong(text);
        }
        return null;
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text);
        }
        return -1;
    }
}
