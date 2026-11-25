package org.u80dc.jsqlint.commons.location;

import org.u80dc.jsqlint.commons.compiler.MavenRepositoryOptions;
import org.u80dc.jsqlint.core.spi.AnalysisInputOptions;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 分析输入位置选项。inputMode 指定输入处理方式；classpath 仅为额外分析依赖，
 * 不改变目标项目或普通源码的编译 classpath。
 * buildArguments 为 Maven/Gradle 的额外命令行参数。
 * gradleInstallation 为空时使用目标项目的 Wrapper，或 Tooling API 的默认发行版。
 * classOutputDirectory 为普通源码/ALWAYS 构建的可选 class 输出目录；为空时分配随机临时目录。
 * 多模块项目会在该目录下隔离各模块产物。相对路径以调用进程的工作目录为基准。
 * mavenRepositories 仅用于 Maven 的依赖与插件解析，默认使用官方中央仓库。
 * 可通过 AnalyzerConfig.withInputOptions 交给 Analyzer，再由输入解析器传入自动编译位置。
 * buildPolicy 默认 ALWAYS；REUSE 读取指定编译快照，AUTO 对记录的输入及产物做内容校验。
 * 项目缓存的目录、生命周期独立于单次输出；缓存/复用不会清理调用方的 classOutputDirectory。
 * compiledProjectManifest 是显式 REUSE 的产物描述；additionalBuildInputs 补充插件的隐式构建输入。
 *
 * @author u80dc
 */
public record InputLocationOptions(InputMode inputMode, List<Path> classpath,
                                   List<String> buildArguments, Path gradleInstallation,
                                   Path classOutputDirectory, MavenRepositoryOptions mavenRepositories,
                                   BuildPolicy buildPolicy, Path projectCacheDirectory,
                                   Path compiledProjectManifest, Duration cacheMaxAge,
                                   List<Path> additionalBuildInputs) implements AnalysisInputOptions {
    public InputLocationOptions {
        Objects.requireNonNull(inputMode, "inputMode");
        Objects.requireNonNull(mavenRepositories, "mavenRepositories");
        Objects.requireNonNull(buildPolicy, "buildPolicy");
        Objects.requireNonNull(cacheMaxAge, "cacheMaxAge");
        if (cacheMaxAge.isNegative()) throw new IllegalArgumentException("Cache max age must not be negative");
        cacheMaxAge.toMillis(); // 缓存时间戳以毫秒保存，提前拒绝溢出配置。
        projectCacheDirectory = absolute(projectCacheDirectory);
        compiledProjectManifest = absolute(compiledProjectManifest);
        additionalBuildInputs = List.copyOf(additionalBuildInputs).stream()
                .map(path -> path.toAbsolutePath().normalize()).distinct().toList();
        classpath = classpath.stream().map(path -> path.toAbsolutePath().normalize()).toList();
        buildArguments = List.copyOf(buildArguments);
        if (gradleInstallation != null) {
            gradleInstallation = gradleInstallation.toAbsolutePath().normalize();
        }
        if (classOutputDirectory != null) {
            classOutputDirectory = classOutputDirectory.toAbsolutePath().normalize();
        }
    }

    /** 兼容原完整构造器，项目仍每次构建。 */
    public InputLocationOptions(InputMode inputMode, List<Path> classpath, List<String> buildArguments,
                                Path gradleInstallation, Path classOutputDirectory,
                                MavenRepositoryOptions mavenRepositories) {
        this(inputMode, classpath, buildArguments, gradleInstallation, classOutputDirectory, mavenRepositories,
                BuildPolicy.ALWAYS, null, null, Duration.ofHours(24), List.of());
    }

    /** 保留原有构造方式，未指定输出目录。 */
    public InputLocationOptions(InputMode inputMode, List<Path> classpath,
                                List<String> buildArguments, Path gradleInstallation) {
        this(inputMode, classpath, buildArguments, gradleInstallation, null);
    }

    /** 保留指定输出目录的构造方式，使用默认 Maven 仓库配置。 */
    public InputLocationOptions(InputMode inputMode, List<Path> classpath,
                                List<String> buildArguments, Path gradleInstallation, Path classOutputDirectory) {
        this(inputMode, classpath, buildArguments, gradleInstallation, classOutputDirectory, MavenRepositoryOptions.defaults());
    }

    /** 返回使用指定输出目录的新选项；传入 null 恢复默认输出方式。 */
    public InputLocationOptions withClassOutputDirectory(Path directory) {
        return copy(inputMode, classpath, buildArguments, gradleInstallation, directory, mavenRepositories,
                buildPolicy, projectCacheDirectory, compiledProjectManifest, cacheMaxAge, additionalBuildInputs);
    }

    public InputLocationOptions withMavenRepositories(MavenRepositoryOptions repositories) {
        return copy(inputMode, classpath, buildArguments, gradleInstallation, classOutputDirectory, repositories,
                buildPolicy, projectCacheDirectory, compiledProjectManifest, cacheMaxAge, additionalBuildInputs);
    }

    public InputLocationOptions withInputMode(InputMode mode) {
        return copy(mode, classpath, buildArguments, gradleInstallation, classOutputDirectory, mavenRepositories,
                buildPolicy, projectCacheDirectory, compiledProjectManifest, cacheMaxAge, additionalBuildInputs);
    }

    public InputLocationOptions withBuildArguments(List<String> arguments) {
        return copy(inputMode, classpath, arguments, gradleInstallation, classOutputDirectory, mavenRepositories,
                buildPolicy, projectCacheDirectory, compiledProjectManifest, cacheMaxAge, additionalBuildInputs);
    }

    public InputLocationOptions withBuildPolicy(BuildPolicy policy) {
        return copy(inputMode, classpath, buildArguments, gradleInstallation, classOutputDirectory, mavenRepositories,
                policy, projectCacheDirectory, compiledProjectManifest, cacheMaxAge, additionalBuildInputs);
    }

    /** 缓存根目录，null 使用 java.io.tmpdir/.jsqlint-project-cache，与单次编译输出分开。 */
    public InputLocationOptions withProjectCacheDirectory(Path directory) {
        return copy(inputMode, classpath, buildArguments, gradleInstallation, classOutputDirectory, mavenRepositories,
                buildPolicy, directory, compiledProjectManifest, cacheMaxAge, additionalBuildInputs);
    }

    /** 指定外部产物清单时同时选择 REUSE；null 恢复读取本项目的缓存清单。 */
    public InputLocationOptions withCompiledProjectManifest(Path manifest) {
        return copy(inputMode, classpath, buildArguments, gradleInstallation, classOutputDirectory, mavenRepositories,
                manifest == null ? buildPolicy : BuildPolicy.REUSE, projectCacheDirectory, manifest,
                cacheMaxAge, additionalBuildInputs);
    }

    /** AUTO 最长复用时间；零不按时间过期。REUSE 读取指定快照不受此期限限制。 */
    public InputLocationOptions withCacheMaxAge(Duration age) {
        return copy(inputMode, classpath, buildArguments, gradleInstallation, classOutputDirectory, mavenRepositories,
                buildPolicy, projectCacheDirectory, compiledProjectManifest, age, additionalBuildInputs);
    }

    /** 补充构建脚本、插件读取的项目外文件或目录；AUTO 使用内容摘要校验。 */
    public InputLocationOptions withAdditionalBuildInputs(List<Path> inputs) {
        return copy(inputMode, classpath, buildArguments, gradleInstallation, classOutputDirectory, mavenRepositories,
                buildPolicy, projectCacheDirectory, compiledProjectManifest, cacheMaxAge, inputs);
    }

    private static Path absolute(Path path) { return path == null ? null : path.toAbsolutePath().normalize(); }

    private static InputLocationOptions copy(InputMode mode, List<Path> classpath, List<String> arguments,
            Path gradle, Path output, MavenRepositoryOptions repositories, BuildPolicy policy, Path cache,
            Path manifest, Duration age, List<Path> inputs) {
        return new InputLocationOptions(mode, classpath, arguments, gradle, output, repositories,
                policy, cache, manifest, age, inputs);
    }

    public static InputLocationOptions defaults() {
        return new InputLocationOptions(InputMode.AUTO, List.of(), List.of(), null);
    }
}
