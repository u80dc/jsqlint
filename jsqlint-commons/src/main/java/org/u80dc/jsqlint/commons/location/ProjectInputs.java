package org.u80dc.jsqlint.commons.location;

import org.u80dc.jsqlint.commons.compiler.CompiledProject;
import org.u80dc.jsqlint.core.resource.PathResourceSource;
import org.u80dc.jsqlint.core.spi.PreparedInput;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.DefaultRuntimeAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.jar.JarFile;

/** 只读打开已有项目产物；调用方关闭返回的输入，不编译、不删除产物或依赖。 */
public final class ProjectInputs {
    private ProjectInputs() {}

    /** 自有模块作为 Application，第三方依赖作为 Library，并加入当前 JDK 运行时。 */
    public static PreparedInput open(CompiledProject project) {
        return open(project, true);
    }

    /** Path 入口由 Analyzer 添加运行时，避免同一次请求重复注册。 */
    static PreparedInput open(CompiledProject project, boolean includeRuntime) {
        Objects.requireNonNull(project, "project");
        var opened = new ArrayList<AnalysisInputLocation>();
        try {
            var applications = new LinkedHashSet<Path>();
            for (Path value : project.classDirectories()) {
                Path path = value.toAbsolutePath().normalize();
                if (classpath(path, true)) applications.add(path);
            }
            if (applications.isEmpty())
                throw new AnalysisInputException("Compiled project has no application class files: " + project.directory());
            opened.add(location(List.copyOf(applications), SourceType.Application));

            var libraries = new LinkedHashSet<Path>();
            for (Path value : project.dependencies()) {
                Path path = value.toAbsolutePath().normalize();
                if (applications.contains(path)) continue;
                classpath(path, false);
                libraries.add(path);
            }
            if (!libraries.isEmpty()) opened.add(location(List.copyOf(libraries), SourceType.Library));

            var roots = new LinkedHashSet<Path>();
            for (Path value : project.resourceRoots()) {
                Path path = value.toAbsolutePath().normalize();
                if (!Files.isDirectory(path) && !Files.isRegularFile(path))
                    throw new AnalysisInputException("Missing compiled project resource: " + path);
                if (Files.isRegularFile(path) && jar(path)) {
                    try (var archive = new JarFile(path.toFile())) { /* 校验资源归档可读，枚举由资源来源负责。 */ }
                }
                roots.add(path);
            }
            if (includeRuntime) opened.add(new DefaultRuntimeAnalysisInputLocation());
            return new PreparedInput(opened, roots.stream().map(PathResourceSource::new).toList());
        } catch (IOException | RuntimeException | Error failure) {
            try { new PreparedInput(opened, List.of()).close(); }
            catch (Exception close) { failure.addSuppressed(close); }
            if (failure instanceof Error error) throw error;
            if (failure instanceof AnalysisInputException input) throw input;
            throw new AnalysisInputException("Cannot open compiled project inputs for " + project.directory()
                    + ": " + failure.getMessage(), failure);
        }
    }

    private static AnalysisInputLocation location(List<Path> paths, SourceType type) {
        return new JavaClassPathAnalysisInputLocation(String.join(File.pathSeparator,
                paths.stream().map(Path::toString).toList()), type);
    }

    /** 返回是否具有应用 class；资源-only 根不会被加入 Application 路径。 */
    private static boolean classpath(Path path, boolean application) throws IOException {
        if (Files.isDirectory(path)) {
            if (Files.isDirectory(path.resolve("BOOT-INF/classes")) || Files.isDirectory(path.resolve("BOOT-INF/lib")))
                throw boot(path);
            if (!application) return true;
            try (var files = Files.walk(path)) {
                return files.anyMatch(file -> Files.isRegularFile(file) && file.toString().endsWith(".class")
                        && !file.getFileName().toString().equals("module-info.class"));
            }
        }
        if (!Files.isRegularFile(path)) throw new AnalysisInputException("Missing compiled project classpath: " + path);
        if (!jar(path)) throw new AnalysisInputException("Unsupported compiled project classpath; expected a directory or ordinary JAR: " + path);
        boolean classes = false;
        try (var archive = new JarFile(path.toFile())) {
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("BOOT-INF/classes/") || name.startsWith("BOOT-INF/lib/")) throw boot(path);
                if (!entry.isDirectory() && name.endsWith(".class") && !name.startsWith("META-INF/")
                        && !name.equals("module-info.class")) classes = true;
            }
        }
        return !application || classes;
    }

    private static boolean jar(Path path) { return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"); }
    private static AnalysisInputException boot(Path path) {
        return new AnalysisInputException("Spring Boot executable classpath is not supported: " + path
                + "; use BOOT-INF/classes and separately extracted library JARs");
    }
}
