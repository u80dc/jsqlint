package org.u80dc.jsqlint.commons.location;

import java.nio.file.Path;
import java.util.List;

/**
 * Maven/Gradle 编译产物和依赖；项目输出由项目自身拥有。
 *
 * @author u80dc
 */
record CompiledProject(Path directory, InputMode inputMode, List<Path> classDirectories,
                       List<Path> dependencies) {
    CompiledProject {
        classDirectories = List.copyOf(classDirectories);
        dependencies = List.copyOf(dependencies);
    }
}
