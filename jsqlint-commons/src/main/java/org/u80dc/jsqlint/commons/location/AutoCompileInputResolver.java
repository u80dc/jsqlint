package org.u80dc.jsqlint.commons.location;

import org.jspecify.annotations.Nullable;
import org.u80dc.jsqlint.commons.compiler.GradleProjectCompiler;
import org.u80dc.jsqlint.commons.compiler.JavaSourceFiles;
import org.u80dc.jsqlint.commons.compiler.MavenProjectCompiler;
import org.u80dc.jsqlint.commons.compiler.ProjectCompiler;
import org.u80dc.jsqlint.core.spi.AnalysisInputPlan;
import org.u80dc.jsqlint.core.resource.PathResourceSource;
import org.u80dc.jsqlint.core.spi.AnalysisInputContext;
import org.u80dc.jsqlint.core.spi.AnalysisInputOptions;
import org.u80dc.jsqlint.core.spi.AnalysisInputResolver;
import org.u80dc.jsqlint.core.spi.PreparedInput;
import org.u80dc.jsqlint.core.statistics.StatisticsRecorder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 为 core 的 Path 入口按需接入源码及项目编译。
 * 接收 Analyzer 生成的完整输出路径并传给自动编译输入位置。
 * 直接使用解析器且未指定路径时，由自动编译输入位置分配随机临时目录。
 *
 * @author u80dc
 */
public final class AutoCompileInputResolver implements AnalysisInputResolver {
    private final InputLocationOptions options;

    public AutoCompileInputResolver() { this(InputLocationOptions.defaults()); }

    public AutoCompileInputResolver(InputLocationOptions options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    /** 编译扩展优先于 core 的字节码解析器，避免含源码的项目使用旧产物。 */
    @Override
    public int priority() { return 100; }

    /** 本解析器只接收源码或构建项目，输出目录生命周期交由 Analyzer 管理。 */
    @Override
    public boolean requiresCompilation(Path source) { return true; }

    @Override
    public boolean supports(Path source) throws Exception {
        if (Files.isRegularFile(source)) return source.toString().endsWith(".java");
        if (!Files.isDirectory(source)) return false;
        if (MavenProjectCompiler.isProject(source) || GradleProjectCompiler.isProject(source)) return true;
        return !JavaSourceFiles.collect(source).isEmpty();
    }

    @Override
    public boolean supports(Path source, @Nullable AnalysisInputOptions inputOptions) throws Exception {
        if (inputOptions instanceof InputLocationOptions configured)
            return configured.compiledProjectManifest() != null && Files.isDirectory(source) || supports(source);
        return options.compiledProjectManifest() != null && inputOptions == null && Files.isDirectory(source) || supports(source);
    }

    @Override
    public AnalysisInputPlan plan(Path source, @Nullable AnalysisInputOptions inputOptions,
                                   AnalysisInputContext context) {
        var effective = effective(inputOptions);
        boolean managed = effective.buildPolicy() == BuildPolicy.ALWAYS;
        return new AnalysisInputPlan() {
            public boolean requiresManagedClassOutputDirectory() { return managed; }
            public PreparedInput resolve(@Nullable Path output, AnalysisInputContext execution) {
                return AutoCompileInputResolver.this.resolve(source, output, effective, execution);
            }
        };
    }

    @Override
    public PreparedInput resolve(Path source, Runnable checkpoint) {
        return resolve(source, null, checkpoint);
    }

    @Override
    public PreparedInput resolve(Path source, @Nullable Path classOutputDirectory, Runnable checkpoint) {
        return resolve(source, classOutputDirectory, null, checkpoint);
    }

    /** Analyzer 传入的完整输入选项优先于解析器默认值，每次只创建新选项，不修改解析器实例。 */
    @Override
    public PreparedInput resolve(Path source, @Nullable Path classOutputDirectory,
                                 @Nullable AnalysisInputOptions inputOptions, Runnable checkpoint) {
        return resolve(source, classOutputDirectory, inputOptions,
                new AnalysisInputContext(checkpoint, StatisticsRecorder.none()));
    }

    @Override
    public PreparedInput resolve(Path source, @Nullable Path classOutputDirectory,
                                 @Nullable AnalysisInputOptions inputOptions, AnalysisInputContext context) {
        Objects.requireNonNull(context, "context").checkpoint().run();
        InputLocationOptions configured = effective(inputOptions);
        if (configured.buildPolicy() != BuildPolicy.ALWAYS) {
            var project = ProjectCompiler.compile(source, configured, context);
            try { return ProjectInputs.open(project, false); }
            catch (Exception e) { throw new AnalysisInputException("Cannot open compiled project: " + source, e); }
        }
        // Analyzer 生成的输出路径优先，仓库、构建参数等输入配置随新选项完整保留。
        InputLocationOptions effectiveOptions = classOutputDirectory == null
                ? configured : configured.withClassOutputDirectory(classOutputDirectory);
        // 构造输入位置时完成必要编译，后续类和方法读取才按需转换为 Jimple。
        var location = new AutoCompileAnalysisInputLocation(source, effectiveOptions, context.statisticsRecorder());
        // 创建后立即转交所有权；统一入口再次检查取消，并负责关闭已经创建的输入。
        Path root = Files.isDirectory(source) ? source : source.getParent();
        return new PreparedInput(location.locations(), List.of(new PathResourceSource(root, path -> {
            // 这里只限定默认资源范围，具体文件类型由框架模块判断；特殊目录可显式传入。
            Path relative = root.relativize(path);
            for (Path part : relative)
                if (List.of(".git", ".gradle", ".idea", "target", "build").contains(part.toString())) return false;
            for (int i = 0; i + 1 < relative.getNameCount(); i++)
                if (relative.getName(i).toString().equals("src") && relative.getName(i + 1).toString().equals("test"))
                    return false;
            return true;
        })));
    }

    private InputLocationOptions effective(@Nullable AnalysisInputOptions inputOptions) {
        if (inputOptions != null && !(inputOptions instanceof InputLocationOptions))
            throw new IllegalArgumentException("AutoCompileInputResolver requires InputLocationOptions, got "
                    + inputOptions.getClass().getName());
        return inputOptions == null ? options : (InputLocationOptions) inputOptions;
    }
}
