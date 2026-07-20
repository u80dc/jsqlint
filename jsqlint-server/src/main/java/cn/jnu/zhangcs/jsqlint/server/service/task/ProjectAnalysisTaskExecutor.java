package cn.jnu.zhangcs.jsqlint.server.service.task;

import cn.jnu.zhangcs.jsqlint.analysis.AnalyzeResult;
import cn.jnu.zhangcs.jsqlint.analysis.Analyzer;
import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectVersionStepType;
import cn.jnu.zhangcs.jsqlint.server.common.enums.TaskType;
import cn.jnu.zhangcs.jsqlint.server.entity.Project;
import cn.jnu.zhangcs.jsqlint.server.entity.ProjectVersion;
import cn.jnu.zhangcs.jsqlint.server.entity.ScheduledTask;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectService;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectVersionStepService;
import cn.jnu.zhangcs.jsqlint.server.service.SqlStatementService;
import cn.jnu.zhangcs.jsqlint.server.service.TaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * 项目分析任务执行器
 * 负责执行 SQL 代码分析，提取 SQL 语句
 *
 * @author zhangcs
 */
@Slf4j
@Service
public class ProjectAnalysisTaskExecutor extends ProjectTaskExecutor {

    private final SqlStatementService sqlStatementService;

    public ProjectAnalysisTaskExecutor(TaskService taskService,
                                       ProjectService projectService,
                                       ProjectVersionStepService projectVersionStepService,
                                       SqlStatementService sqlStatementService) {
        super(taskService, projectService, projectVersionStepService);
        this.sqlStatementService = sqlStatementService;
    }

    @Override
    public TaskType type() {
        return TaskType.ANALYSE;
    }

    @Override
    public TaskResult execute(ScheduledTask task) {
        log.info("开始执行项目分析任务: taskId={}, projectId={}", task.getId(), task.getProjectId());
        Instant start = Instant.now();
        ProjectVersion version = null;

        try {
            // 1. 检查项目信息
            Long projectId = task.getProjectId();
            version = projectService.getLastVersion(projectId);

            if (version == null) {
                return failed(task, null, "项目版本不存在", false);
            }

            if (!projectVersionStepService.isStepSuccess(version.getId(), ProjectVersionStepType.COMPILE_CHECK)) {
                return failed(task, version, ProjectVersionStepType.SQL_SCAN, "项目未完成编译", false);
            }

            // 2. 更新版本状态为分析中
            startStep(task, version, ProjectVersionStepType.SQL_SCAN);
            log.info("更新版本状态为SQL扫描中: versionId=", version.getId());

            // 3. 获取项目和代码目录
            Project project = projectService.getById(projectId);
            if (project == null) {
                return failed(task, version, ProjectVersionStepType.SQL_SCAN, "项目不存在", false);
            }

            String codeDir = getCodeDir(project.getName(), projectId, extractRepoName(project.getGitUrl()));

            // 4. 检查编译产物是否存在
            Path source = Path.of(codeDir).resolve("target").resolve("classes");
            if (!Files.exists(source)) {
                return failed(task, version, ProjectVersionStepType.SQL_SCAN, "编译产物不存在，请先执行编译", false);
            }

            log.info("开始分析项目: projectId={}, versionId={}, targetPath={}",
                    projectId, version.getId(), source);

            // 5. 执行 SQL 分析
            Path library = Path.of(getWorkspace(project.getName(), projectId)).resolve(REPO_DIR);
            Analyzer analyzer = new Analyzer(source, library);
            AnalyzeResult result = analyzer.analyze();
            if (!result.isSuccess()) {
                String message = result.getDiagnostics().isEmpty()
                        ? "SQL分析失败"
                        : String.join("; ", result.getDiagnostics());
                return failed(task, version, ProjectVersionStepType.SQL_SCAN, message, true);
            }
            sqlStatementService.replaceVersionAnalysis(projectId, version.getId(), result);

            log.info("分析完成: projectId={}, versionId={}, sqlCount={}",
                    projectId, version.getId(), result.getStatistics().getTotalStatements());

            // 6. 更新版本状态为分析完成
            return success(task, version, ProjectVersionStepType.SQL_SCAN);

        } catch (Exception e) {
            log.error("项目分析失败: projectId={}, versionId={}",
                    task.getProjectId(), version != null ? version.getId() : null, e);
            return failed(task, version, ProjectVersionStepType.SQL_SCAN, "分析异常: " + e.getMessage(), true);
        } finally {
            long duration = Duration.between(start, Instant.now()).toMillis();
            log.info("项目分析任务处理结束，耗时: {}ms", duration);
        }
    }
}
