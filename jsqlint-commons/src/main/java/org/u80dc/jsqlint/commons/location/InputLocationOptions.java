package org.u80dc.jsqlint.commons.location;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 分析输入位置选项。inputMode 指定输入处理方式；classpath 仅为额外分析依赖，
 * 不改变目标项目或 OTF 的编译 classpath。
 * buildArguments 为 Maven/Gradle 的额外命令行参数。
 * gradleInstallation 为空时使用目标项目的 Wrapper，或 Tooling API 的默认发行版。
 *
 * @author u80dc
 */
public record InputLocationOptions(InputMode inputMode, List<Path> classpath,
                                   List<String> buildArguments, Path gradleInstallation) {
    public InputLocationOptions {
        Objects.requireNonNull(inputMode, "inputMode");
        classpath = classpath.stream().map(path -> path.toAbsolutePath().normalize()).toList();
        buildArguments = List.copyOf(buildArguments);
        if (gradleInstallation != null) {
            gradleInstallation = gradleInstallation.toAbsolutePath().normalize();
        }
    }

    public static InputLocationOptions defaults() {
        return new InputLocationOptions(InputMode.AUTO, List.of(), List.of(), null);
    }
}
