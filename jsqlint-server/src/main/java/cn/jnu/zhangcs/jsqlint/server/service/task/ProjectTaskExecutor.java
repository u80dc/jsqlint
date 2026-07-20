package cn.jnu.zhangcs.jsqlint.server.service.task;

import cn.jnu.zhangcs.jsqlint.common.util.FileUtils;
import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectVersionStatus;
import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectVersionStepType;
import cn.jnu.zhangcs.jsqlint.server.entity.ProjectVersion;
import cn.jnu.zhangcs.jsqlint.server.entity.ScheduledTask;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectService;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectVersionStepService;
import cn.jnu.zhangcs.jsqlint.server.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 项目任务处理器
 *
 * @author zhangcs
 */
@Slf4j
@RequiredArgsConstructor
public abstract class ProjectTaskExecutor implements TaskHandler {

    private static final Pattern FILENAME_SANITIZE_PATTERN = Pattern.compile("[^a-zA-Z0-9_\\-]");

    protected static final int BATCH_SIZE = 10;

    protected final TaskService taskService;

    protected final ProjectService projectService;

    protected final ProjectVersionStepService projectVersionStepService;

    protected static final String REPO_DIR = "repo";

    /**
     * 任务完成
     *
     * @param task    任务对象
     * @param version 项目操作版本
     */
    protected void startStep(@NonNull ScheduledTask task,
                             @NonNull ProjectVersion version,
                             @NonNull ProjectVersionStepType stepType) {
        version.setOverallStatus(ProjectVersionStatus.RUNNING);
        version.setCurrentStep(stepType);
        version.setMessage("");
        projectService.updateVersion(version.getId(), version);
        projectVersionStepService.markRunning(version.getProjectId(), version.getId(), stepType, task.getId());
        log.info("任务步骤开始: taskId={}, versionId={}, step={}", task.getId(), version.getId(), stepType);
    }

    protected TaskResult success(@NonNull ScheduledTask task,
                                 @NonNull ProjectVersion version,
                                 @NonNull ProjectVersionStepType stepType) {
        projectVersionStepService.markSuccess(version.getProjectId(), version.getId(), stepType, task.getId());
        version.setOverallStatus(stepType == ProjectVersionStepType.GENERATE_REPORT
                ? ProjectVersionStatus.SUCCESS
                : ProjectVersionStatus.RUNNING);
        version.setCurrentStep(stepType);
        version.setMessage("");
        projectService.updateVersion(version.getId(), version);
        log.info("任务步骤完成: taskId={}, versionId={}, step={}", task.getId(), version.getId(), stepType);
        return TaskResult.success(version.getId(), task.getCodeDir());
    }

    /**
     * 任务执行失败
     *
     * @param task    任务对象
     * @param version 项目操作版本
     * @param message 失败信息
     */
    protected TaskResult failed(ScheduledTask task,
                                ProjectVersion version,
                                ProjectVersionStepType stepType,
                                String message,
                                boolean retryable) {
        Long versionId = null;
        if (version != null) {
            versionId = version.getId();
            if (stepType != null) {
                projectVersionStepService.markFailed(version.getProjectId(), version.getId(), stepType, task.getId(), message);
                version.setCurrentStep(stepType);
            }
            version.setOverallStatus(ProjectVersionStatus.FAILED);
            version.setMessage(message);
            projectService.updateVersion(version.getId(), version);
        }
        return TaskResult.failed(versionId, message, retryable);
    }

    protected TaskResult failed(ScheduledTask task, ProjectVersion version, String message, boolean retryable) {
        return failed(task, version, null, message, retryable);
    }

    /**
     * 获取项目工作目录 </br>
     * ${临时目录}/.jsqlint/${项目名}_${项目ID}
     */
    protected String getWorkspace(String projectName, Long projectId) {
        Path workDir = FileUtils.ensureWorkDir();
        String dirName = String.format("%s_%d",
                sanitizeFileName(projectName), projectId);
        return workDir.resolve(dirName).toString();
    }

    /**
     * 获取项目实际目录 </br>
     * ${临时目录}/.jsqlint/${项目名}_${项目ID}/${git仓库名}
     *
     * @param projectName 项目名
     * @param projectId   项目ID
     * @param gitRepoName git仓库名
     * @return 返回路径
     */
    protected String getCodeDir(String projectName, Long projectId, String gitRepoName) {
        String projectDir = getWorkspace(projectName, projectId);
        String repoDirName = gitRepoName != null ? gitRepoName.replace(".git", "") : projectName;
        return Path.of(projectDir).resolve(repoDirName).toString();
    }

    /**
     * 处理名称，使其可以用于文件或文件夹名称
     *
     * @param name 名称字符串
     * @return 返回处理后的名称
     */
    protected String sanitizeFileName(String name) {
        return FILENAME_SANITIZE_PATTERN.matcher(name).replaceAll("_");
    }

    /**
     * 提取仓库名
     *
     * @param gitUrl 地址
     * @return 返回名称
     */
    protected @Nullable String extractRepoName(String gitUrl) {
        if (!StringUtils.hasText(gitUrl)) {
            return null;
        }
        String[] parts = gitUrl.split("/");
        if (parts.length > 0) {
            String lastPart = parts[parts.length - 1];
            return lastPart.replace(".git", "");
        }
        return null;
    }

}
