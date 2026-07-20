package cn.jnu.zhangcs.jsqlint.server.scheduler.task;

import cn.jnu.zhangcs.jsqlint.common.mvn.CompileResult;
import cn.jnu.zhangcs.jsqlint.common.mvn.MavenCompiler;
import cn.jnu.zhangcs.jsqlint.common.mvn.MavenRepo;
import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectVersionStepType;
import cn.jnu.zhangcs.jsqlint.server.common.enums.TaskType;
import cn.jnu.zhangcs.jsqlint.server.entity.MavenRepository;
import cn.jnu.zhangcs.jsqlint.server.entity.Project;
import cn.jnu.zhangcs.jsqlint.server.entity.ProjectVersion;
import cn.jnu.zhangcs.jsqlint.server.entity.ScheduledTask;
import cn.jnu.zhangcs.jsqlint.server.service.MavenRepositoryService;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectService;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectVersionStepService;
import cn.jnu.zhangcs.jsqlint.server.service.TaskService;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.springframework.stereotype.Service;

import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ProjectCompileTaskExecutor extends ProjectTaskExecutor {

    private final MavenRepositoryService mavenRepositoryService;

    public ProjectCompileTaskExecutor(TaskService taskService, ProjectService projectService,
                                      ProjectVersionStepService projectVersionStepService,
                                      MavenRepositoryService mavenRepositoryService) {
        super(taskService, projectService, projectVersionStepService);
        this.mavenRepositoryService = mavenRepositoryService;
    }

    @Override
    public TaskType type() {
        return TaskType.COMPILE;
    }

    @Override
    public TaskResult execute(ScheduledTask task) {
        log.debug("处理项目编译任务: taskId={}, projectId={}", task.getId(), task.getProjectId());
        Instant start = Instant.now();
        ProjectVersion version = null;
        try {
            log.debug("检查项目信息");
            Long projectId = task.getProjectId();

            // 获取最新的版本信息
            version = projectService.getLastVersion(projectId);
            if (version == null) {
                // 版本不存在
                return failed(task, null, "项目版本不存在", false);
            }
            if (!projectVersionStepService.isStepSuccess(version.getId(), ProjectVersionStepType.SYNC_CODE)) {
                // 项目未同步
                return failed(task, version, ProjectVersionStepType.COMPILE_CHECK, "项目未同步", false);
            }
            startStep(task, version, ProjectVersionStepType.COMPILE_CHECK);

            // 获取代码目录
            Project project = projectService.getById(projectId);
            if (project == null) {
                return failed(task, version, "项目不存在", false);
            }
            String codeDir = getCodeDir(project.getName(), projectId, extractRepoName(project.getGitUrl()));
            task.setCodeDir(codeDir);
            Path projectPath = Path.of(codeDir);
            log.info("获取代码路径: {}", codeDir);

            // 检查 pom.xml 是否存在
            Path pomPath = projectPath.resolve("pom.xml");
            if (!Files.exists(pomPath)) {
                return failed(task, version, "pom.xml文件不存在", false);
            }

            // 本地仓库路径（存储依赖）
            Path repoPath = Path.of(getWorkspace(project.getName(), projectId))
                    .resolve(REPO_DIR);

            List<MavenRepository> repositories = mavenRepositoryService.list(1);

            log.info("开始编译项目: {}", project.getName());
            log.debug("\t代码路径: {}", projectPath);
            log.debug("\t本地仓库: {}", repoPath);

            Set<MavenRepo> repos = repositories.stream()
                    .map(r -> new MavenRepo(r.getRepoId(), r.getUrl(), r.getReleasesEnabled(), r.getSnapshotsEnabled()))
                    .collect(Collectors.toSet());

            CompileResult compileResult = MavenCompiler.compile(projectPath.toAbsolutePath().toString(), repoPath.toAbsolutePath().toString(), repos);
            if (compileResult.isSuccess()) {
                log.info("编译成功: {}", project.getName());
                // 编译产物在项目的 target/classes 目录

                TaskResult result = success(task, version, ProjectVersionStepType.COMPILE_CHECK);

                Path defaultTarget = projectPath.resolve("target").resolve("classes");
                log.info("编译任务执行成功，耗时{}ms: projectId={}", Duration.between(start, Instant.now()).toMillis(), projectId);
                log.info("target: {}", defaultTarget);
                return result;
            } else {
                List<String> errors = new ArrayList<>(compileResult.getErrors());
                log.error("编译失败: {}, 错误: {}", project.getName(), errors);
                String message = errors.isEmpty() ? "编译失败" : errors.getFirst();
                return failed(task, version, ProjectVersionStepType.COMPILE_CHECK, message, false);
            }
        } catch (Exception e) {
            log.error("编译任务执行失败: projectId={}", task.getProjectId(), e);
            return failed(task, version, ProjectVersionStepType.COMPILE_CHECK, e.getMessage(), true);
        }
    }

    /**
     * 从 pom.xml 读取项目配置的 Java 版本
     */
    private String getJavaVersionFromPom(Path projectPath) {
        Path pomPath = projectPath.resolve("pom.xml");
        try (FileReader reader = new FileReader(pomPath.toFile())) {
            MavenXpp3Reader xpp3Reader = new MavenXpp3Reader();
            Model model = xpp3Reader.read(reader);

            // 1. 优先从 maven-compiler-plugin 读取
            String version = getCompilerPluginVersion(model);
            if (version != null) {
                return version;
            }

            // 2. 从 properties 读取 maven.compiler.source/target
            Properties props = model.getProperties();
            if (props.containsKey("maven.compiler.source")) {
                return props.getProperty("maven.compiler.source");
            }
            if (props.containsKey("java.version")) {
                return props.getProperty("java.version");
            }

            // 3. 默认值
            return "17";
        } catch (Exception e) {
            log.warn("读取 pom.xml 版本失败，使用默认值: {}", e.getMessage());
            return "17";
        }
    }

    /**
     * 获取 maven-compiler-plugin 配置的版本
     */
    private String getCompilerPluginVersion(Model model) {
        List<Plugin> plugins = model.getBuild().getPlugins();
        for (Plugin plugin : plugins) {
            if ("org.apache.maven.plugins".equals(plugin.getGroupId()) && "maven-compiler-plugin".equals(plugin.getArtifactId())) {
                // 尝试从 configuration 读取
                String version = extractVersionFromConfig(plugin.getConfiguration());
                if (version != null) {
                    return version;
                }
                // 尝试从 executions 读取
                for (PluginExecution exec : plugin.getExecutions()) {
                    version = extractVersionFromConfig(exec.getConfiguration());
                    if (version != null) {
                        return version;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 从配置节点提取版本
     */
    private String extractVersionFromConfig(Object config) {
        if (config instanceof Xpp3Dom dom) {
            Xpp3Dom release = dom.getChild("release");
            if (release != null) {
                return release.getValue();
            }
            Xpp3Dom source = dom.getChild("source");
            if (source != null) {
                return source.getValue();
            }
        }
        return null;
    }

}
