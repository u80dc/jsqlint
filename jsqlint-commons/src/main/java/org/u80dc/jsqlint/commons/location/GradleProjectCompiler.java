package org.u80dc.jsqlint.commons.location;

import org.gradle.tooling.GradleConnectionException;
import org.gradle.tooling.GradleConnector;

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

/**
 * 用临时 init script 读取 Gradle 实际 main source set 产物，不修改项目构建文件。
 *
 * @author u80dc
 */
final class GradleProjectCompiler {
    private GradleProjectCompiler() {}

    static CompiledProject compile(Path root, InputLocationOptions options) throws IOException {
        boolean buildFile = List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
                .stream().anyMatch(name -> Files.isRegularFile(root.resolve(name)));
        if (!buildFile) throw new AnalysisInputException("No Gradle build files: " + root);
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
            Files.writeString(script, initScript(taskName, metadata), StandardCharsets.UTF_8);
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
            return ProjectCompiler.result(root, InputMode.GRADLE_PROJECT, paths(properties, "classes"), dependencies);
        } finally {
            connector.disconnect();
            deleteTemporaryDirectory(temporary);
        }
    }

    private static void deleteTemporaryDirectory(Path directory) throws IOException {
        // This directory was created by this invocation. Do not follow symlinks or remove project outputs.
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
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

    private static String initScript(String taskName, Path metadata) {
        String destination = metadata.toString().replace("\\", "\\\\").replace("'", "\\'");
        return """
                gradle.projectsEvaluated {
                    def mainSets = gradle.rootProject.allprojects.collect { p ->
                        def sets = p.extensions.findByName('sourceSets')
                        def main = sets == null ? null : sets.findByName('main')
                        main == null ? null : [project: p, sourceSet: main]
                    }.findAll { it != null }
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
                            new File('%s').withWriter('UTF-8') { writer -> properties.store(writer, 'JSQLint main outputs') }
                        }
                    }
                }
                """.formatted(taskName, destination);
    }
}
