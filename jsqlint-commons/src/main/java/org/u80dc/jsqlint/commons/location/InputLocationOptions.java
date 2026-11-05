package org.u80dc.jsqlint.commons.location;

import org.u80dc.jsqlint.commons.compiler.MavenRepositoryOptions;
import org.u80dc.jsqlint.core.spi.AnalysisInputOptions;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 分析输入位置选项。inputMode 指定输入处理方式；classpath 仅为额外分析依赖，
 * 不改变目标项目或普通源码的编译 classpath。
 * buildArguments 为 Maven/Gradle 的额外命令行参数。
 * gradleInstallation 为空时使用目标项目的 Wrapper，或 Tooling API 的默认发行版。
 * classOutputDirectory 为可选的 class 输出目录；为空时，自动编译输入在系统临时目录下分配随机子目录。
 * 多模块项目会在该目录下隔离各模块产物。相对路径以调用进程的工作目录为基准。
 * mavenRepositories 仅用于 Maven 的依赖与插件解析，默认使用官方中央仓库。
 * 可通过 AnalyzerConfig.withInputOptions 交给 Analyzer，再由输入解析器传入自动编译位置。
 *
 * @author u80dc
 */
public record InputLocationOptions(InputMode inputMode, List<Path> classpath,
                                   List<String> buildArguments, Path gradleInstallation,
                                   Path classOutputDirectory, MavenRepositoryOptions mavenRepositories) implements AnalysisInputOptions {
    public InputLocationOptions {
        Objects.requireNonNull(inputMode, "inputMode");
        Objects.requireNonNull(mavenRepositories, "mavenRepositories");
        classpath = classpath.stream().map(path -> path.toAbsolutePath().normalize()).toList();
        buildArguments = List.copyOf(buildArguments);
        if (gradleInstallation != null) {
            gradleInstallation = gradleInstallation.toAbsolutePath().normalize();
        }
        if (classOutputDirectory != null) {
            classOutputDirectory = classOutputDirectory.toAbsolutePath().normalize();
        }
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
        return new InputLocationOptions(inputMode, classpath, buildArguments, gradleInstallation, directory, mavenRepositories);
    }

    public InputLocationOptions withMavenRepositories(MavenRepositoryOptions repositories) {
        return new InputLocationOptions(inputMode, classpath, buildArguments, gradleInstallation, classOutputDirectory, repositories);
    }

    public static InputLocationOptions defaults() {
        return new InputLocationOptions(InputMode.AUTO, List.of(), List.of(), null);
    }
}
