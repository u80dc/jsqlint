package org.u80dc.jsqlint.commons.compiler;

import org.apache.maven.cli.MavenCli;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.eventspy.EventSpy;
import org.apache.maven.eventspy.internal.EventSpyDispatcher;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.u80dc.jsqlint.commons.location.AnalysisInputException;
import org.u80dc.jsqlint.commons.location.InputLocationOptions;
import org.u80dc.jsqlint.commons.location.InputMode;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 通过 Maven Embedder 编译，并从有效 reactor 模型中读取产物和依赖。
 *
 * @author u80dc
 */
public final class MavenProjectCompiler {
    private static final Object CLI_LOCK = new Object();

    private MavenProjectCompiler() {}

    /** 判断目录是否包含 Maven 构建文件，不执行构建。 */
    public static boolean isProject(Path root) {
        return Files.isRegularFile(root.resolve("pom.xml"));
    }

    /** 编译 Maven 主代码，并返回校验后的 class 目录和分析依赖。 */
    public static CompiledProject compile(Path root, InputLocationOptions options) throws IOException {
        if (!isProject(root)) {
            throw new AnalysisInputException("Maven pom.xml does not exist: " + root);
        }
        validateRepositoryArguments(options.buildArguments());
        var cli = new CapturingMavenCli(options.classOutputDirectory());
        var output = new BuildLog();
        int status;
        try (var settings = MavenBuildSettings.create(options.mavenRepositories())) {
            var arguments = new ArrayList<>(List.of("-B", "-ntp"));
            arguments.addAll(options.buildArguments());
            arguments.addAll(settings.arguments());
            arguments.addAll(List.of("-f", root.resolve("pom.xml").toString(), "-DskipTests=true", "compile"));
            synchronized (CLI_LOCK) {
                String property = MavenCli.MULTIMODULE_PROJECT_DIRECTORY;
                String previous = System.getProperty(property);
                ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
                try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
                    System.setProperty(property, root.toString());
                    status = cli.doMain(arguments.toArray(String[]::new), root.toString(), stream, stream);
                } finally {
                    if (previous == null) System.clearProperty(property);
                    else System.setProperty(property, previous);
                    Thread.currentThread().setContextClassLoader(previousLoader);
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
            throw new AnalysisInputException(message);
        }
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
        return CompiledProject.fromOutputs(root, InputMode.MAVEN_PROJECT, outputs, dependencies);
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
        private MavenExecutionResult result;

        private CapturingMavenCli(Path classOutputDirectory) {
            this.classOutputDirectory = classOutputDirectory;
        }

        private void configureOutput(ExecutionEvent event) {
            if (classOutputDirectory == null) return;
            if (event.getType() == ExecutionEvent.Type.SessionStarted) {
                // 在依赖解析前调整整个 reactor，模块间编译依赖随之指向新的 class 目录。
                var projects = event.getSession().getProjects().stream()
                        .filter(project -> !"pom".equals(project.getPackaging())).toList();
                for (MavenProject project : projects) {
                    Path output = projects.size() == 1 ? classOutputDirectory
                            : classOutputDirectory.resolve("group-" + encode(project.getGroupId()))
                                    .resolve("module-" + encode(project.getArtifactId()));
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
                }
            }
        }

        private static String encode(String coordinate) {
            return URLEncoder.encode(coordinate, StandardCharsets.UTF_8);
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
                        if (event instanceof ExecutionEvent execution) configureOutput(execution);
                    }
                });
                dispatcher.setEventSpies(spies);
            } catch (ComponentLookupException e) {
                throw new AnalysisInputException("Cannot c ollect Maven reactor outputs", e);
            }
        }
    }
}
