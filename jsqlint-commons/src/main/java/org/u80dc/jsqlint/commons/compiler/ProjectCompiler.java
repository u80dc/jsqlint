package org.u80dc.jsqlint.commons.compiler;

import org.u80dc.jsqlint.commons.location.AnalysisInputException;
import org.u80dc.jsqlint.commons.location.InputLocationOptions;
import org.u80dc.jsqlint.commons.location.InputMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 协调 Maven/Gradle 项目识别和编译分派，具体构建操作由各后端负责。
 *
 * @author u80dc
 */
public final class ProjectCompiler {
    private ProjectCompiler() {}

    /** 根据输入选项选择项目编译后端，并统一处理构建失败。 */
    public static CompiledProject compile(Path directory, InputLocationOptions options) {
        Objects.requireNonNull(options, "options");
        Path root = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Project directory does not exist: " + root);
        }
        for (Path dependency : options.classpath()) {
            if (!Files.exists(dependency)) {
                throw new IllegalArgumentException("Classpath entry does not exist: " + dependency);
            }
        }
        InputMode mode = options.inputMode() == InputMode.AUTO ? detect(root) : options.inputMode();
        try {
            return switch (mode) {
                case MAVEN_PROJECT -> MavenProjectCompiler.compile(root, options);
                case GRADLE_PROJECT -> GradleProjectCompiler.compile(root, options);
                case JAVA_SOURCE, AUTO -> throw new IllegalArgumentException("Only Maven/Gradle projects require a custom compiler: " + root);
            };
        } catch (IOException e) {
            throw new AnalysisInputException(mode + " compilation failed for " + root, e);
        }
    }

    /** 识别项目构建方式；同时存在两种构建文件时要求调用方明确选择。 */
    public static InputMode detect(Path root) {
        boolean maven = MavenProjectCompiler.isProject(root);
        boolean gradle = GradleProjectCompiler.isProject(root);
        if (maven && gradle) {
            throw new IllegalArgumentException("Both Maven and Gradle build files exist; select an InputMode explicitly: " + root);
        }
        return maven ? InputMode.MAVEN_PROJECT : gradle ? InputMode.GRADLE_PROJECT : InputMode.JAVA_SOURCE;
    }
}
