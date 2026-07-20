package cn.jnu.zhangcs.jsqlint.server.scheduler.task;

import cn.jnu.zhangcs.jsqlint.server.common.enums.TaskType;
import cn.jnu.zhangcs.jsqlint.server.entity.ScheduledTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Service
public class ProjectExecuteTaskExecutor implements TaskHandler {

    private final ProjectSyncTaskExecutor syncTaskExecutor;

    private final ProjectCompileTaskExecutor compileTaskExecutor;

    private final ProjectAnalysisTaskExecutor analysisTaskExecutor;

    private final ProjectAuditTaskExecutor auditTaskExecutor;

    private final ProjectReportTaskExecutor reportTaskExecutor;

    public ProjectExecuteTaskExecutor(ProjectSyncTaskExecutor syncTaskExecutor,
                                      ProjectCompileTaskExecutor compileTaskExecutor,
                                      ProjectAnalysisTaskExecutor analysisTaskExecutor,
                                      ProjectAuditTaskExecutor auditTaskExecutor,
                                      ProjectReportTaskExecutor reportTaskExecutor) {
        this.syncTaskExecutor = syncTaskExecutor;
        this.compileTaskExecutor = compileTaskExecutor;
        this.analysisTaskExecutor = analysisTaskExecutor;
        this.auditTaskExecutor = auditTaskExecutor;
        this.reportTaskExecutor = reportTaskExecutor;
    }

    @Override
    public TaskType type() {
        return TaskType.EXECUTE;
    }

    @Override
    public TaskResult execute(ScheduledTask task) {
        log.info("开始执行项目完整任务: taskId={}, projectId={}", task.getId(), task.getProjectId());
        Instant start = Instant.now();
        try {
            TaskResult syncResult = syncTaskExecutor.execute(childTask(task, TaskType.SYNC, null, null));
            if (!syncResult.success()) {
                return failed(syncResult, "同步代码失败: " + syncResult.errorMessage());
            }

            TaskResult compileResult = compileTaskExecutor.execute(
                    childTask(task, TaskType.COMPILE, syncResult.versionId(), syncResult.codeDir()));
            if (!compileResult.success()) {
                return failed(compileResult, "编译检查失败: " + compileResult.errorMessage());
            }

            TaskResult analysisResult = analysisTaskExecutor.execute(
                    childTask(task, TaskType.ANALYSE, compileResult.versionId(), compileResult.codeDir()));
            if (!analysisResult.success()) {
                return failed(analysisResult, "SQL扫描失败: " + analysisResult.errorMessage());
            }

            TaskResult auditResult = auditTaskExecutor.execute(
                    childTask(task, TaskType.AUDIT, analysisResult.versionId(), analysisResult.codeDir()));
            if (!auditResult.success()) {
                return failed(auditResult, "规则审核失败: " + auditResult.errorMessage());
            }

            TaskResult reportResult = reportTaskExecutor.execute(
                    childTask(task, TaskType.REPORT, auditResult.versionId(), auditResult.codeDir()));
            if (!reportResult.success()) {
                return failed(reportResult, "生成报告失败: " + reportResult.errorMessage());
            }

            log.info("项目完整任务执行完成: taskId={}, projectId={}, versionId={}, duration={}ms",
                    task.getId(), task.getProjectId(), reportResult.versionId(),
                    Duration.between(start, Instant.now()).toMillis());
            return TaskResult.success(reportResult.versionId(), reportResult.codeDir());
        } catch (Exception e) {
            log.error("项目完整任务执行异常: taskId={}, projectId={}", task.getId(), task.getProjectId(), e);
            return TaskResult.failed(task.getVersionId(), "执行异常: " + e.getMessage(), true);
        }
    }

    private TaskResult failed(TaskResult result, String message) {
        log.warn("项目完整任务中止: versionId={}, message={}", result.versionId(), message);
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
