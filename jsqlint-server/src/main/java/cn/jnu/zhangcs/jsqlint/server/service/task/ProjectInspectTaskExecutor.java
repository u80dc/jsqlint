package cn.jnu.zhangcs.jsqlint.server.service.task;

import cn.jnu.zhangcs.jsqlint.server.common.enums.TaskType;
import cn.jnu.zhangcs.jsqlint.server.entity.ScheduledTask;
import cn.jnu.zhangcs.jsqlint.server.service.InspectionConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Service
public class ProjectInspectTaskExecutor implements TaskHandler {

    private final ProjectSyncTaskExecutor syncTaskExecutor;

    private final ProjectCompileTaskExecutor compileTaskExecutor;

    private final ProjectAnalysisTaskExecutor analysisTaskExecutor;

    private final ProjectAuditTaskExecutor auditTaskExecutor;

    private final InspectionConfigService inspectionConfigService;

    public ProjectInspectTaskExecutor(ProjectSyncTaskExecutor syncTaskExecutor,
                                      ProjectCompileTaskExecutor compileTaskExecutor,
                                      ProjectAnalysisTaskExecutor analysisTaskExecutor,
                                      ProjectAuditTaskExecutor auditTaskExecutor,
                                      InspectionConfigService inspectionConfigService) {
        this.syncTaskExecutor = syncTaskExecutor;
        this.compileTaskExecutor = compileTaskExecutor;
        this.analysisTaskExecutor = analysisTaskExecutor;
        this.auditTaskExecutor = auditTaskExecutor;
        this.inspectionConfigService = inspectionConfigService;
    }

    @Override
    public TaskType type() {
        return TaskType.INSPECT;
    }

    @Override
    public TaskResult execute(ScheduledTask task) {
        log.info("开始执行项目巡检任务: taskId={}, projectId={}", task.getId(), task.getProjectId());
        Instant start = Instant.now();
        try {
            TaskResult syncResult = syncTaskExecutor.execute(childTask(task, TaskType.SYNC, null, null));
            if (!syncResult.success()) {
                return failed(task, syncResult, "同步失败: " + syncResult.errorMessage());
            }

            TaskResult compileResult = compileTaskExecutor.execute(
                    childTask(task, TaskType.COMPILE, syncResult.versionId(), syncResult.codeDir()));
            if (!compileResult.success()) {
                return failed(task, compileResult, "编译失败: " + compileResult.errorMessage());
            }

            TaskResult analysisResult = analysisTaskExecutor.execute(
                    childTask(task, TaskType.ANALYSE, compileResult.versionId(), compileResult.codeDir()));
            if (!analysisResult.success()) {
                return failed(task, analysisResult, "分析失败: " + analysisResult.errorMessage());
            }

            TaskResult auditResult = auditTaskExecutor.execute(
                    childTask(task, TaskType.AUDIT, analysisResult.versionId(), analysisResult.codeDir()));
            if (!auditResult.success()) {
                return failed(task, auditResult, "审核失败: " + auditResult.errorMessage());
            }

            inspectionConfigService.markResult(task.getProjectId(), "SUCCESS", null);
            log.info("项目巡检任务完成: taskId={}, projectId={}, versionId={}, duration={}ms",
                    task.getId(), task.getProjectId(), auditResult.versionId(),
                    Duration.between(start, Instant.now()).toMillis());
            return TaskResult.success(auditResult.versionId(), auditResult.codeDir());
        } catch (Exception e) {
            inspectionConfigService.markResult(task.getProjectId(), "FAILED", e.getMessage());
            log.error("项目巡检任务异常: taskId={}, projectId={}", task.getId(), task.getProjectId(), e);
            return TaskResult.failed(null, "巡检异常: " + e.getMessage(), true);
        }
    }

    private TaskResult failed(ScheduledTask task, TaskResult result, String message) {
        inspectionConfigService.markResult(task.getProjectId(), "FAILED", message);
        return TaskResult.failed(result.versionId(), message, result.retryable());
    }

    private ScheduledTask childTask(ScheduledTask parent, TaskType type, Long versionId, String codeDir) {
        ScheduledTask child = new ScheduledTask();
        child.setId(parent.getId());
        child.setType(type);
        child.setProjectId(parent.getProjectId());
        child.setVersionId(versionId);
        child.setOperatorId(parent.getOperatorId());
        child.setPayload(parent.getPayload());
        child.setCodeDir(codeDir);
        child.setRetryCount(parent.getRetryCount());
        child.setMaxRetries(parent.getMaxRetries());
        return child;
    }
}
