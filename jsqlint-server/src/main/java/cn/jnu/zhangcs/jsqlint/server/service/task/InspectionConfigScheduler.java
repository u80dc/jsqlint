package cn.jnu.zhangcs.jsqlint.server.service.task;

import cn.jnu.zhangcs.jsqlint.server.common.enums.TaskStatus;
import cn.jnu.zhangcs.jsqlint.server.common.enums.TaskType;
import cn.jnu.zhangcs.jsqlint.server.entity.InspectionConfig;
import cn.jnu.zhangcs.jsqlint.server.entity.ScheduledTask;
import cn.jnu.zhangcs.jsqlint.server.service.InspectionConfigService;
import cn.jnu.zhangcs.jsqlint.server.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class InspectionConfigScheduler {

    private static final int BATCH_SIZE = 20;

    private final InspectionConfigService inspectionConfigService;

    private final TaskService taskService;

    @Scheduled(fixedDelay = 30_000)
    public void scheduleDueInspections() {
        List<InspectionConfig> dueConfigs = inspectionConfigService.findDueConfigs(BATCH_SIZE);
        if (dueConfigs.isEmpty()) {
            return;
        }

        for (InspectionConfig config : dueConfigs) {
            if (!inspectionConfigService.tryClaimDue(config.getId())) {
                continue;
            }

            try {
                ScheduledTask task = new ScheduledTask();
                task.setType(TaskType.INSPECT);
                task.setProjectId(config.getProjectId());
                task.setStatus(TaskStatus.PENDING);
                task.setNextExecTime(System.currentTimeMillis());
                task.setRetryCount(0);
                task.setMaxRetries(0);
                task.setOperatorId(config.getOperatorId());
                task.setPayload("{\"inspectionConfigId\":" + config.getId() + "}");
                Long taskId = taskService.submit(task);
                inspectionConfigService.scheduleNext(config.getId(), config.getCronExpr());
                log.info("已提交巡检任务: configId={}, taskId={}, projectId={}",
                        config.getId(), taskId, config.getProjectId());
            } catch (Exception e) {
                inspectionConfigService.markResult(config.getProjectId(), "FAILED", e.getMessage());
                log.error("提交巡检任务失败: configId={}, projectId={}",
                        config.getId(), config.getProjectId(), e);
            }
        }
    }
}
