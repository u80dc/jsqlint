package org.u80dc.jsqlint.commons.compiler;

import org.apache.maven.cli.CliRequest;
import org.apache.maven.cli.MavenCli;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.eventspy.EventSpy;
import org.apache.maven.eventspy.internal.EventSpyDispatcher;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.plugin.descriptor.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.u80dc.jsqlint.commons.location.AnalysisInputException;
import org.u80dc.jsqlint.commons.location.BuildPolicy;
import org.u80dc.jsqlint.commons.location.InputLocationOptions;
import org.u80dc.jsqlint.commons.location.InputMode;
import org.u80dc.jsqlint.core.statistics.StatisticsRecorder;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

import static org.u80dc.jsqlint.core.AnalysisStatistics.Phase.COMPILATION;
import static org.u80dc.jsqlint.core.AnalysisStatistics.Phase.WAITING;

/**
 * 通过 Maven Embedder 编译，并从有效 reactor 模型中读取产物和依赖。
 *
 * @author u80dc
 */
public final class MavenProjectCompiler {
    private static final Object CLI_LOCK = new Object();
    private static final String GUICE_CLASS_LOADING = "guice_custom_class_loading";
    private static final String GUICE_ANNOTATION_CHECK = "guice.disable.misplaced.annotation.check";

    private MavenProjectCompiler() {}

    /** 配置摘要等待当前 Maven 调用还原临时属性，再复制宿主属性。 */
    static Map<String, String> systemPropertiesSnapshot() {
        synchronized (CLI_LOCK) {
            var properties = System.getProperties();
            synchronized (properties) {
                var snapshot = new HashMap<String, String>();
                for (String name : properties.stringPropertyNames()) snapshot.put(name, properties.getProperty(name));
                return Map.copyOf(snapshot);
            }
        }
    }

    /** 判断目录是否包含 Maven 构建文件，不执行构建。 */
    public static boolean isProject(Path root) {
        return Files.isRegularFile(root.resolve("pom.xml"));
    }

    /** 编译 Maven 主代码，并返回校验后的 class 目录和分析依赖。 */
    public static CompiledProject compile(Path root, InputLocationOptions options) throws IOException {
        return compile(root, options, StatisticsRecorder.none());
    }

    public static CompiledProject compile(Path root, InputLocationOptions options, StatisticsRecorder statisticsRecorder) throws IOException {
        if (options.buildPolicy() != BuildPolicy.ALWAYS)
            return ProjectCompiler.compile(root, options.withInputMode(InputMode.MAVEN_PROJECT), statisticsRecorder);
        return compileFresh(root, options, statisticsRecorder);
    }

    static CompiledProject compileFresh(Path root, InputLocationOptions options, StatisticsRecorder statisticsRecorder) throws IOException {
        Objects.requireNonNull(statisticsRecorder, "statisticsRecorder");
        if (!isProject(root)) {
            throw new AnalysisInputException("Maven pom.xml does not exist: " + root);
        }
        validateRepositoryArguments(options.buildArguments());
        var cli = new CapturingMavenCli(options.classOutputDirectory());
        var output = new BuildLog();
        int status;
        try (var settings = MavenBuildSettings.create(options.mavenRepositories(), statisticsRecorder)) {
            var arguments = new ArrayList<>(List.of("-B", "-ntp"));
            arguments.addAll(options.buildArguments());
            arguments.addAll(settings.arguments());
            arguments.addAll(List.of("-f", root.resolve("pom.xml").toString(), "-DskipTests=true", "compile"));
            try (var waiting = statisticsRecorder.measure(WAITING)) {
                synchronized (CLI_LOCK) {
                    // 等待只计入获取全局CLI锁之前，实际Maven执行单独归因。
                    waiting.close();
                    String property = MavenCli.MULTIMODULE_PROJECT_DIRECTORY;
                    String previous = System.getProperty(property);
                    ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
                    try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
                        System.setProperty(property, root.toString());
                        try (var compilation = statisticsRecorder.measure(COMPILATION)) {
                            status = cli.doMain(arguments.toArray(String[]::new), root.toString(), stream, stream);
                        }
                    } finally {
                        if (previous == null) System.clearProperty(property);
                        else System.setProperty(property, previous);
                        Thread.currentThread().setContextClassLoader(previousLoader);
                    }
                }
            }
        }
        if (status != 0 || cli.result == null || cli.result.hasExceptions()) {
            String details = cli.result == null ? "" : cli.result.getExceptions().toString();
            String message = "Maven compilation failed for " + root + " (exit " + status + ")\n"
                    + details + "\n" + output.text();
            for (var repository : options.mavenRepositories().remoteRepositories()) {
                if (repository.password() != null && !repository.password().isEmpty()) {
                    message = message.replace(repository.password(), "***");
                }
            }
            var failure = new AnalysisInputException(message);
            if (cli.observationFailure != null) failure.addSuppressed(cli.observationFailure);
            throw failure;
        }
        if (cli.observationFailure != null) throw cli.observationFailure;
        var projects = cli.result.getTopologicallySortedProjects();
        if (projects == null || projects.isEmpty()) {
            throw new AnalysisInputException("Maven returned no reactor projects: " + root);
        }
        List<Path> outputs = new ArrayList<>();
        List<Path> dependencies = new ArrayList<>(options.classpath());
        Set<String> reactor = new HashSet<>();
        for (var project : projects) {
            reactor.add(project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion());
            if (!"pom".equals(project.getPackaging())) {
                outputs.add(Path.of(project.getBuild().getOutputDirectory()));
            }
        }
        for (var project : projects) {
            for (var artifact : project.getArtifacts()) {
                if ("test".equals(artifact.getScope()) || "pom".equals(artifact.getType())) continue;
                String id = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();
                // Reactor classes are available after compile, even before their JAR is packaged.
                if (reactor.contains(id) && !artifact.hasClassifier()) continue;
                if (artifact.getFile() == null || !artifact.getFile().exists()) {
                    throw new AnalysisInputException("Unresolved Maven analysis dependency: " + artifact);
                }
                dependencies.add(artifact.getFile().toPath());
            }
        }
        // 没有Java类的模块仍可能提供已过滤的Mapper XML或配置资源。
        return CompiledProject.fromOutputs(root, InputMode.MAVEN_PROJECT, outputs, dependencies, outputs);
    }

    private static void validateRepositoryArguments(List<String> arguments) {
        for (String argument : arguments) {
            // 仓库配置只有一个入口，避免原始参数与结构化配置互相覆盖。
            if (argument.startsWith("-s") || argument.startsWith("-gs")
                    || argument.equals("--settings") || argument.startsWith("--settings=")
                    || argument.equals("--global-settings") || argument.startsWith("--global-settings=")
                    || argument.matches("(?:-D|--define=)?maven\\.repo\\.local(?:=.*)?")) {
                throw new IllegalArgumentException("Configure Maven repositories through InputLocationOptions.withMavenRepositories(), not buildArguments");
            }
        }
    }

    private static final class CapturingMavenCli extends MavenCli {
        private final Path classOutputDirectory;
        private final ProjectBuildObserver observer;
        private final Set<Path> fileOutputs = new LinkedHashSet<>();
        private final Map<MavenProject, OutputRelocation> outputRelocations = new IdentityHashMap<>();
        private MavenExecutionResult result;
        private IOException observationFailure;

        private CapturingMavenCli(Path classOutputDirectory) {
            this.classOutputDirectory = classOutputDirectory;
            this.observer = ProjectCompiler.buildObserver();
        }

        @Override
        public int doMain(CliRequest request) {
            Map<String, String> previous = systemPropertiesSnapshot();
            try {
                return super.doMain(request);
            } finally {
                var properties = System.getProperties();
                synchronized (properties) {
                    var userProperties = request.getUserProperties();
                    for (String name : userProperties.stringPropertyNames()) {
                        // Maven 将命令行及 maven.config 的 -D 写入宿主，只还原仍由本次调用持有的值。
                        restoreIfUnchanged(properties, previous, name, userProperties.getProperty(name));
                    }
                    String previousGuice = previous.get(GUICE_CLASS_LOADING);
                    if (previousGuice == null || previousGuice.trim().isEmpty())
                        restoreIfUnchanged(properties, previous, GUICE_CLASS_LOADING, "CHILD");
                    restoreIfUnchanged(properties, previous, GUICE_ANNOTATION_CHECK, "true");
                }
            }
        }

        private static void restoreIfUnchanged(Properties properties, Map<String, String> previous,
                                                String name, String written) {
            if (!Objects.equals(properties.getProperty(name), written)) return;
            String value = previous.get(name);
            if (value == null) properties.remove(name);
            else properties.setProperty(name, value);
        }

        private synchronized void configureOutput(ExecutionEvent event) {
            if (classOutputDirectory == null) return;
            if (event.getType() == ExecutionEvent.Type.SessionStarted) {
                // 在依赖解析前调整整个 reactor，模块间编译依赖随之指向新的 class 目录。
                var projects = event.getSession().getProjects().stream()
                        .filter(project -> !"pom".equals(project.getPackaging())).toList();
                for (MavenProject project : projects) {
                    Path output = projects.size() == 1 ? classOutputDirectory
                            : classOutputDirectory.resolve("group-" + encode(project.getGroupId()))
                                    .resolve("module-" + encode(project.getArtifactId()));
                    String originalOutput = project.getBuild().getOutputDirectory();
                    if (originalOutput != null && !originalOutput.isBlank()) {
                        Path original = Path.of(originalOutput);
                        if (!original.isAbsolute() && project.getBasedir() != null)
                            original = project.getBasedir().toPath().resolve(original);
                        var relocation = new OutputRelocation(original.toAbsolutePath().normalize(), output.toAbsolutePath().normalize());
                        outputRelocations.put(project, relocation);
                        for (var resource : project.getResources())
                            resource.setTargetPath(relocation.targetPath(resource.getTargetPath()));
                    }
                    project.getBuild().setOutputDirectory(output.toString());
                }
            } else if (event.getType() == ExecutionEvent.Type.MojoStarted) {
                var mojo = event.getMojoExecution();
                // 执行配置可能已经插值，或显式覆盖了 build.outputDirectory，需要同步标准插件参数。
                if ("org.apache.maven.plugins".equals(mojo.getGroupId())
                        && (("maven-compiler-plugin".equals(mojo.getArtifactId()) && "compile".equals(mojo.getGoal()))
                        || ("maven-resources-plugin".equals(mojo.getArtifactId()) && "resources".equals(mojo.getGoal())))) {
                    Xpp3Dom configuration = mojo.getConfiguration();
                    Xpp3Dom output = configuration.getChild("outputDirectory");
                    if (output == null) {
                        output = new Xpp3Dom("outputDirectory");
                        configuration.addChild(output);
                    }
                    output.setValue(event.getProject().getBuild().getOutputDirectory());
                    if ("maven-resources-plugin".equals(mojo.getArtifactId())) {
                        var relocation = outputRelocations.get(event.getProject());
                        var resources = configuration.getChild("resources");
                        if (relocation != null && resources != null) {
                            for (var resource : resources.getChildren()) {
                                var target = resource.getChild("targetPath");
                                if (target != null) target.setValue(relocation.targetPath(target.getValue()));
                            }
                        }
                    }
                }
            }
        }

        private record OutputRelocation(Path original, Path relocated) {
            private String targetPath(String target) {
                if (target == null || target.isBlank()) return target;
                Path path = Path.of(target);
                if (!path.isAbsolute()) return target;
                path = path.normalize();
                return path.startsWith(original) ? relocated.resolve(original.relativize(path)).toString() : target;
            }
        }

        private static String encode(String coordinate) {
            return URLEncoder.encode(coordinate, StandardCharsets.UTF_8);
        }

        private synchronized void observeBuild(ExecutionEvent event) {
            if (observer == null || observationFailure != null
                    || event.getType() != ExecutionEvent.Type.SessionStarted && event.getType() != ExecutionEvent.Type.MojoStarted)
                return;
            try {
                var inputs = new LinkedHashSet<Path>();
                var outputs = new LinkedHashSet<>(fileOutputs);
                List<MavenProject> projects = event.getSession().getProjects();
                List<MavenProject> selected = event.getType() == ExecutionEvent.Type.SessionStarted
                        ? projects : List.of(event.getProject());
                for (var project : projects) {
                    addPath(outputs, project.getBuild().getDirectory(), project.getBasedir());
                    addPath(outputs, project.getBuild().getOutputDirectory(), project.getBasedir());
                    addPath(outputs, project.getBuild().getTestOutputDirectory(), project.getBasedir());
                }
                for (var project : selected) {
                    if (project.getBasedir() != null) inputs.add(project.getBasedir().toPath().toAbsolutePath().normalize());
                    var parents = new HashSet<MavenProject>();
                    for (var parent = project; parent != null && parents.add(parent); parent = parent.getParent()) {
                        if (parent.getFile() != null) inputs.add(parent.getFile().toPath().toAbsolutePath().normalize());
                    }
                    addSource(inputs, outputs, project.getBuild().getSourceDirectory(), project.getBasedir());
                    for (String source : project.getCompileSourceRoots()) addSource(inputs, outputs, source, project.getBasedir());
                    for (var resource : project.getResources()) addSource(inputs, outputs, resource.getDirectory(), project.getBasedir());
                    addArtifacts(inputs, project.getArtifacts());
                    addArtifacts(inputs, project.getPluginArtifacts());
                    addArtifacts(inputs, project.getExtensionArtifacts());
                }
                if (event.getType() == ExecutionEvent.Type.MojoStarted && event.getMojoExecution().getMojoDescriptor() != null) {
                    observeConfiguredFiles(event, inputs, outputs);
                    var plugin = event.getMojoExecution().getMojoDescriptor().getPluginDescriptor();
                    if (plugin != null) addArtifacts(inputs, plugin.getArtifacts());
                }
                inputs.removeIf(input -> outputs.stream().anyMatch(input::startsWith));
                observer.observe(inputs, outputs);
            } catch (IOException failure) {
                observationFailure = failure;
            } catch (RuntimeException failure) {
                observationFailure = new IOException("Cannot statically observe Maven build inputs", failure);
            }
        }

        private void observeConfiguredFiles(ExecutionEvent event, Set<Path> inputs, Set<Path> outputs) throws IOException {
            var parameters = event.getMojoExecution().getMojoDescriptor().getParameterMap();
            var outputParameter = parameters.get("outputFile");
            if (outputParameter == null) return;
            Path output = configuredPath(event, outputParameter, false);
            if (output != null) {
                // 只排除声明的单文件；保留后续 mojo 读取生成中间文件时的输出身份。
                fileOutputs.add(output);outputs.add(output);
            }
            var inputParameter = parameters.get("file");
            if (inputParameter != null) {
                Path input = configuredPath(event, inputParameter, true);
                if (input != null) inputs.add(input);
            }
        }

        private static Path configuredPath(ExecutionEvent event, Parameter parameter, boolean requireConfiguration) throws IOException {
            String type = parameter.getType();
            if (type == null || !Set.of("java.lang.String", "java.io.File", "java.nio.file.Path").contains(type)) return null;
            var configuration = event.getMojoExecution().getConfiguration();
            Xpp3Dom value = configuration == null ? null : configuration.getChild(parameter.getName());
            if (value == null && configuration != null && parameter.getAlias() != null)
                value = configuration.getChild(parameter.getAlias());
            if (requireConfiguration && value == null) return null;
            String expression = value == null ? parameter.getExpression() : value.getValue();
            String fallback = value == null ? parameter.getDefaultValue() : value.getAttribute("default-value");
            if (fallback == null) fallback = parameter.getDefaultValue();
            try {
                var evaluator = new PluginParameterExpressionEvaluator(event.getSession(), event.getMojoExecution());
                Object evaluated = expression == null || expression.isBlank() ? null : evaluator.evaluate(expression);
                if (evaluated == null && fallback != null && !fallback.isBlank()) evaluated = evaluator.evaluate(fallback);
                Path path;
                if (evaluated instanceof java.io.File file) path = file.toPath();
                else if (evaluated instanceof Path file) path = file;
                else if (evaluated instanceof String text) {
                    if (text.isBlank() || text.contains("${") || text.indexOf('*') >= 0 || text.indexOf('?') >= 0) return null;
                    path = Path.of(text);
                } else return null;
                String raw = path.toString();
                if (raw.contains("${") || raw.indexOf('*') >= 0 || raw.indexOf('?') >= 0) return null;
                if (!path.isAbsolute()) {
                    if (event.getProject().getBasedir() == null) return null;
                    path = event.getProject().getBasedir().toPath().resolve(path);
                }
                path = path.toAbsolutePath().normalize();
                return path.getFileName() == null || Files.isDirectory(path) ? null : path;
            } catch (ExpressionEvaluationException failure) {
                throw new IOException("Cannot resolve Maven file parameter: " + parameter.getName(), failure);
            }
        }

        private static void addPath(Set<Path> paths, String value, java.io.File basedir) {
            if (value == null || value.isBlank()) return;
            Path path = Path.of(value);
            if (!path.isAbsolute() && basedir != null) path = basedir.toPath().resolve(path);
            paths.add(path.toAbsolutePath().normalize());
        }

        private static void addSource(Set<Path> inputs, Set<Path> outputs, String value, java.io.File basedir) {
            var paths = new LinkedHashSet<Path>();
            addPath(paths, value, basedir);
            for (Path path : paths) if (outputs.stream().noneMatch(path::startsWith)) inputs.add(path);
        }

        private static void addArtifacts(Set<Path> inputs, Collection<org.apache.maven.artifact.Artifact> artifacts) {
            if (artifacts == null) return;
            for (var artifact : artifacts) if (artifact.getFile() != null && artifact.getFile().isFile())
                inputs.add(artifact.getFile().toPath().toAbsolutePath().normalize());
        }

        @Override
        protected void customizeContainer(PlexusContainer container) {
            try {
                var dispatcher = container.lookup(EventSpyDispatcher.class);
                List<EventSpy> spies = new ArrayList<>(dispatcher.getEventSpies());
                spies.add(new AbstractEventSpy() {
                    @Override
                    public void onEvent(Object event) {
                        if (event instanceof MavenExecutionResult execution) result = execution;
                        if (event instanceof ExecutionEvent execution) {
                            try {
                                configureOutput(execution);
                                observeBuild(execution);
                            } catch (RuntimeException failure) {
                                if (observer == null) throw failure;
                                if (observationFailure == null)
                                    observationFailure = new IOException("Cannot configure observed Maven outputs", failure);
                            }
                        }
                    }
                });
                dispatcher.setEventSpies(spies);
            } catch (ComponentLookupException e) {
                throw new AnalysisInputException("Cannot c ollect Maven reactor outputs", e);
            }
        }
    }
}
