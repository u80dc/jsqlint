package cn.jnu.zhangcs.jsqlint.common.mvn;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Maven Helper工具类
 * <p>
 * 通过子进程执行Maven命令，支持自定义本地仓库和远程仓库配置。
 *
 * @author zhangcs
 */
@Slf4j
public class MavenHelper {

    /**
     * 默认本地仓库
     */
    private static final String DEFAULT_LOCAL_REPO = System.getProperty("java.io.tmpdir") + File.separator + ".jsqlint" + File.separator + "repo";

    /**
     * 编译项目
     *
     * @param basedir      项目路径，该路径下有pom.xml
     * @param localRepo    本地仓库地址，默认将所有的依赖下载到该路径，如果为空则使用DEFAULT_LOCAL_REPO
     * @param repositories 远程仓库，如果pom.xml中没有指定仓库信息，则从该远程仓库中下载
     * @return 编译结果
     */
    @NonNull
    public static CompileResult compile(@NonNull String basedir, String localRepo, Collection<MavenRepo> repositories) {
        log.info("开始编译项目: {}", basedir);

        String effectiveLocalRepo = localRepo;
        if (effectiveLocalRepo == null || effectiveLocalRepo.isBlank()) {
            effectiveLocalRepo = DEFAULT_LOCAL_REPO;
        }
        log.info("使用本地仓库: {}", effectiveLocalRepo);

        Instant start = Instant.now();
        try {
            Path localRepoPath = Path.of(effectiveLocalRepo);
            if (!Files.exists(localRepoPath)) {
                Files.createDirectories(localRepoPath);
                log.info("创建本地仓库目录: {}", effectiveLocalRepo);
            }

            int exitCode = executeMaven(basedir, effectiveLocalRepo, repositories);

            if (exitCode == 0) {
                Path targetPath = Path.of(basedir, "target", "classes");
                String targetVersion = detectTargetVersion(basedir);

                log.info("编译成功，耗时{}ms", Duration.between(start, Instant.now()).toMillis());
                if (log.isDebugEnabled()) {
                    List<Path> classFiles = findCompiledClasses(basedir);
                    log.debug("共编译类文件{}个", classFiles.size());
                }
                return CompileResult.success(targetPath, targetVersion);
            } else {
                log.error("Maven编译失败，退出码: {}", exitCode);
                return CompileResult.failure("Maven编译失败，退出码: " + exitCode);
            }
        } catch (Exception e) {
            String msg = "Maven编译失败: " + e.getMessage();
            log.error(msg, e);
            return CompileResult.failure(msg);
        }
    }

    /**
     * 执行Maven编译
     */
    private static int executeMaven(String basedir, String localRepo, Collection<MavenRepo> repositories) throws Exception {
        String mvnCmd = findMvnCommand();
        log.debug("使用mvn命令: {}", mvnCmd);

        List<String> command = new ArrayList<>();
        command.add(mvnCmd);
        command.add("compile");
        command.add("-f");
        command.add(Path.of(basedir, "pom.xml").toString());
        command.add("-Dmaven.repo.local=" + localRepo);
        command.add("-B");

        String revision = extractRevision(basedir);
        if (revision != null) {
            command.add("-Drevision=" + revision);
            log.debug("添加revision参数: -Drevision={}", revision);
        }

        if (repositories != null && !repositories.isEmpty()) {
            String settingsFile = createSettingsFile(basedir, repositories);
            if (settingsFile != null) {
                command.add("-s");
                command.add(settingsFile);
            }
        }

        log.debug("Maven命令: {}", String.join(" ", command));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(new File(basedir));
        builder.redirectErrorStream(false);

        Process process = builder.start();

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        try (BufferedReader outReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
             BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = outReader.readLine()) != null) {
                stdout.append(line).append("\n");
            }
            while ((line = errReader.readLine()) != null) {
                stderr.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        String output = stdout.toString() + stderr.toString();

        if (log.isDebugEnabled() && !output.isBlank()) {
            log.debug("Maven输出: {}", output);
        }

        return exitCode;
    }

    /**
     * 查找mvn命令
     */
    private static String findMvnCommand() {
        // 1. 检查系统PATH
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                File mvn = new File(dir, "mvn.cmd");
                if (mvn.exists()) {
                    return mvn.getAbsolutePath();
                }
                File mvnSh = new File(dir, "mvn");
                if (mvnSh.exists()) {
                    return mvnSh.getAbsolutePath();
                }
            }
        }

        // 2. 检查项目自带的mvnw
        Path projectMvnw = Path.of(System.getProperty("user.dir"), "mvnw.cmd");
        if (Files.exists(projectMvnw)) {
            return projectMvnw.toString();
        }

        // 3. 默认使用mvn
        return "mvn";
    }

    /**
     * 创建settings.xml文件
     */
    private static String createSettingsFile(String basedir, Collection<MavenRepo> repositories) {
        try {
            Path projectPath = Path.of(basedir).toAbsolutePath();
            Path parentDir = projectPath.getParent();
            if (parentDir == null) {
                parentDir = projectPath;
            }
            Path settingsFile = parentDir.resolve("jsqlint-settings.xml");

            StringBuilder xml = new StringBuilder();
            xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            xml.append("<settings xmlns=\"http://maven.apache.org/SETTINGS/1.2.0\"\n");
            xml.append("          xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
            xml.append("          xsi:schemaLocation=\"http://maven.apache.org/SETTINGS/1.2.0 http://maven.apache.org/xsd/settings-1.2.0.xsd\">\n");
            xml.append("  <profiles>\n");
            xml.append("    <profile>\n");
            xml.append("      <id>jsqlint-multi-repo</id>\n");
            xml.append("      <repositories>\n");

            for (MavenRepo repo : repositories) {
                xml.append("        <repository>\n");
                xml.append("          <id>").append(repo.id()).append("</id>\n");
                xml.append("          <url>").append(repo.url()).append("</url>\n");
                xml.append("          <releases>\n");
                xml.append("            <enabled>").append(repo.release()).append("</enabled>\n");
                xml.append("          </releases>\n");
                xml.append("          <snapshots>\n");
                xml.append("            <enabled>").append(repo.snapshot()).append("</enabled>\n");
                xml.append("          </snapshots>\n");
                xml.append("        </repository>\n");
            }

            xml.append("      </repositories>\n");
            xml.append("      <pluginRepositories>\n");

            for (MavenRepo repo : repositories) {
                xml.append("        <pluginRepository>\n");
                xml.append("          <id>").append(repo.id()).append("</id>\n");
                xml.append("          <url>").append(repo.url()).append("</url>\n");
                xml.append("          <releases>\n");
                xml.append("            <enabled>").append(repo.release()).append("</enabled>\n");
                xml.append("          </releases>\n");
                xml.append("          <snapshots>\n");
                xml.append("            <enabled>").append(repo.snapshot()).append("</enabled>\n");
                xml.append("          </snapshots>\n");
                xml.append("        </pluginRepository>\n");
            }

            xml.append("      </pluginRepositories>\n");
            xml.append("    </profile>\n");
            xml.append("  </profiles>\n");
            xml.append("  <activeProfiles>\n");
            xml.append("    <activeProfile>jsqlint-multi-repo</activeProfile>\n");
            xml.append("  </activeProfiles>\n");
            xml.append("</settings>\n");

            Files.writeString(settingsFile, xml.toString());
            log.info("已创建Maven Settings配置文件: {}", settingsFile);

            return settingsFile.toString();
        } catch (Exception e) {
            log.warn("创建settings.xml失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从pom.xml提取revision属性的实际值
     */
    private static String extractRevision(String basedir) {
        Path pomPath = Path.of(basedir, "pom.xml");
        if (!Files.exists(pomPath)) {
            return null;
        }

        try {
            String content = Files.readString(pomPath);

            if (content.contains("<revision>")) {
                int start = content.indexOf("<revision>") + "<revision>".length();
                int end = content.indexOf("</revision>", start);
                if (start > 0 && end > start) {
                    String revision = content.substring(start, end).trim();
                    if (!revision.startsWith("${")) {
                        log.debug("从pom.xml提取到revision: {}", revision);
                        return revision;
                    }
                }
            }

            Path flattenedPom = Path.of(basedir, ".flattened-pom.xml");
            if (Files.exists(flattenedPom)) {
                String flattenedContent = Files.readString(flattenedPom);
                if (flattenedContent.contains("<version>")) {
                    int start = flattenedContent.indexOf("<version>") + "<version>".length();
                    int end = flattenedContent.indexOf("</version>", start);
                    if (start > 0 && end > start) {
                        String version = flattenedContent.substring(start, end).trim();
                        log.debug("从flattened pom提取到version: {}", version);
                        return version;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("提取revision失败: {}", e.getMessage());
        }

        return null;
    }

    /**
     * 查找编译后的class文件
     */
    private static List<Path> findCompiledClasses(String basedir) {
        Path outputDir = Path.of(basedir, "target", "classes");

        if (!Files.exists(outputDir)) {
            log.warn("编译输出目录不存在: {}", outputDir);
            return List.of();
        }

        List<Path> classFiles = new ArrayList<>();
        try (var stream = Files.walk(outputDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".class"))
                    .forEach(classFiles::add);
        } catch (java.io.IOException e) {
            log.error("扫描class文件失败: {}", e.getMessage());
        }

        return classFiles;
    }

    /**
     * 从pom.xml检测目标Java版本
     */
    private static String detectTargetVersion(String basedir) {
        Path flattenedPom = Path.of(basedir, ".flattened-pom.xml");
        Path pomPath = flattenedPom.toFile().exists() ? flattenedPom : Path.of(basedir, "pom.xml");
        if (!Files.exists(pomPath)) {
            return null;
        }

        try {
            String content = Files.readString(pomPath);

            if (content.contains("maven.compiler.target")) {
                int idx = content.indexOf("maven.compiler.target");
                int start = content.indexOf(">", idx) + 1;
                int end = content.indexOf("<", start);
                if (start > 0 && end > start) {
                    return content.substring(start, end).trim();
                }
            }

            if (content.contains("<java.version>")) {
                int start = content.indexOf("<java.version>") + "<java.version>".length();
                int end = content.indexOf("<", start);
                if (start > 0 && end > start) {
                    return content.substring(start, end).trim();
                }
            }
        } catch (java.io.IOException e) {
            log.warn("读取pom.xml失败: {}", e.getMessage());
        }

        return null;
    }

}
