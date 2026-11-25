package org.u80dc.jsqlint.commons.compiler;

import org.u80dc.jsqlint.commons.location.AnalysisInputException;
import org.u80dc.jsqlint.commons.location.InputLocationOptions;
import org.u80dc.jsqlint.commons.location.InputMode;
import org.u80dc.jsqlint.commons.location.BuildPolicy;
import org.u80dc.jsqlint.core.spi.AnalysisInputContext;
import org.u80dc.jsqlint.core.statistics.StatisticsRecorder;

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
        return compile(directory, options, StatisticsRecorder.none());
    }

    public static CompiledProject compile(Path directory, InputLocationOptions options, StatisticsRecorder statisticsRecorder) {
        return compile(directory, options, new AnalysisInputContext(() -> {}, statisticsRecorder));
    }

    /** 项目缓存读取/构建也沿用请求检查点；第三方实际构建仍在返回后协作停止。 */
    public static CompiledProject compile(Path directory, InputLocationOptions options, AnalysisInputContext context) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(context, "context");
        Path root = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Project directory does not exist: " + root);
        }
        for (Path dependency : options.classpath()) {
            if (!Files.exists(dependency)) {
                throw new IllegalArgumentException("Classpath entry does not exist: " + dependency);
            }
        }
        if (options.compiledProjectManifest() != null) {
            if (options.buildPolicy() != BuildPolicy.REUSE)
                throw new IllegalArgumentException("A compiled project manifest requires REUSE policy");
            return ProjectCache.readExplicit(root, options, context);
        }
        InputMode mode = options.inputMode() == InputMode.AUTO ? detect(root) : options.inputMode();
        InputLocationOptions effective = options.withInputMode(mode);
        try {
            if (mode != InputMode.MAVEN_PROJECT && mode != InputMode.GRADLE_PROJECT)
                throw new IllegalArgumentException("Only Maven/Gradle projects require a custom compiler: " + root);
            context.checkpoint().run();
            return effective.buildPolicy() == BuildPolicy.ALWAYS
                    ? compileFresh(root, effective, context.statisticsRecorder())
                    : ProjectCache.resolve(root, effective, context, ProjectCompiler::compileFresh, System::currentTimeMillis);
        } catch (IOException e) {
            throw new AnalysisInputException(mode + " compilation failed for " + root, e);
        }
    }

    private static CompiledProject compileFresh(Path root, InputLocationOptions options,
                                                StatisticsRecorder statisticsRecorder) throws IOException {
        return switch (options.inputMode()) {
            case MAVEN_PROJECT -> MavenProjectCompiler.compileFresh(root, options, statisticsRecorder);
            case GRADLE_PROJECT -> GradleProjectCompiler.compileFresh(root, options, statisticsRecorder);
            case JAVA_SOURCE, AUTO -> throw new IllegalArgumentException("Expected a project build mode");
        };
    }

    /** 后端在任务开始前捕获此回调；缓存之外的普通构建没有额外输入扫描。 */
    static ProjectBuildObserver buildObserver() { return ProjectCache.currentObserver(); }

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
