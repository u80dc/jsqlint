package org.u80dc.jsqlint.commons.compiler;

import org.u80dc.jsqlint.commons.location.AnalysisInputException;
import org.u80dc.jsqlint.commons.location.InputMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Maven/Gradle 后端共用的编译产物和依赖；产物由调用方或目标项目管理。
 *
 * @author u80dc
 */
public record CompiledProject(Path directory, InputMode inputMode, List<Path> classDirectories,
                              List<Path> dependencies, List<Path> resourceRoots) {
    public CompiledProject {
        classDirectories = List.copyOf(classDirectories);
        dependencies = List.copyOf(dependencies);
        resourceRoots = List.copyOf(resourceRoots);
    }

    public CompiledProject(Path directory, InputMode inputMode, List<Path> classDirectories, List<Path> dependencies) {
        this(directory, inputMode, classDirectories, dependencies, classDirectories);
    }

    /** 统一校验构建产物、规范化路径并排除重复依赖，供各编译后端共用。 */
    public static CompiledProject fromOutputs(Path root, InputMode mode, Collection<Path> outputs,
                                              Collection<Path> dependencies) throws IOException {
        return fromOutputs(root, mode, outputs, dependencies, outputs);
    }

    public static CompiledProject fromOutputs(Path root, InputMode mode, Collection<Path> outputs,
                                              Collection<Path> dependencies, Collection<Path> resourceRoots) throws IOException {
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
        List<Path> resources = resourceRoots.stream().map(path -> path.toAbsolutePath().normalize())
                .filter(path -> Files.isDirectory(path) || Files.isRegularFile(path)).distinct().toList();
        return new CompiledProject(root, mode, classDirectories.stream().distinct().toList(), libraries, resources);
    }
}
