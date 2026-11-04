package org.u80dc.jsqlint.commons.location;

import org.apache.maven.cli.MavenCli;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.eventspy.EventSpy;
import org.apache.maven.eventspy.internal.EventSpyDispatcher;
import org.apache.maven.execution.MavenExecutionResult;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
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
final class MavenProjectCompiler {
    private static final Object CLI_LOCK = new Object();

    private MavenProjectCompiler() {}

    static CompiledProject compile(Path root, InputLocationOptions options) throws IOException {
        if (!Files.isRegularFile(root.resolve("pom.xml"))) {
            throw new AnalysisInputException("Maven pom.xml does not exist: " + root);
        }
        var arguments = new ArrayList<>(List.of("-B", "-ntp"));
        arguments.addAll(options.buildArguments());
        arguments.addAll(List.of("-f", root.resolve("pom.xml").toString(), "-DskipTests=true", "compile"));
        var cli = new CapturingMavenCli();
        var output = new ByteArrayOutputStream();
        int status;
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
        if (status != 0 || cli.result == null || cli.result.hasExceptions()) {
            String details = cli.result == null ? "" : cli.result.getExceptions().toString();
            throw new AnalysisInputException("Maven compilation failed for " + root + " (exit " + status + ")\n"
                    + details + "\n" + output.toString(StandardCharsets.UTF_8));
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
        return ProjectCompiler.result(root, InputMode.MAVEN_PROJECT, outputs, dependencies);
    }

    private static final class CapturingMavenCli extends MavenCli {
        private MavenExecutionResult result;

        @Override
        protected void customizeContainer(PlexusContainer container) {
            try {
                var dispatcher = container.lookup(EventSpyDispatcher.class);
                List<EventSpy> spies = new ArrayList<>(dispatcher.getEventSpies());
                spies.add(new AbstractEventSpy() {
                    @Override
                    public void onEvent(Object event) {
                        if (event instanceof MavenExecutionResult execution) result = execution;
                    }
                });
                dispatcher.setEventSpies(spies);
            } catch (ComponentLookupException e) {
                throw new AnalysisInputException("Cannot collect Maven reactor outputs", e);
            }
        }
    }
}
