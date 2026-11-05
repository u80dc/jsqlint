package org.u80dc.jsqlint.commons.compiler;

import org.gradle.tooling.GradleConnectionException;
import org.gradle.tooling.GradleConnector;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.u80dc.jsqlint.commons.location.AnalysisInputException;
import org.u80dc.jsqlint.commons.location.InputLocationOptions;
import org.u80dc.jsqlint.commons.location.InputMode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

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
        if (!isProject(root)) throw new AnalysisInputException("No Gradle build files: " + root);
        Path temporary = Files.createTempDirectory("jsqlint-gradle-");
        GradleConnector connector = GradleConnector.newConnector().forProjectDirectory(root.toFile());
        try {
            if (options.gradleInstallation() != null) {
                connector.useInstallation(options.gradleInstallation().toFile());
            } else {
                connector.useBuildDistribution();
            }
            Path metadata = temporary.resolve("outputs.properties");
            Path script = temporary.resolve("compile.gradle");
            String taskName = "jsqlintCompile_" + UUID.randomUUID().toString().replace("-", "");
            Files.writeString(script, initScript(taskName, metadata, options.classOutputDirectory()), StandardCharsets.UTF_8);
            var arguments = new ArrayList<>(options.buildArguments());
            arguments.addAll(List.of("--init-script", script.toString(), "--console=plain", "--no-configuration-cache"));
            var output = new ByteArrayOutputStream();
            try (var connection = connector.connect()) {
                connection.newBuild().forTasks(":" + taskName).withArguments(arguments.toArray(String[]::new))
                        .setStandardOutput(output).setStandardError(output).run();
            } catch (GradleConnectionException e) {
                throw new AnalysisInputException("Gradle compilation failed for " + root + "\n"
                        + output.toString(StandardCharsets.UTF_8), e);
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
            return CompiledProject.fromOutputs(root, InputMode.GRADLE_PROJECT, paths(properties, "classes"), dependencies);
        } finally {
            connector.disconnect();
            deleteTemporaryDirectory(temporary);
        }
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

    private static String initScript(String taskName, Path metadata, Path classOutputDirectory) {
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
                    gradle.rootProject.tasks.register('%s') {
                        dependsOn mainSets.collect { it.project.tasks.named(it.sourceSet.classesTaskName) }
                        dependsOn mainSets.collect { it.sourceSet.compileClasspath }
                        dependsOn mainSets.collect { it.sourceSet.runtimeClasspath }
                        doLast {
                            def classes = new LinkedHashSet<File>()
                            def dependencies = new LinkedHashSet<File>()
                            def ownOutputs = new LinkedHashSet<File>()
                            mainSets.each { entry ->
                                classes.addAll(entry.sourceSet.output.classesDirs.files)
                                ownOutputs.addAll(entry.sourceSet.output.files)
                                def jar = entry.project.tasks.findByName('jar')
                                if (jar instanceof org.gradle.api.tasks.bundling.Jar) ownOutputs.add(jar.archiveFile.get().asFile)
                                dependencies.addAll(entry.sourceSet.compileClasspath.files)
                                dependencies.addAll(entry.sourceSet.runtimeClasspath.files)
                            }
                            dependencies.removeAll(ownOutputs)
                            def properties = new Properties()
                            properties.setProperty('classes.count', classes.size().toString())
                            properties.setProperty('dependency.count', dependencies.size().toString())
                            classes.eachWithIndex { file, i -> properties.setProperty('classes.' + i, file.absolutePath) }
                            dependencies.eachWithIndex { file, i -> properties.setProperty('dependency.' + i, file.absolutePath) }
                            new File(%s).withWriter('UTF-8') { writer -> properties.store(writer, 'JSQLint main outputs') }
                        }
                    }
                }
                """.formatted(classOutputDirectory == null ? "null" : groovyString(classOutputDirectory),
                        taskName, groovyString(metadata));
    }
}
