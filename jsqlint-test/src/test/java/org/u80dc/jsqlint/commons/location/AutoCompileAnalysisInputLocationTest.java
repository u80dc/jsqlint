package org.u80dc.jsqlint.commons.location;

import org.apache.maven.cli.MavenCli;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.u80dc.jsqlint.commons.compiler.ProjectCompiler;
import sootup.core.interceptor.BodyInterceptor;
import sootup.core.model.SourceType;
import sootup.java.core.views.JavaView;

import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.commons.location.AutoCompileAnalysisInputLocation.InputType.*;

/**
 * 实际编译及 Jimple 转换集成测试。
 *
 * @author u80dc
 */
class AutoCompileAnalysisInputLocationTest {
    @TempDir Path temporary;

    @Test
    void sourceDirectoryCompilesMainSourcesIntoTemporaryOutput() throws Exception {
        Path root = temporary.resolve("Java 项目 with spaces");
        write(root, "src/main/java/build/Example.java", "package build; public class Example { public int value() { return 7; } }");
        write(root, "src/test/java/Invalid.java", "not Java");
        write(root, "target/Ignore.java", "not Java");
        try (var input = new AutoCompileAnalysisInputLocation(root)) {
            assertEquals(JAVA_SOURCE, input.getInputType());
            assertEquals(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize(),
                    input.getClassOutputDirectory().getParent());
            assertTrue(Files.isRegularFile(input.getClassOutputDirectory().resolve("build/Example.class")));
            assertTrue(body(input, "build.Example").contains("return 7"));
            var view = input.createView();
            assertTrue(view.getClass(view.getIdentifierFactory().getClassType("java.lang.Object")).orElseThrow().isLibraryClass());
            try (var sources = input.getClassSources(view)) {
                assertEquals(List.of("build.Example"), sources.map(source -> source.getClassType().toString()).toList());
            }
            assertTrue(input.getClassSource(view.getIdentifierFactory().getClassType("missing.Type"), view).isEmpty());
        }
        assertFalse(Files.exists(root.resolve("src/main/java/build/Example.class")));
        assertTrue(Files.isRegularFile(root.resolve("src/main/java/build/Example.java")));
    }

    @Test
    void acceptsSingleFileAndCompilesMultipleFilesOrDirectoriesTogether() throws Exception {
        Path single = temporary.resolve("one");
        write(single, "Single.java", "public class Single { public int value() { return 2; } }");
        try (var input = new AutoCompileAnalysisInputLocation(single.resolve("Single.java"))) {
            assertTrue(body(input, "Single").contains("return 2"));
        }
        Path first = temporary.resolve("first");
        Path second = temporary.resolve("second");
        write(first, "build/Example.java", "package build; public class Example { public int value() { return test.Helper.value(); } }");
        write(second, "test/Helper.java", "package test; public class Helper { public static int value() { return 11; } }");
        for (List<Path> paths : List.of(List.of(first, second),
                List.of(first.resolve("build/Example.java"), second.resolve("test/Helper.java")),
                List.of(first, first.resolve("build/Example.java"), second))) {
            try (var input = new AutoCompileAnalysisInputLocation(paths)) {
                assertEquals(JAVA_SOURCE, input.getInputType());
                assertTrue(body(input, "build.Example").contains("test.Helper"));
                assertTrue(body(input, "test.Helper").contains("return 11"));
            }
        }
    }

    @Test
    void explicitOutputCompilesSourcesTogetherAndPreservesGeneratedClassesAfterClose() throws Exception {
        Path first = temporary.resolve("first/pkg/Example.java");
        Path second = temporary.resolve("second");
        write(first.getParent(), "Example.java", """
                package pkg;
                public class Example {
                    public int value() { return new Helper().value(); }
                    public static class Nested { public int value() { return 37; } }
                }
                """);
        write(second, "pkg/Helper.java", "package pkg; public class Helper { public int value() { return 41; } }");
        Path output = temporary.resolve("输出 classes with spaces");
        var options = InputLocationOptions.defaults().withClassOutputDirectory(output.resolve("unused/.."));
        assertEquals(output, options.classOutputDirectory());
        try (var input = new AutoCompileAnalysisInputLocation(List.of(first, second), options)) {
            assertEquals(JAVA_SOURCE, input.getInputType());
            assertTrue(body(input, "pkg.Example").contains("pkg.Helper"));
            assertTrue(body(input, "pkg.Example$Nested").contains("return 37"));
            assertTrue(body(input, "pkg.Helper").contains("return 41"));
        }
        assertTrue(Files.isRegularFile(output.resolve("pkg/Example.class")));
        assertTrue(Files.isRegularFile(output.resolve("pkg/Example$Nested.class")));
        assertFalse(Files.exists(first.resolveSibling("Example.class")));
        // 输出归调用方所有；后续构建可以复用，关闭分析输入不能删除它。
        write(output, "keep.txt", "caller-owned");
        write(second, "pkg/Helper.java", "package pkg; public class Helper { public int value() { return 43; } }");
        try (var input = new AutoCompileAnalysisInputLocation(List.of(first, second), options)) {
            assertTrue(body(input, "pkg.Helper").contains("return 43"));
        }
        try (var input = new AutoCompileAnalysisInputLocation(output)) {
            assertEquals(BYTECODE, input.getInputType());
            assertNull(input.getClassOutputDirectory());
            assertTrue(body(input, "pkg.Example$Nested").contains("return 37"));
        }
        assertEquals("caller-owned", Files.readString(output.resolve("keep.txt")));
    }

    @Test
    void explicitOutputValidatesPathsAndReportsCompilationErrors() throws Exception {
        Path root = temporary.resolve("source");
        write(root, "Example.java", "public class Example { syntax error }");
        var options = InputLocationOptions.defaults().withClassOutputDirectory(temporary.resolve("classes"));
        var error = assertThrows(AnalysisInputException.class, () -> new AutoCompileAnalysisInputLocation(root, options));
        assertTrue(error.getMessage().contains("Example.java"));
        write(temporary, "not-directory", "caller-owned");
        assertThrows(IllegalArgumentException.class, () -> new AutoCompileAnalysisInputLocation(root,
                options.withClassOutputDirectory(temporary.resolve("not-directory"))));
        assertThrows(IllegalArgumentException.class, () -> new AutoCompileAnalysisInputLocation(root,
                options.withClassOutputDirectory(root)));
        Path classes = fixture("bytecode-output", "Example.java", "public class Example {}");
        assertThrows(IllegalArgumentException.class, () -> new AutoCompileAnalysisInputLocation(classes, options));
        assertEquals("caller-owned", Files.readString(temporary.resolve("not-directory")));
    }

    @Test
    void bytecodeDirectoriesAndJarsLoadWithoutCompilingAndPreserveClassification() throws Exception {
        Path classes = fixture("app", "Example.java", "public class Example { public int value() { return 17; } }");
        Path jar = jar(fixture("library", "Helper.java", "package dep; public class Helper { public int value() { return 19; } }"));
        var options = new InputLocationOptions(InputMode.AUTO, List.of(jar), List.of(), null);
        try (var input = new AutoCompileAnalysisInputLocation(classes, options)) {
            assertEquals(BYTECODE, input.getInputType());
            assertEquals(List.of(jar), input.getDependencies());
            var view = new JavaView(input);
            assertTrue(view.getClass(view.getIdentifierFactory().getClassType("Example")).orElseThrow().isApplicationClass());
            assertTrue(view.getClass(view.getIdentifierFactory().getClassType("dep.Helper")).orElseThrow().isLibraryClass());
            assertEquals(2, view.getClasses().count());
            assertTrue(body(input, "Example").contains("return 17"));
            assertTrue(body(input, "dep.Helper").contains("return 19"));
        }
        try (var input = new AutoCompileAnalysisInputLocation(List.of(classes, jar))) {
            assertTrue(new JavaView(input).getClasses().allMatch(clazz -> clazz.isApplicationClass()));
        }
        var interceptors = new ArrayList<BodyInterceptor>();
        try (var input = new AutoCompileAnalysisInputLocation(jar, InputLocationOptions.defaults(), SourceType.Library, interceptors)) {
            interceptors.add((builder, view) -> {});
            assertEquals(SourceType.Library, input.getSourceType());
            assertTrue(input.getBodyInterceptors().isEmpty());
            assertTrue(new JavaView(input).getClasses().allMatch(clazz -> clazz.isLibraryClass()));
            assertTrue(body(input, "dep.Helper").contains("return 19"));
            assertThrows(UnsupportedOperationException.class, () -> input.getInputs().clear());
        }
        assertTrue(Files.exists(classes.resolve("Example.class")));
        assertTrue(Files.exists(jar));
    }

    @Test
    void sourcesTakePrecedenceOverStaleBytecode() throws Exception {
        Path classes = fixture("stale", "Example.java", "public class Example { public int value() { return 1; } }");
        write(classes, "Example.java", "public class Example { public int value() { return 23; } }");
        try (var input = new AutoCompileAnalysisInputLocation(classes)) {
            assertEquals(JAVA_SOURCE, input.getInputType());
            assertTrue(body(input, "Example").contains("return 23"));
        }
    }

    @Test
    void sourceClasspathIsForAnalysisAndDoesNotChangeCompilation() throws Exception {
        Path library = fixture("dep", "Helper.java", "package dep; public class Helper {}");
        Path source = temporary.resolve("source");
        write(source, "Example.java", "public class Example { public int value() { return 29; } }");
        var options = new InputLocationOptions(InputMode.AUTO, List.of(library), List.of(), null);
        try (var input = new AutoCompileAnalysisInputLocation(source, options)) {
            var view = new JavaView(input);
            assertTrue(view.getClass(view.getIdentifierFactory().getClassType("dep.Helper")).orElseThrow().isLibraryClass());
        }
        write(source, "Example.java", "public class Example { dep.Helper field; }");
        assertTrue(assertThrows(AnalysisInputException.class,
                () -> new AutoCompileAnalysisInputLocation(source, options)).getMessage().contains("Java compilation failed"));
    }

    @Test
    void failuresAreExplicitAndAmbiguousBuildFilesRequireAChoice() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new AutoCompileAnalysisInputLocation(temporary.resolve("missing")));
        assertThrows(IllegalArgumentException.class, () -> new AutoCompileAnalysisInputLocation(temporary));
        assertThrows(IllegalArgumentException.class, () -> new AutoCompileAnalysisInputLocation(List.of()));
        write(temporary, "Broken.java", "public class Broken { syntax error }");
        assertTrue(assertThrows(AnalysisInputException.class,
                () -> new AutoCompileAnalysisInputLocation(temporary)).getMessage().contains("Broken.java"));
        write(temporary, "pom.xml", "not XML");
        write(temporary, "build.gradle.kts", "plugins { java }");
        assertThrows(IllegalArgumentException.class, () -> new AutoCompileAnalysisInputLocation(temporary));
        write(temporary, "Broken.java", "public class Broken { public int value() { return 31; } }");
        var javaSourceOptions = new InputLocationOptions(InputMode.JAVA_SOURCE, List.of(), List.of(), null);
        try (var input = new AutoCompileAnalysisInputLocation(temporary, javaSourceOptions)) {
            assertEquals(JAVA_SOURCE, input.getInputType());
            assertTrue(body(input, "Broken").contains("return 31"));
        }
        var maven = new InputLocationOptions(InputMode.MAVEN_PROJECT, List.of(), List.of("-o"), null);
        assertThrows(AnalysisInputException.class, () -> new AutoCompileAnalysisInputLocation(temporary, maven));
        Path gradle = Files.createDirectory(temporary.resolve("gradle"));
        write(gradle, "settings.gradle.kts", "");
        assertEquals(InputMode.GRADLE_PROJECT, ProjectCompiler.detect(gradle));
    }

    @Test
    void rejectsMixedInputsUnsupportedFilesAndInapplicableOptions() throws Exception {
        Path classes = fixture("bytecode", "Example.java", "public class Example {}");
        Path source = temporary.resolve("One.java");
        Files.writeString(source, "public class One {}");
        Path jar = jar(classes);
        assertThrows(IllegalArgumentException.class, () -> new AutoCompileAnalysisInputLocation(List.of(source, jar)));
        assertThrows(IllegalArgumentException.class, () -> new AutoCompileAnalysisInputLocation(classes.resolve("Example.class")));
        var badDependency = new InputLocationOptions(InputMode.AUTO, List.of(source), List.of(), null);
        assertThrows(IllegalArgumentException.class, () -> new AutoCompileAnalysisInputLocation(jar, badDependency));
        var badArguments = new InputLocationOptions(InputMode.AUTO, List.of(), List.of("--offline"), null);
        assertThrows(IllegalArgumentException.class, () -> new AutoCompileAnalysisInputLocation(source, badArguments));
        Path project = temporary.resolve("project");
        write(project, "pom.xml", "not XML");
        assertThrows(IllegalArgumentException.class, () -> new AutoCompileAnalysisInputLocation(List.of(project, source)));
    }

    @Test
    @Tag("integration")
    void mavenReactorUsesTemporaryOutputsAndRestoresGlobalState() throws Exception {
        Path root = temporary.resolve("maven project");
        write(root, "pom.xml", mavenRoot("<modules><module>lib</module><module>app</module></modules>"));
        write(root, "lib/pom.xml", mavenChild("lib", ""));
        write(root, "app/pom.xml", mavenChild("app", "<dependencies><dependency><groupId>sample</groupId><artifactId>lib</artifactId><version>1</version></dependency></dependencies>"));
        write(root, "lib/src/main/java/dep/Helper.java", "package dep; public class Helper { public static int value() { return 3; } }");
        write(root, "app/src/main/java/Example.java", "public class Example { public int value() { return dep.Helper.value(); } }");
        String previous = System.getProperty(MavenCli.MULTIMODULE_PROJECT_DIRECTORY);
        Path output;
        try (var compiled = new AutoCompileAnalysisInputLocation(root, mavenOptions())) {
            output = compiled.getClassOutputDirectory();
            assertEquals(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize(), output.getParent());
            assertEquals(MAVEN_PROJECT, compiled.getInputType());
            assertEquals(2, new JavaView(compiled).getClasses().count());
            assertTrue(Files.isRegularFile(output.resolve("group-sample/module-lib/dep/Helper.class")));
            assertTrue(Files.isRegularFile(output.resolve("group-sample/module-app/Example.class")));
            assertFalse(Files.exists(root.resolve("app/custom/classes/Example.class")));
            assertTrue(body(compiled, "Example").contains("dep.Helper"));
            assertTrue(body(compiled, "dep.Helper").contains("return 3"));
            assertTrue(compiled.getDependencies().isEmpty());
        }
        assertEquals(previous, System.getProperty(MavenCli.MULTIMODULE_PROJECT_DIRECTORY));
        assertTrue(Files.isRegularFile(output.resolve("group-sample/module-app/Example.class")));
        write(root, "app/src/main/java/Example.java", "invalid Java");
        assertThrows(AnalysisInputException.class, () -> new AutoCompileAnalysisInputLocation(root, mavenOptions()));
        assertEquals(previous, System.getProperty(MavenCli.MULTIMODULE_PROJECT_DIRECTORY));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Tag("integration")
    void mavenWritesClassesToCallerDirectory(boolean multiModule) throws Exception {
        Path root = temporary.resolve("maven");
        if (multiModule) {
            write(root, "pom.xml", mavenRoot("<modules><module>lib</module><module>app</module></modules>"));
            write(root, "lib/pom.xml", mavenChild("lib", ""));
            write(root, "app/pom.xml", mavenChild("app", "<dependencies><dependency><groupId>sample</groupId><artifactId>lib</artifactId><version>1</version></dependency></dependencies>"));
            write(root, "lib/src/main/java/dep/Helper.java", "package dep; public class Helper { public static int value() { return 47; } }");
        } else {
            write(root, "pom.xml", mavenRoot("").replace("<packaging>pom</packaging>", "<packaging>jar</packaging>"));
            write(root, "src/main/java/dep/Helper.java", "package dep; public class Helper { public static int value() { return 47; } }");
        }
        Path application = multiModule ? root.resolve("app") : root;
        // 显式插件参数也必须被覆盖，不能仅修改 MavenProject 的 build.outputDirectory。
        Path pom = root.resolve("pom.xml");
        String originalPom = Files.readString(pom).replace("<version>3.15.0</version>", """
                <version>3.15.0</version><configuration>
                <outputDirectory>${project.basedir}/plugin-output</outputDirectory>
                </configuration>
                """);
        Files.writeString(pom, originalPom);
        write(application, "src/main/java/Example.java", "public class Example { public int value() { return dep.Helper.value(); } }");
        write(application, "src/main/resources/mapper.xml", "<mapper/>");
        Path output = temporary.resolve("Maven 输出 classes");
        Path appOutput = multiModule ? output.resolve("group-sample/module-app") : output;
        Path libOutput = multiModule ? output.resolve("group-sample/module-lib") : output;
        try (var input = new AutoCompileAnalysisInputLocation(root, mavenOptions().withClassOutputDirectory(output))) {
            assertTrue(body(input, "Example").contains("dep.Helper"));
            assertTrue(body(input, "dep.Helper").contains("return 47"));
            assertTrue(input.getDependencies().isEmpty());
            assertEquals(2, new JavaView(input).getClasses().count());
        }
        assertTrue(Files.isRegularFile(appOutput.resolve("Example.class")));
        assertTrue(Files.isRegularFile(libOutput.resolve("dep/Helper.class")));
        assertEquals("<mapper/>", Files.readString(appOutput.resolve("mapper.xml")));
        assertFalse(Files.exists(application.resolve("custom/classes/Example.class")));
        assertFalse(Files.exists(application.resolve("plugin-output/Example.class")));
        assertEquals(originalPom, Files.readString(pom));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Tag("integration")
    void gradleWritesClassesToCallerDirectory(boolean multiModule) throws Exception {
        Path root = temporary.resolve("gradle");
        write(root, "settings.gradle", "rootProject.name = 'sample'\n" + (multiModule ? "include 'lib', 'app'\n" : ""));
        String script = multiModule ? """
                subprojects { apply plugin: 'java-library' }
                project(':app') { dependencies { implementation project(':lib') } }
                """ : "plugins { id 'java' }";
        write(root, "build.gradle", script);
        Path application = multiModule ? root.resolve("app") : root;
        Path library = multiModule ? root.resolve("lib") : root;
        write(application, "src/main/java/Example.java", "public class Example { public int value() { return dep.Helper.value(); } }");
        write(library, "src/main/java/dep/Helper.java", "package dep; public class Helper { public static int value() { return 53; } }");
        Path output = temporary.resolve("Gradle 输出 ' classes");
        Path appOutput = multiModule ? output.resolve("project-%3Aapp") : output;
        Path libOutput = multiModule ? output.resolve("project-%3Alib") : output;
        try (var input = new AutoCompileAnalysisInputLocation(root, gradleOptions().withClassOutputDirectory(output))) {
            assertTrue(body(input, "Example").contains("dep.Helper"));
            assertTrue(body(input, "dep.Helper").contains("return 53"));
            assertTrue(input.getDependencies().isEmpty());
            assertEquals(2, new JavaView(input).getClasses().count());
        }
        assertTrue(Files.isRegularFile(appOutput.resolve("Example.class")));
        assertTrue(Files.isRegularFile(libOutput.resolve("dep/Helper.class")));
        assertFalse(Files.exists(application.resolve("build/classes/java/main/Example.class")));
        assertEquals(script, Files.readString(root.resolve("build.gradle")));
    }

    @Test
    @Tag("integration")
    void gradleBuildsSubprojectsAndReadsActualSourceSetOutputs() throws Exception {
        Path root = temporary.resolve("gradle project");
        write(root, "settings.gradle", "rootProject.name = 'sample'\ninclude 'lib', 'app'\n");
        write(root, "build.gradle", """
                subprojects {
                    apply plugin: 'java-library'
                    layout.buildDirectory = file('custom-build')
                    sourceSets.main.java.srcDirs = ['sources']
                }
                project(':app') { dependencies { implementation project(':lib') } }
                """);
        write(root, "lib/sources/dep/Helper.java", "package dep; public class Helper { public static int value() { return 5; } }");
        write(root, "app/sources/Example.java", "public class Example { public int value() { return dep.Helper.value(); } }");
        try (var compiled = new AutoCompileAnalysisInputLocation(root, gradleOptions())) {
            Path output = compiled.getClassOutputDirectory();
            assertEquals(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize(), output.getParent());
            assertEquals(GRADLE_PROJECT, compiled.getInputType());
            assertEquals(2, new JavaView(compiled).getClasses().count());
            assertTrue(Files.isRegularFile(output.resolve("project-%3Alib/dep/Helper.class")));
            assertTrue(Files.isRegularFile(output.resolve("project-%3Aapp/Example.class")));
            assertFalse(Files.exists(root.resolve("app/custom-build/classes/java/main/Example.class")));
            assertTrue(body(compiled, "Example").contains("dep.Helper"));
            assertTrue(body(compiled, "dep.Helper").contains("return 5"));
            assertTrue(compiled.getDependencies().isEmpty());
        }
        write(root, "app/sources/Example.java", "invalid Java");
        assertThrows(AnalysisInputException.class, () -> new AutoCompileAnalysisInputLocation(root, gradleOptions()));
    }

    @Test
    @Tag("integration")
    void kotlinGradleBuildScriptAndExternalJarAreSupported() throws Exception {
        Path library = fixture("external", "Helper.java", "package dep; public class Helper { public static int value() { return 13; } }");
        Path root = temporary.resolve("gradle-kotlin");
        Path jar = Files.createDirectories(root.resolve("libs")).resolve("helper.jar");
        Files.copy(jar(library), jar);
        write(root, "settings.gradle.kts", "rootProject.name = \"kotlin-script\"\n");
        write(root, "build.gradle.kts", "plugins { java }\ndependencies { implementation(files(\"libs/helper.jar\")) }\n");
        write(root, "src/main/java/Example.java", "public class Example { public int value() { return dep.Helper.value(); } }");
        try (var compiled = new AutoCompileAnalysisInputLocation(root, gradleOptions())) {
            assertEquals(List.of(jar.toAbsolutePath()), compiled.getDependencies());
            assertTrue(body(compiled, "Example").contains("dep.Helper"));
            var view = new JavaView(compiled);
            assertTrue(view.getClass(view.getIdentifierFactory().getClassType("dep.Helper"))
                    .orElseThrow().isLibraryClass());
        }
    }

    private static InputLocationOptions mavenOptions() {
        return new InputLocationOptions(InputMode.AUTO, List.of(), List.of("-o"), null);
    }

    private static InputLocationOptions gradleOptions() {
        String installation = System.getProperty("jsqlint.test.gradle.home");
        return new InputLocationOptions(InputMode.AUTO, List.of(), List.of("--offline"),
                installation == null ? null : Path.of(installation));
    }

    private static String body(AutoCompileAnalysisInputLocation input, String className) {
        var view = input.createView();
        var clazz = view.getClass(view.getIdentifierFactory().getClassType(className)).orElseThrow();
        return clazz.getMethods().stream().filter(method -> method.getName().equals("value"))
                .findFirst().orElseThrow().getBody().toString();
    }

    private Path fixture(String name, String fileName, String content) throws IOException {
        Path root = temporary.resolve(name);
        write(root, "sources/" + fileName, content);
        Path output = Files.createDirectories(root.resolve("classes"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            manager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));
            manager.setLocationFromPaths(StandardLocation.CLASS_PATH, List.of());
            assertTrue(compiler.getTask(null, manager, null, List.of("--release", "17"), null,
                    manager.getJavaFileObjects(root.resolve("sources/" + fileName))).call());
        }
        return output;
    }

    private static Path jar(Path classes) throws IOException {
        Path jar = classes.resolveSibling("fixture.jar");
        try (var stream = new JarOutputStream(Files.newOutputStream(jar)); var paths = Files.walk(classes)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                stream.putNextEntry(new JarEntry(classes.relativize(path).toString().replace('\\', '/')));
                Files.copy(path, stream);
                stream.closeEntry();
            }
        }
        return jar;
    }

    private static void write(Path root, String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static String mavenRoot(String extra) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>sample</groupId><artifactId>parent</artifactId><version>1</version><packaging>pom</packaging>
                <properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                <build><outputDirectory>${project.basedir}/custom/classes</outputDirectory><plugins>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId><version>3.4.0</version></plugin>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.15.0</version></plugin>
                </plugins></build>
                """ + extra + "</project>";
    }

    private static String mavenChild(String name, String extra) {
        return "<project xmlns='http://maven.apache.org/POM/4.0.0'><modelVersion>4.0.0</modelVersion>"
                + "<parent><groupId>sample</groupId><artifactId>parent</artifactId><version>1</version></parent>"
                + "<artifactId>" + name + "</artifactId>" + extra + "</project>";
    }
}
