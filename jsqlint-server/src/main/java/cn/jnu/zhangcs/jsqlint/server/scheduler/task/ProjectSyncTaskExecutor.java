package cn.jnu.zhangcs.jsqlint.server.scheduler.task;

import cn.jnu.zhangcs.jsqlint.common.git.CloneResult;
import cn.jnu.zhangcs.jsqlint.common.git.GitHelper;
import cn.jnu.zhangcs.jsqlint.common.git.PullResult;
import cn.jnu.zhangcs.jsqlint.common.util.FileUtils;
import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectVersionStatus;
import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectVersionStepType;
import cn.jnu.zhangcs.jsqlint.server.common.enums.TaskType;
import cn.jnu.zhangcs.jsqlint.server.entity.Credential;
import cn.jnu.zhangcs.jsqlint.server.entity.Project;
import cn.jnu.zhangcs.jsqlint.server.entity.ProjectVersion;
import cn.jnu.zhangcs.jsqlint.server.entity.ScheduledTask;
import cn.jnu.zhangcs.jsqlint.server.service.CredentialService;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectService;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectVersionStepService;
import cn.jnu.zhangcs.jsqlint.server.service.TaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 项目同步任务处理器
 *
 * @author zhangcs
 */
@Slf4j
@Service
public class ProjectSyncTaskExecutor extends ProjectTaskExecutor {

    private final CredentialService credentialService;

    public ProjectSyncTaskExecutor(TaskService taskService,
                                   ProjectService projectService,
                                   ProjectVersionStepService projectVersionStepService,
                                   CredentialService credentialService) {
        super(taskService, projectService, projectVersionStepService);
        this.credentialService = credentialService;
    }

    @Override
    public TaskType type() {
        return TaskType.SYNC;
    }

    /**
     * 处理任务
     *
     * @param task 任务对象
     */
    @Override
    public TaskResult execute(ScheduledTask task) {
        log.debug("处理项目同步任务: taskId={}, projectId={}", task.getId(), task.getProjectId());
        Instant start = Instant.now();
        ProjectVersion version = null;
        try {
            log.debug("检查项目信息");
            Long projectId = task.getProjectId();
            ProjectVersion exist = projectService.getLastVersion(projectId);
            if (exist != null) {
                if (ProjectVersionStatus.is(exist.getOverallStatus(), ProjectVersionStatus.RUNNING)
                        && ProjectVersionStepType.from(exist.getCurrentStep()) == ProjectVersionStepType.SYNC_CODE) {
                    return failed(task, null, "项目同步中", false);
                }
            }

            Project project = projectService.getById(projectId);
            if (project == null) {
                return failed(task, null, "项目不存在", false);
            }

            // 新增项目版本
            version = new ProjectVersion();
            version.setProjectId(projectId);
            version.setRef(project.getRef());
            version.setOverallStatus(ProjectVersionStatus.RUNNING);
            version.setCurrentStep(ProjectVersionStepType.SYNC_CODE);
            version.setOperatorId(task.getOperatorId());
            projectService.addVersion(projectId, version);
            projectVersionStepService.initializeSteps(projectId, version.getId());
            startStep(task, version, ProjectVersionStepType.SYNC_CODE);
            log.debug("新增项目版本信息: versionId={}", version.getId());

            // 拉取
            // 1、Git信息
            String gitUrl = project.getGitUrl();
            String gitRef = project.getRef();
            String gitRepoName = extractRepoName(project.getGitUrl());
            String gitUsername = null;
            String gitPassword = null;
            Long credentialId = project.getCredentialId();
            if (credentialId != null) {
                Credential credential = credentialService.getCredentialById(credentialId);
                if (credential != null) {
                    gitUsername = credential.getAccount();
                    String token = credential.getToken();
                    if (StringUtils.hasText(token)) {
                        gitPassword = token;
                    } else {
                        gitPassword = credential.getPassword();
                    }
                }
            }
            // 2、处理工作目录
            FileUtils.ensureWorkDir();
            String codeDir = getCodeDir(project.getName(), projectId, gitRepoName);
            task.setCodeDir(codeDir);
            FileUtils.deleteDirectory(Path.of(codeDir));
            // 3、从Git拉取
            CloneResult result;
            if (StringUtils.hasText(gitRef)) {
                result = GitHelper.clone(gitUrl, gitUsername, gitPassword, codeDir, gitRef);
            } else {
                result = GitHelper.clone(gitUrl, gitUsername, gitPassword, codeDir);
            }
            if (!result.isSuccess()) {
                throw new RuntimeException(result.getMessage());
            }
            // 4、获取最新提交信息，忽略此处的操作失败
            PullResult pullResult = GitHelper.pull(codeDir);
            if (pullResult.isSuccess()) {
                version.setOperationTime(LocalDateTime.now());
                version.setCommitId(pullResult.getShortCommitId());
                version.setAuthor(pullResult.getAuthorName());
                version.setCommitMessage(pullResult.getCommitMessage());
            } else {
                log.warn("获取提交信息失败: versionId={}, message={}", version.getId(), pullResult.getMessage());
            }

            // 更新状态
            return success(task, version, ProjectVersionStepType.SYNC_CODE);
        } catch (Exception e) {
            log.warn("项目同步失败: {}", e.getMessage(), e);
            return failed(task, version, ProjectVersionStepType.SYNC_CODE, e.getMessage(), true);
        } finally {
            log.info("项目同步任务处理结束，耗时: {}ms", Duration.between(start, Instant.now()).toMillis());
        }
    }

}
