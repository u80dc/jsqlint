package org.u80dc.jsqlint.commons.location;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Maven/Gradle 项目识别和编译分派。普通源码和字节码由 SootUp 原生入口处理。
 *
 * @author u80dc
 */
final class ProjectCompiler {
    private ProjectCompiler() {}

    static CompiledProject compile(Path directory, InputLocationOptions options) {
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

    static InputMode detect(Path root) {
        boolean maven = Files.isRegularFile(root.resolve("pom.xml"));
        boolean gradle = List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
                .stream().anyMatch(name -> Files.isRegularFile(root.resolve(name)));
        if (maven && gradle) {
            throw new IllegalArgumentException("Both Maven and Gradle build files exist; select an InputMode explicitly: " + root);
        }
        return maven ? InputMode.MAVEN_PROJECT : gradle ? InputMode.GRADLE_PROJECT : InputMode.JAVA_SOURCE;
    }

    static CompiledProject result(Path root, InputMode mode, Collection<Path> outputs,
                                  Collection<Path> dependencies) throws IOException {
        List<Path> classDirectories = new ArrayList<>();
        for (Path output : outputs) {
            Path path = output.toAbsolutePath().normalize();
            if (Files.isDirectory(path)) {
                try (var files = Files.walk(path)) {
                    if (files.anyMatch(file -> Files.isRegularFile(file) && file.toString().endsWith(".class"))) {
                        classDirectories.add(path);
                    }
                }
            }
        }
        if (classDirectories.isEmpty()) {
            throw new AnalysisInputException(mode + " produced no main class files: " + root);
        }
        List<Path> libraries = dependencies.stream().map(path -> path.toAbsolutePath().normalize())
                .filter(path -> !classDirectories.contains(path)).distinct().toList();
        for (Path library : libraries) {
            if (!Files.exists(library)) {
                throw new AnalysisInputException("Missing analysis dependency after " + mode + " compilation: " + library);
            }
        }
        return new CompiledProject(root, mode, classDirectories.stream().distinct().toList(), libraries);
    }
}
