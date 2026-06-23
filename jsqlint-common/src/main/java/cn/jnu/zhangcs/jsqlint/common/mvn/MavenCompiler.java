package cn.jnu.zhangcs.jsqlint.common.mvn;

import lombok.extern.slf4j.Slf4j;
import org.apache.maven.cli.MavenCli;
import org.jspecify.annotations.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Maven编译工具类。
 *
 * <p>通过 Maven Embedder 在当前 JVM 内执行 Maven，不依赖外部 mvn/mvnw 命令。
 * 该类保持与 {@link MavenHelper} 相同的调用形态，便于调用方逐步迁移。</p>
 *
 * @author zhangcs
 */
@Slf4j
public class MavenCompiler {

    private static final String DEFAULT_LOCAL_REPO = System.getProperty("java.io.tmpdir")
            + File.separator + ".jsqlint" + File.separator + "repo";

    private static final Object MAVEN_CLI_LOCK = new Object();

    private MavenCompiler() {
    }

    /**
     * 编译项目。
     *
     * @param basedir      项目路径，该路径下有pom.xml
     * @param localRepo    本地仓库地址，默认将依赖下载到临时目录下的 .jsqlint/repo
     * @param repositories 远程仓库，如果pom.xml中没有指定仓库信息，则从该远程仓库中下载
     * @return 编译结果
     */
    @NonNull
    public static CompileResult compile(@NonNull String basedir, String localRepo, Collection<MavenRepo> repositories) {
        log.info("开始使用 Maven Embedder 编译项目: {}", basedir);

        Path projectDir = Path.of(basedir).toAbsolutePath().normalize();
        Path pomPath = projectDir.resolve("pom.xml");
        if (!Files.isRegularFile(pomPath)) {
            return CompileResult.failure("pom.xml不存在: " + pomPath);
        }

        String effectiveLocalRepo = localRepo;
        if (effectiveLocalRepo == null || effectiveLocalRepo.isBlank()) {
            effectiveLocalRepo = DEFAULT_LOCAL_REPO;
        }
        Path localRepoPath = Path.of(effectiveLocalRepo).toAbsolutePath().normalize();
        log.info("使用本地仓库: {}", localRepoPath);

        Instant start = Instant.now();
        Path settingsFile = null;
        try {
            Files.createDirectories(localRepoPath);

            if (repositories != null && !repositories.isEmpty()) {
                settingsFile = createSettingsFile(localRepoPath, repositories);
            }

            int exitCode = executeEmbeddedMaven(projectDir, pomPath, localRepoPath, settingsFile);
            if (exitCode == 0) {
                Path targetPath = projectDir.resolve("target").resolve("classes");
                String targetVersion = detectTargetVersion(projectDir);

                log.info("Maven Embedder 编译成功，耗时{}ms", Duration.between(start, Instant.now()).toMillis());
                if (log.isDebugEnabled()) {
                    List<Path> classFiles = findCompiledClasses(targetPath);
                    log.debug("共编译类文件{}个", classFiles.size());
                }
                return CompileResult.success(targetPath, targetVersion);
            }

            log.error("Maven Embedder 编译失败，退出码: {}", exitCode);
            return CompileResult.failure("Maven Embedder 编译失败，退出码: " + exitCode);
        } catch (Exception e) {
            String msg = "Maven Embedder 编译失败: " + e.getMessage();
            log.error(msg, e);
            return CompileResult.failure(msg);
        } finally {
            if (settingsFile != null) {
                try {
                    Files.deleteIfExists(settingsFile);
                } catch (Exception e) {
                    log.debug("删除临时settings.xml失败: {}", settingsFile, e);
                }
            }
        }
    }

    private static int executeEmbeddedMaven(Path projectDir, Path pomPath, Path localRepoPath, Path settingsFile) throws Exception {
        List<String> args = new ArrayList<>();
        args.add("compile");
        args.add("-f");
        args.add(pomPath.toString());
        args.add("-Dmaven.repo.local=" + localRepoPath);
        args.add("-B");
        args.add("-ntp");

        String revision = extractRevision(projectDir);
        if (revision != null) {
            args.add("-Drevision=" + revision);
            log.debug("添加revision参数: -Drevision={}", revision);
        }

        if (settingsFile != null) {
            args.add("-s");
            args.add(settingsFile.toString());
        }

        log.debug("Maven Embedder 参数: {}", String.join(" ", args));

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode;

        String previousMultiModuleDir = System.getProperty(MavenCli.MULTIMODULE_PROJECT_DIRECTORY);
        synchronized (MAVEN_CLI_LOCK) {
            try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
                 PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
                System.setProperty(MavenCli.MULTIMODULE_PROJECT_DIRECTORY, projectDir.toString());
                exitCode = new MavenCli().doMain(args.toArray(String[]::new), projectDir.toString(), out, err);
            } finally {
                if (previousMultiModuleDir == null) {
                    System.clearProperty(MavenCli.MULTIMODULE_PROJECT_DIRECTORY);
                } else {
                    System.setProperty(MavenCli.MULTIMODULE_PROJECT_DIRECTORY, previousMultiModuleDir);
                }
            }
        }

        String output = stdout.toString(StandardCharsets.UTF_8) + stderr.toString(StandardCharsets.UTF_8);
        if (log.isDebugEnabled() && !output.isBlank()) {
            log.warn("Maven Embedder 输出: {}", abbreviate(output, 16_000));
        }

        return exitCode;
    }

    private static Path createSettingsFile(Path localRepoPath, Collection<MavenRepo> repositories) throws Exception {
        Path settingsDir = localRepoPath.getParent() == null
                ? localRepoPath.resolve(".settings")
                : localRepoPath.getParent().resolve("settings");
        Files.createDirectories(settingsDir);
        Path settingsFile = Files.createTempFile(settingsDir, "jsqlint-", "-settings.xml");

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<settings xmlns=\"http://maven.apache.org/SETTINGS/1.2.0\"\n");
        xml.append("          xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        xml.append("          xsi:schemaLocation=\"http://maven.apache.org/SETTINGS/1.2.0 http://maven.apache.org/xsd/settings-1.2.0.xsd\">\n");
        xml.append("  <profiles>\n");
        xml.append("    <profile>\n");
        xml.append("      <id>jsqlint-multi-repo</id>\n");
        xml.append("      <repositories>\n");
        appendRepositories(xml, repositories, "repository");
        xml.append("      </repositories>\n");
        xml.append("      <pluginRepositories>\n");
        appendRepositories(xml, repositories, "pluginRepository");
        xml.append("      </pluginRepositories>\n");
        xml.append("    </profile>\n");
        xml.append("  </profiles>\n");
        xml.append("  <activeProfiles>\n");
        xml.append("    <activeProfile>jsqlint-multi-repo</activeProfile>\n");
        xml.append("  </activeProfiles>\n");
        xml.append("</settings>\n");

        Files.writeString(settingsFile, xml.toString(), StandardCharsets.UTF_8);
        log.info("已创建临时 Maven Settings 配置文件: {}", settingsFile);
        return settingsFile;
    }

    private static void appendRepositories(StringBuilder xml, Collection<MavenRepo> repositories, String elementName) {
        for (MavenRepo repo : repositories) {
            xml.append("        <").append(elementName).append(">\n");
            xml.append("          <id>").append(xmlEscape(repo.id())).append("</id>\n");
            xml.append("          <url>").append(xmlEscape(repo.url())).append("</url>\n");
            xml.append("          <releases>\n");
            xml.append("            <enabled>").append(repo.release()).append("</enabled>\n");
            xml.append("          </releases>\n");
            xml.append("          <snapshots>\n");
            xml.append("            <enabled>").append(repo.snapshot()).append("</enabled>\n");
            xml.append("          </snapshots>\n");
            xml.append("        </").append(elementName).append(">\n");
        }
    }

    private static String extractRevision(Path projectDir) {
        Path pomPath = projectDir.resolve("pom.xml");
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

            Path flattenedPom = projectDir.resolve(".flattened-pom.xml");
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

    private static List<Path> findCompiledClasses(Path outputDir) {
        if (!Files.exists(outputDir)) {
            log.warn("编译输出目录不存在: {}", outputDir);
            return List.of();
        }

        List<Path> classFiles = new ArrayList<>();
        try (var stream = Files.walk(outputDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".class"))
                    .forEach(classFiles::add);
        } catch (Exception e) {
            log.error("扫描class文件失败: {}", e.getMessage());
        }

        return classFiles;
    }

    private static String detectTargetVersion(Path projectDir) {
        Path flattenedPom = projectDir.resolve(".flattened-pom.xml");
        Path pomPath = flattenedPom.toFile().exists() ? flattenedPom : projectDir.resolve("pom.xml");
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
        } catch (Exception e) {
            log.warn("读取pom.xml失败: {}", e.getMessage());
        }

        return null;
    }

    private static String xmlEscape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String abbreviate(String value, @SuppressWarnings("SameParameterValue") int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "\n... output truncated, total chars=" + value.length();
    }

}
