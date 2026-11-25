package org.u80dc.jsqlint.commons.compiler;

import org.gradle.tooling.GradleConnectionException;
import org.gradle.tooling.GradleConnector;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.u80dc.jsqlint.commons.location.AnalysisInputException;
import org.u80dc.jsqlint.commons.location.BuildPolicy;
import org.u80dc.jsqlint.commons.location.InputLocationOptions;
import org.u80dc.jsqlint.commons.location.InputMode;
import org.u80dc.jsqlint.core.statistics.StatisticsRecorder;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

import static org.u80dc.jsqlint.core.AnalysisStatistics.Phase.COMPILATION;
import static org.u80dc.jsqlint.core.AnalysisStatistics.Phase.CLEANUP;
import static org.u80dc.jsqlint.core.AnalysisStatistics.Phase.PREPARATION;

/**
 * 用临时 init script 读取 Gradle 实际 main source set 产物，不修改项目构建文件。
 *
 * @author u80dc
 */
public final class GradleProjectCompiler {
    private GradleProjectCompiler() {}

    /** 判断目录是否包含 Gradle 构建文件，支持 Groovy 和 Kotlin DSL。 */
    public static boolean isProject(Path root) {
        return Stream.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
                .anyMatch(name -> Files.isRegularFile(root.resolve(name)));
    }

    /** 编译 Gradle 主代码，并返回校验后的 class 目录和分析依赖。 */
    public static CompiledProject compile(Path root, InputLocationOptions options) throws IOException {
        return compile(root, options, StatisticsRecorder.none());
    }

    public static CompiledProject compile(Path root, InputLocationOptions options, StatisticsRecorder statisticsRecorder) throws IOException {
        if (options.buildPolicy() != BuildPolicy.ALWAYS)
            return ProjectCompiler.compile(root, options.withInputMode(InputMode.GRADLE_PROJECT), statisticsRecorder);
        return compileFresh(root, options, statisticsRecorder);
    }

    static CompiledProject compileFresh(Path root, InputLocationOptions options, StatisticsRecorder statisticsRecorder) throws IOException {
        Objects.requireNonNull(statisticsRecorder, "statisticsRecorder");
        if (!isProject(root)) throw new AnalysisInputException("No Gradle build files: " + root);
        Path temporary = Files.createTempDirectory("jsqlint-gradle-");
        ProjectBuildObserver observer = ProjectCompiler.buildObserver();
        GradleConnector connector = GradleConnector.newConnector().forProjectDirectory(root.toFile());
        try {
            if (observer != null) observer.observe(List.of(), List.of(temporary));
            if (options.gradleInstallation() != null) {
                connector.useInstallation(options.gradleInstallation().toFile());
            } else {
                connector.useBuildDistribution();
            }
            Path metadata = temporary.resolve("outputs.properties");
            Path inputMetadata = observer == null ? null : temporary.resolve("inputs.properties");
            Path script = temporary.resolve("compile.gradle");
            String taskName = "jsqlintCompile_" + UUID.randomUUID().toString().replace("-", "");
            Files.writeString(script, initScript(taskName, metadata, options.classOutputDirectory(), inputMetadata), StandardCharsets.UTF_8);
            var arguments = new ArrayList<>(options.buildArguments());
            arguments.addAll(List.of("--init-script", script.toString(), "--console=plain", "--no-configuration-cache"));
            var output = new BuildLog();
            try {
                var connection = connector.connect();
                try (var closeConnection = measuredClose(connection, statisticsRecorder)) {
                    if (observer != null) {
                        try (var preparation = statisticsRecorder.measure(PREPARATION)) {
                            connection.newBuild().forTasks(":" + taskName + "Inputs").withArguments(arguments.toArray(String[]::new))
                                    .setStandardOutput(output).setStandardError(output).run();
                            if (!Files.isRegularFile(inputMetadata))
                                throw new AnalysisInputException("Gradle did not report its build inputs: " + root);
                            var inputProperties = new Properties();
                            try (var reader = Files.newBufferedReader(inputMetadata, StandardCharsets.UTF_8)) {
                                inputProperties.load(reader);
                            }
                            observer.observe(paths(inputProperties, "input"), paths(inputProperties, "output"));
                        }
                    }
                    var build = connection.newBuild().forTasks(":" + taskName).withArguments(arguments.toArray(String[]::new))
                            .setStandardOutput(output).setStandardError(output);
                    try (var compilation = statisticsRecorder.measure(COMPILATION)) {
                        build.run();
                    }
                }
            } catch (GradleConnectionException e) {
                throw new AnalysisInputException("Gradle compilation failed for " + root + "\n"
                        + output.text(), e);
            }
            if (!Files.isRegularFile(metadata)) {
                throw new AnalysisInputException("Gradle did not report its compilation outputs: " + root);
            }
            var properties = new Properties();
            try (var reader = Files.newBufferedReader(metadata, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            var dependencies = new ArrayList<>(options.classpath());
            dependencies.addAll(paths(properties, "dependency"));
            return CompiledProject.fromOutputs(root, InputMode.GRADLE_PROJECT, paths(properties, "classes"), dependencies,
                    paths(properties, "resource"));
        } finally {
            try (var cleanup = statisticsRecorder.measure(CLEANUP)) {
                connector.disconnect();
                deleteTemporaryDirectory(temporary);
            }
        }
    }

    private static @Nullable Closeable measuredClose(@Nullable Closeable resource, StatisticsRecorder statisticsRecorder) {
        if (resource == null || !statisticsRecorder.enabled()) return resource;
        return () -> {
            try (var cleanup = statisticsRecorder.measure(CLEANUP)) {
                resource.close();
            }
        };
    }

    private static void deleteTemporaryDirectory(Path directory) throws IOException {
        // This directory was created by this invocation. Do not follow symlinks or remove project outputs.
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public @NonNull FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public @NonNull FileVisitResult postVisitDirectory(@NonNull Path dir, @Nullable IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static List<Path> paths(Properties metadata, String prefix) {
        String count = metadata.getProperty(prefix + ".count");
        if (count == null) throw new AnalysisInputException("Missing Gradle output metadata: " + prefix);
        List<Path> paths = new ArrayList<>();
        for (int i = 0; i < Integer.parseInt(count); i++) {
            String path = metadata.getProperty(prefix + "." + i);
            if (path == null) throw new AnalysisInputException("Incomplete Gradle output metadata: " + prefix);
            paths.add(Path.of(path));
        }
        return paths;
    }

    private static String groovyString(Path path) {
        return "'" + path.toString().replace("\\", "\\\\").replace("'", "\\'")
                .replace("\r", "\\r").replace("\n", "\\n") + "'";
    }

    private static String initScript(String taskName, Path metadata, Path classOutputDirectory, Path inputMetadata) {
        return """
                gradle.projectsEvaluated {
                    def mainSets = gradle.rootProject.allprojects.collect { p ->
                        def sets = p.extensions.findByName('sourceSets')
                        def main = sets == null ? null : sets.findByName('main')
                        main == null ? null : [project: p, sourceSet: main]
                    }.findAll { it != null }
                    def outputRoot = %s
                    if (outputRoot != null) {
                        mainSets.each { entry ->
                            // 按 source set 的产物任务修改编译输出，保留任务依赖及项目间 classpath 关联。
                            def producers = entry.sourceSet.output.classesDirs.buildDependencies.getDependencies(null)
                            def compilers = producers.findAll { it.hasProperty('destinationDirectory') }
                            if (compilers.empty || compilers.size() != producers.size()) {
                                throw new GradleException('Cannot configure class output directory for ' + entry.project.path)
                            }
                            def moduleOutput = mainSets.size() == 1 ? new File(outputRoot)
                                : new File(outputRoot, 'project-' + java.net.URLEncoder.encode(entry.project.path, 'UTF-8'))
                            compilers.each { compiler ->
                                def destination = compilers.size() == 1 ? moduleOutput : new File(moduleOutput, compiler.name)
                                compiler.destinationDirectory.set(destination)
                            }
                            // 更新 source set 元数据，避免继续收集旧输出目录。
                            entry.sourceSet.output.classesDirs.setFrom(compilers.collect { it.destinationDirectory })
                            entry.sourceSet.output.classesDirs.builtBy(compilers)
                        }
                    }
                    %s
                    gradle.rootProject.tasks.register('%s') {
                        dependsOn mainSets.collect { it.project.tasks.named(it.sourceSet.classesTaskName) }
                        dependsOn mainSets.collect { it.sourceSet.compileClasspath }
                        dependsOn mainSets.collect { it.sourceSet.runtimeClasspath }
                        doLast {
                            def classes = new LinkedHashSet<File>()
                            def resources = new LinkedHashSet<File>()
                            def dependencies = new LinkedHashSet<File>()
                            def ownOutputs = new LinkedHashSet<File>()
                            mainSets.each { entry ->
                                classes.addAll(entry.sourceSet.output.classesDirs.files)
                                resources.addAll(entry.sourceSet.output.classesDirs.files)
                                def resourceOutput = entry.sourceSet.output.resourcesDir
                                if (resourceOutput != null) resources.add(resourceOutput)
                                ownOutputs.addAll(entry.sourceSet.output.files)
                                def jar = entry.project.tasks.findByName('jar')
                                if (jar instanceof org.gradle.api.tasks.bundling.Jar) ownOutputs.add(jar.archiveFile.get().asFile)
                                dependencies.addAll(entry.sourceSet.compileClasspath.files)
                                dependencies.addAll(entry.sourceSet.runtimeClasspath.files)
                            }
                            dependencies.removeAll(ownOutputs)
                            def properties = new Properties()
                            properties.setProperty('classes.count', classes.size().toString())
                            properties.setProperty('resource.count', resources.size().toString())
                            properties.setProperty('dependency.count', dependencies.size().toString())
                            classes.eachWithIndex { file, i -> properties.setProperty('classes.' + i, file.absolutePath) }
                            resources.eachWithIndex { file, i -> properties.setProperty('resource.' + i, file.absolutePath) }
                            dependencies.eachWithIndex { file, i -> properties.setProperty('dependency.' + i, file.absolutePath) }
                            new File(%s).withWriter('UTF-8') { writer -> properties.store(writer, 'JSQLint main outputs') }
                        }
                    }
                }
                """.formatted(classOutputDirectory == null ? "null" : groovyString(classOutputDirectory),
                        inputProbe(taskName, inputMetadata), taskName, groovyString(metadata));
    }

    private static String inputProbe(String taskName, Path metadata) {
        if (metadata == null) return "";
        return """
                gradle.rootProject.tasks.register('%s') {
                    doLast {
                        def inputs = new LinkedHashSet<File>()
                        def outputs = new LinkedHashSet<File>()
                        gradle.rootProject.allprojects.each { project ->
                            inputs.add(project.projectDir)
                            inputs.add(project.buildFile)
                            outputs.add(project.layout.buildDirectory.get().asFile)
                        }
                        mainSets.each { entry ->
                            inputs.addAll(entry.sourceSet.allSource.srcDirs)
                            outputs.addAll(entry.sourceSet.output.classesDirs.files)
                            outputs.addAll(entry.sourceSet.output.files)
                            def resourceOutput = entry.sourceSet.output.resourcesDir
                            if (resourceOutput != null) outputs.add(resourceOutput)
                            def jar = entry.project.tasks.findByName('jar')
                            if (jar instanceof org.gradle.api.tasks.bundling.Jar) outputs.add(jar.archiveFile.get().asFile)
                        }
                        def external = { files ->
                            files.each { file ->
                                def path = file.toPath().toAbsolutePath().normalize()
                                if (file.exists() && !outputs.any { path.startsWith(it.toPath().toAbsolutePath().normalize()) })
                                    inputs.add(file)
                            }
                        }
                        def configurationFiles = { configuration ->
                            if (configuration != null) {
                                def view = configuration.incoming.artifactView {
                                    componentFilter { id -> !(id instanceof org.gradle.api.artifacts.component.ProjectComponentIdentifier) }
                                }
                                external(view.files.files)
                            }
                        }
                        gradle.rootProject.allprojects.each { project ->
                            configurationFiles(project.buildscript.configurations.findByName('classpath'))
                        }
                        mainSets.each { entry ->
                            if (entry.sourceSet.hasProperty('annotationProcessorPath')) {
                                def processors = entry.sourceSet.annotationProcessorPath
                                if (processors instanceof org.gradle.api.artifacts.Configuration)
                                    configurationFiles(processors)
                                else if (processors != null) external(processors.files)
                            }
                        }
                        inputs.removeAll { file ->
                            def path = file.toPath().toAbsolutePath().normalize()
                            outputs.any { path.startsWith(it.toPath().toAbsolutePath().normalize()) }
                        }
                        def properties = new Properties()
                        properties.setProperty('input.count', inputs.size().toString())
                        properties.setProperty('output.count', outputs.size().toString())
                        inputs.eachWithIndex { file, i -> properties.setProperty('input.' + i, file.absolutePath) }
                        outputs.eachWithIndex { file, i -> properties.setProperty('output.' + i, file.absolutePath) }
                        new File(%s).withWriter('UTF-8') { writer -> properties.store(writer, 'JSQLint build inputs') }
                    }
                }
                """.formatted(taskName + "Inputs", groovyString(metadata));
    }
}
