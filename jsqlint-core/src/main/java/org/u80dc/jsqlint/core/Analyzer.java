package org.u80dc.jsqlint.core;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.u80dc.jsqlint.core.input.BytecodeInputResolver;
import org.u80dc.jsqlint.core.spi.AnalysisInputResolver;
import org.u80dc.jsqlint.core.spi.PreparedInput;
import org.u80dc.jsqlint.core.spi.SqlAnalysisModule;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.DefaultRuntimeAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.u80dc.jsqlint.core.AnalysisResult.Status;

/**
 * 可复用的同步分析入口。默认通过 ServiceLoader 加载可选扩展；
 * 显式构造器只启用传入模块及解析器，始终保留 core 的字节码解析器。
 *
 * @author u80dc
 */
@SuppressWarnings("unused")
public final class Analyzer {
    private final AnalyzerConfig config;
    private final List<SqlAnalysisModule> modules;
    private final List<AnalysisInputResolver> resolvers;

    /** 从运行时类路径发现可选模块；扩展实例复用，每次分析的状态由独立会话管理。 */
    public Analyzer() {
        this(AnalyzerConfig.defaults());
    }

    /** 指定实例默认配置，模块和解析器仍通过 ServiceLoader 自动发现。 */
    public Analyzer(AnalyzerConfig config) {
        this(config, ServiceLoader.load(SqlAnalysisModule.class).stream().map(ServiceLoader.Provider::get).toList(),
                ServiceLoader.load(AnalysisInputResolver.class).stream().map(ServiceLoader.Provider::get).toList());
    }

    /** 显式指定扩展，适合按需启用模块、配置编译参数或隔离测试环境。 */
    public Analyzer(List<? extends SqlAnalysisModule> modules,
                    List<? extends AnalysisInputResolver> resolvers) {
        this(AnalyzerConfig.defaults(), modules, resolvers);
    }

    /** 同时指定实例配置和扩展；仅启用传入的扩展，始终保留 core 的字节码解析器。 */
    public Analyzer(AnalyzerConfig config, List<? extends SqlAnalysisModule> modules,
                    List<? extends AnalysisInputResolver> resolvers) {
        this.config = Objects.requireNonNull(config, "config");
        this.modules = List.copyOf(modules);
        Set<String> ids = new HashSet<>();
        for (var module : this.modules) {
            if (module.id() == null || module.id().isBlank() || !ids.add(module.id()))
                throw new IllegalArgumentException("SQL module IDs must be nonblank and unique: " + module.id());
        }
        List<AnalysisInputResolver> all = new ArrayList<>(List.copyOf(resolvers));
        all.add(new BytecodeInputResolver());
        this.resolvers = all.stream().sorted(Comparator.comparingInt(AnalysisInputResolver::priority).reversed()).toList();
    }

    private static Status fail(AnalysisSession session, Exception error) {
        try {
            return session.failure(error);
        } catch (RuntimeException listenerError) {
            // 报告原始错误时监听器也可能失败，此时直接返回失败，避免递归报告。
            return Status.FAILED;
        }
    }

    private static List<Path> dependencyPaths(@Nullable Path path) throws IOException {
        if (path == null) return List.of();
        if (Files.isRegularFile(path) && path.toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
            return List.of(path);
        if (!Files.isDirectory(path))
            throw new IllegalArgumentException("Dependencies must be a classpath directory or JAR: " + path);
        List<Path> result = new ArrayList<>();
        // 目录本身可能是类路径根，不能因为其中存在 JAR 就忽略散落的 class 文件。
        result.add(path);
        try (var files = Files.walk(path)) {
            result.addAll(files.filter(Files::isRegularFile)
                    .filter(file -> file.toString().toLowerCase(Locale.ROOT).endsWith(".jar")).sorted().toList());
        }
        return result;
    }

    /**
     * 使用实例默认配置分析路径输入，并自动加入当前 JDK 运行时。
     *
     * @param source 字节码文件、类目录或 JAR；引入 commons 后也支持源码及项目目录
     */
    public @NonNull AnalysisResult analyze(Path source) {
        return analyze(source, AnalysisOptions.defaults());
    }

    /** 指定本次调用的覆盖项；未指定的超时和清理策略继承实例配置。 */
    public @NonNull AnalysisResult analyze(Path source, AnalysisOptions options) {
        return analyze(source, null, options);
    }

    /**
     * 同时指定额外分析依赖和本次选项；管理编译输出目录，并关闭解析器转交的输入。
     * dependencies 可为依赖 JAR 或目录，null 表示不额外指定分析依赖，不参与源码编译配置。
     */
    public @NonNull AnalysisResult analyze(Path source, @Nullable Path dependencies, AnalysisOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        AnalyzerConfig effectiveConfig = options.resolveAgainst(config);
        Path input = source.toAbsolutePath().normalize();
        Path library = dependencies == null ? null : dependencies.toAbsolutePath().normalize();
        if (!Files.exists(input)) throw new IllegalArgumentException("Input path does not exist: " + input);
        if (library != null && !Files.exists(library))
            throw new IllegalArgumentException("Dependency path does not exist: " + library);
        AnalysisSession session = new AnalysisSession(effectiveConfig, options);
        List<AnalysisInputLocation> owned = new ArrayList<>();
        ClassOutputDirectory classOutput = null;
        Status status;
        try {
            session.checkpoint();
            session.progress(AnalysisListener.Stage.PREPARING);
            List<Path> libraries = dependencyPaths(library);
            AnalysisInputResolver selected = null;
            // 只在最高匹配优先级内检查歧义；选中后解析失败也不回退，以免使用旧编译产物。
            for (var resolver : resolvers) {
                session.checkpoint();
                if (selected != null && resolver.priority() < selected.priority()) break;
                if (!resolver.supports(input)) continue;
                if (selected != null) throw new IllegalArgumentException("Ambiguous input resolvers for " + input);
                selected = resolver;
            }
            if (selected == null) throw new IllegalArgumentException(
                    "No input resolver supports " + input + "; add jsqlint-commons for source/project compilation");
            // Analyzer 统一决定输出路径，解析器只负责将完整路径传递给编译输入。
            String outputId = options.classOutputId() == null ? UUID.randomUUID().toString() : options.classOutputId();
            Path output = Path.of(System.getProperty("java.io.tmpdir"), ".jsqlint", outputId)
                    .toAbsolutePath().normalize();
            if (selected.requiresCompilation(input)) {
                classOutput = ClassOutputDirectory.prepare(output, input, library,
                        options.classOutputId() != null, effectiveConfig.cleanupClassOutput());
            }
            PreparedInput prepared = selected.resolve(input, output, session::checkpoint);
            // 先接管输入再检查取消，确保编译期间收到取消信号时也能释放新创建的输入。
            owned.addAll(prepared.locations());
            session.checkpoint();
            for (Path path : libraries)
                owned.add(new JavaClassPathAnalysisInputLocation(path.toString(), SourceType.Library));
            owned.add(new DefaultRuntimeAnalysisInputLocation());
            status = session.execute(new AnalysisRequest(owned, prepared.resources(), options), modules);
        } catch (Exception e) {
            status = fail(session, e);
        } finally {
            try {
                new PreparedInput(owned, List.of()).close();
            } catch (Exception e) {
                status = fail(session, e);
            }
            // 先释放 SootUp 输入，再删除编译产物；输入关闭失败也继续尝试清理。
            if (classOutput != null) {
                try {
                    classOutput.close();
                } catch (Exception e) {
                    status = fail(session, e);
                }
            }
        }
        return session.finish(status);
    }

    /**
     * 分析调用方准备好的代码输入，不补充运行时、不推测资源目录，也不关闭传入的输入。
     * 需要同时提供资源或执行选项时，使用 AnalysisRequest 入口。
     */
    public AnalysisResult analyze(List<? extends AnalysisInputLocation> locations) {
        return analyze(new AnalysisRequest(locations, List.of()));
    }

    /** 统一请求入口；会话只使用请求中的输入，所有权仍由调用方持有。 */
    public AnalysisResult analyze(AnalysisRequest request) {
        Objects.requireNonNull(request, "request");
        AnalysisSession session = new AnalysisSession(request.options().resolveAgainst(config), request.options());
        Status status;
        try {
            session.checkpoint();
            session.progress(AnalysisListener.Stage.PREPARING);
            status = session.execute(request, modules);
        } catch (Exception e) {
            status = fail(session, e);
        }
        return session.finish(status);
    }
}
