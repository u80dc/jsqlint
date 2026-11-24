package org.u80dc.jsqlint.commons.location;

import lombok.Getter;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.u80dc.jsqlint.commons.compiler.CompiledProject;
import org.u80dc.jsqlint.commons.compiler.JavaSourceCompiler;
import org.u80dc.jsqlint.commons.compiler.JavaSourceFiles;
import org.u80dc.jsqlint.commons.compiler.ProjectCompiler;
import org.u80dc.jsqlint.core.statistics.StatisticsRecorder;
import sootup.core.frontend.SootClassSource;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.interceptor.BodyInterceptor;
import sootup.core.model.SourceType;
import sootup.core.types.ClassType;
import sootup.core.views.View;
import sootup.interceptors.BytecodeBodyInterceptors;
import sootup.java.bytecode.frontend.inputlocation.DefaultRuntimeAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.views.JavaView;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 统一接收字节码、普通 Java 源码或 Maven/Gradle 项目的 SootUp 输入。
 *
 * <p>构造时完成必要的编译；读取方法体时由 SootUp 惰性转换为 Jimple。
 * 自动识别优先级为项目构建文件、Java 源码、字节码。多路径输入可以是多个源码文件/目录，
 * 或多个字节码目录/JAR；Maven/Gradle 项目应单独传入根目录。
 *
 * <p>目标代码默认为 Application，构建工具解析的依赖及额外 classpath 为 Library。
 * {@code new JavaView(input)} 即可解析目标代码及这些依赖；{@link #createView()} 还会加入当前 JDK。
 *
 * <p>编译输出目录由 {@link InputLocationOptions#classOutputDirectory()} 指定；未指定时，
 * 在 {@code java.io.tmpdir} 下为本次编译分配随机子目录。
 * 普通源码使用 JDK 编译器写入该目录，沿用 OTF 的当前 JVM 编译 classpath 和默认参数；
 * Maven/Gradle 调整本次构建的输出配置，多模块产物分目录保存。
 * {@link #close()} 仅转发上游关闭操作，不删除临时产物、调用方字节码或项目构建产物。
 *
 * @author u80dc
 */
public final class AutoCompileAnalysisInputLocation implements AnalysisInputLocation {

    @Getter
    private final List<Path> inputs;

    @Getter
    private final InputType inputType;

    /**
     * 返回已自动接入为 Library 的分析依赖路径。
     */
    @Getter
    private final List<Path> dependencies;

    /** 实际使用的编译输出根目录；已有字节码输入不执行编译，返回 null。 */
    @Getter
    private final @Nullable Path classOutputDirectory;

    private final List<AnalysisInputLocation> locations;

    public AutoCompileAnalysisInputLocation(Path input) {
        this(List.of(input));
    }

    public AutoCompileAnalysisInputLocation(Path input, InputLocationOptions options) {
        this(List.of(input), options);
    }

    public AutoCompileAnalysisInputLocation(Path input, InputLocationOptions options, StatisticsRecorder statisticsRecorder) {
        this(List.of(input), options, statisticsRecorder);
    }

    public AutoCompileAnalysisInputLocation(List<Path> inputs) {
        this(inputs, InputLocationOptions.defaults());
    }

    public AutoCompileAnalysisInputLocation(List<Path> inputs, InputLocationOptions options) {
        this(inputs, options, SourceType.Application, BytecodeBodyInterceptors.Default.getBodyInterceptors());
    }

    public AutoCompileAnalysisInputLocation(List<Path> inputs, InputLocationOptions options, StatisticsRecorder statisticsRecorder) {
        this(inputs, options, SourceType.Application, BytecodeBodyInterceptors.Default.getBodyInterceptors(), statisticsRecorder);
    }

    public AutoCompileAnalysisInputLocation(Path input, InputLocationOptions options,
                                            SourceType sourceType, List<BodyInterceptor> bodyInterceptors) {
        this(List.of(input), options, sourceType, bodyInterceptors);
    }

    public AutoCompileAnalysisInputLocation(Path input, InputLocationOptions options,
                                            SourceType sourceType, List<BodyInterceptor> bodyInterceptors,
                                            StatisticsRecorder statisticsRecorder) {
        this(List.of(input), options, sourceType, bodyInterceptors, statisticsRecorder);
    }

    public AutoCompileAnalysisInputLocation(List<Path> inputs, InputLocationOptions options,
                                            SourceType sourceType, List<BodyInterceptor> bodyInterceptors) {
        this(inputs, options, sourceType, bodyInterceptors, StatisticsRecorder.none());
    }

    public AutoCompileAnalysisInputLocation(List<Path> inputs, InputLocationOptions options,
                                            SourceType sourceType, List<BodyInterceptor> bodyInterceptors,
                                            StatisticsRecorder statisticsRecorder) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(statisticsRecorder, "statisticsRecorder");
        List<BodyInterceptor> interceptors = List.copyOf(bodyInterceptors);
        this.inputs = inputs.stream().map(path -> path.toAbsolutePath().normalize()).distinct().toList();
        if (this.inputs.isEmpty()) throw new IllegalArgumentException("At least one input path is required");
        options.classpath().forEach(AutoCompileAnalysisInputLocation::validateClasspathEntry);

        try {
            List<ResolvedInput> resolved = new ArrayList<>();
            for (Path path : this.inputs) resolved.add(resolve(path, options.inputMode()));
            inputType = resolved.getFirst().type();
            if (resolved.stream().anyMatch(input -> input.type() != inputType)
                    || (isProject(inputType) && resolved.size() != 1)) {
                throw new IllegalArgumentException("Use one Maven/Gradle project root, only Java sources, or only bytecode paths per input location");
            }
            if (!isProject(inputType) && (!options.buildArguments().isEmpty() || options.gradleInstallation() != null)) {
                throw new IllegalArgumentException("Build arguments and Gradle installation apply only to Maven/Gradle projects");
            }
            if (inputType == InputType.MAVEN_PROJECT && options.gradleInstallation() != null) {
                throw new IllegalArgumentException("Gradle installation does not apply to a Maven project");
            }
            Path output = options.classOutputDirectory();
            if (inputType != InputType.BYTECODE && output == null) {
                output = createTemporaryOutputDirectory();
            }
            classOutputDirectory = output;
            if (output != null) {
                if (inputType == InputType.BYTECODE) {
                    throw new IllegalArgumentException("Class output directory applies only to source compilation");
                }
                prepareOutputDirectory(output, this.inputs);
            }

            List<Path> paths = resolved.stream().flatMap(input -> input.paths().stream()).distinct().toList();
            List<Path> libraries = options.classpath();
            if (isProject(inputType)) {
                CompiledProject compiled = ProjectCompiler.compile(this.inputs.getFirst(), options.withClassOutputDirectory(output), statisticsRecorder);
                paths = compiled.classDirectories();
                libraries = compiled.dependencies();
            } else if (inputType == InputType.JAVA_SOURCE) {
                JavaSourceCompiler.compile(paths, output, statisticsRecorder);
                paths = List.of(output);
            }
            List<Path> applicationPaths = paths;
            dependencies = libraries.stream().filter(path -> !applicationPaths.contains(path)).distinct().toList();
            AnalysisInputLocation library = dependencies.isEmpty() ? null
                    : bytecode(dependencies, SourceType.Library, interceptors);
            AnalysisInputLocation application = bytecode(paths, sourceType, interceptors);
            locations = library == null ? List.of(application) : List.of(application, library);
        } catch (IOException e) {
            throw new AnalysisInputException("Cannot inspect input paths: " + this.inputs, e);
        }
    }

    private static Path createTemporaryOutputDirectory() throws IOException {
        Path root = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        Files.createDirectories(root);
        // 显式使用 java.io.tmpdir，且不加固定目录层；每次构造独占一个随机目录。
        return Files.createTempDirectory(root, null);
    }

    private static void prepareOutputDirectory(Path output, List<Path> inputs) throws IOException {
        if (Files.exists(output) && !Files.isDirectory(output)) {
            throw new IllegalArgumentException("Class output path must be a directory: " + output);
        }
        // 允许项目内的输出子目录，但不能把项目根目录或源码所在目录直接作为输出根目录。
        for (Path input : inputs) {
            if (input.startsWith(output)) {
                throw new IllegalArgumentException("Class output directory must not contain an input path: " + output);
            }
        }
        Files.createDirectories(output);
        Path realOutput = output.toRealPath();
        for (Path input : inputs) {
            if (input.toRealPath().startsWith(realOutput)) {
                throw new IllegalArgumentException("Class output directory must not contain an input path: " + output);
            }
        }
    }

    private static AnalysisInputLocation bytecode(List<Path> paths, SourceType sourceType,
                                                  List<BodyInterceptor> interceptors) {
        paths.forEach(AutoCompileAnalysisInputLocation::validateClasspathEntry);
        return new JavaClassPathAnalysisInputLocation(paths.stream().map(Path::toString)
                .collect(Collectors.joining(File.pathSeparator)), sourceType, interceptors);
    }

    private static void validateClasspathEntry(Path path) {
        if (!Files.isDirectory(path) && !(Files.isRegularFile(path) && isJar(path))) {
            throw new IllegalArgumentException("Classpath entry must be an existing class directory or JAR: " + path);
        }
        if (path.toString().contains(File.pathSeparator)) {
            throw new IllegalArgumentException("Classpath entry contains the platform classpath separator: " + path);
        }
    }

    private static ResolvedInput resolve(Path path, InputMode mode) throws IOException {
        if (!Files.exists(path)) throw new IllegalArgumentException("Input path does not exist: " + path);
        if (Files.isDirectory(path)) {
            InputMode detected = mode == InputMode.AUTO ? ProjectCompiler.detect(path) : mode;
            if (detected == InputMode.MAVEN_PROJECT || detected == InputMode.GRADLE_PROJECT) {
                return new ResolvedInput(detected == InputMode.MAVEN_PROJECT ? InputType.MAVEN_PROJECT : InputType.GRADLE_PROJECT, List.of(path));
            }
            List<Path> sources = JavaSourceFiles.collect(path);
            if (!sources.isEmpty()) return new ResolvedInput(InputType.JAVA_SOURCE, sources);
            if (mode != InputMode.JAVA_SOURCE) {
                try (var files = Files.walk(path)) {
                    if (files.anyMatch(file -> Files.isRegularFile(file) && file.toString().endsWith(".class"))) {
                        return new ResolvedInput(InputType.BYTECODE, List.of(path));
                    }
                }
            }
            throw new IllegalArgumentException("No Java sources or class files found; pass a source root or classpath root: " + path);
        }
        if (mode == InputMode.MAVEN_PROJECT || mode == InputMode.GRADLE_PROJECT) {
            throw new IllegalArgumentException("Maven/Gradle input must be a project directory: " + path);
        }
        if (Files.isRegularFile(path)) {
            if (path.toString().endsWith(".java")) return new ResolvedInput(InputType.JAVA_SOURCE, List.of(path));
            if (mode != InputMode.JAVA_SOURCE && isJar(path))
                return new ResolvedInput(InputType.BYTECODE, List.of(path));
        }
        throw new IllegalArgumentException("Unsupported input; use .java files, source directories, classpath roots or JARs: " + path);
    }

    private static boolean isJar(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private static boolean isProject(InputType type) {
        return type == InputType.MAVEN_PROJECT || type == InputType.GRADLE_PROJECT;
    }

    /**
     * 创建包含当前 JDK 运行时的视图；不重复编译。
     */
    public JavaView createView() {
        return new JavaView(List.of(this, new DefaultRuntimeAnalysisInputLocation()));
    }

    /** 向解析器转交底层输入，保留独立的应用/库分类，避免应用扫描先解析全部库类。 */
    List<AnalysisInputLocation> locations() { return locations; }

    @Override
    public @NonNull Optional<? extends SootClassSource> getClassSource(@NonNull ClassType type, @NonNull View view) {
        for (AnalysisInputLocation location : locations) {
            Optional<? extends SootClassSource> source = location.getClassSource(type, view);
            if (source.isPresent()) return source;
        }
        return Optional.empty();
    }

    @Override
    public @NonNull Stream<? extends SootClassSource> getClassSources(@NonNull View view) {
        // Keep each source's original location so library classification and interceptors are preserved.
        return locations.stream().flatMap(location -> location.getClassSources(view));
    }

    @Override
    public @NonNull SourceType getSourceType() {
        return locations.getFirst().getSourceType();
    }

    @Override
    public @NonNull List<BodyInterceptor> getBodyInterceptors() {
        return locations.getFirst().getBodyInterceptors();
    }

    @Override
    public void close() throws Exception {
        Exception failure = null;
        for (AnalysisInputLocation location : locations) {
            try {
                location.close();
            } catch (Exception e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
    }

    /**
     * 实际识别的输入类型，不包含调用方的 AUTO 选择。
     */
    public enum InputType {BYTECODE, JAVA_SOURCE, MAVEN_PROJECT, GRADLE_PROJECT}

    private record ResolvedInput(InputType type, List<Path> paths) {
    }
}
