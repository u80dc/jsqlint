package org.u80dc.jsqlint.commons.location;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.commons.compiler.CompiledProject;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.core.resource.PathResourceSource;
import org.u80dc.jsqlint.mybatis.MybatisAnalysisModule;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.DefaultRuntimeAnalysisInputLocation;
import sootup.java.core.views.JavaView;

import javax.tools.ToolProvider;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** 真实两个应用模块和独立依赖/XML，仅读取已生成产物；不启动 Maven 或目标方法。 */
class ProjectInputsTest {
    private static final String SIGNATURE = "<cachedproject.Entry: int run(cachedproject.Repository$UserMapper,dep.Marker)>";
    private static final String SQL = "SELECT id FROM cached_users WHERE id=?";
    private static final String XML = "<mapper namespace='cachedproject.Repository$UserMapper'><select id='select'>"
            + "SELECT id FROM cached_users WHERE id=#{id}</select></mapper>";
    @TempDir Path temporary;

    @Test
    void keepsAllOwnModulesAsApplicationAndRestoresXmlWithoutOriginalSources() throws Exception {
        Fixture fixture = fixture();
        var project = new CompiledProject(temporary.resolve("source-directory-that-does-not-exist"), InputMode.MAVEN_PROJECT,
                List.of(fixture.entry(), fixture.repository(), fixture.resources()), fixture.dependencies(),
                List.of(fixture.resources(), fixture.configuration()));
        assertFalse(Files.exists(project.directory()));
        byte[] before = Files.readAllBytes(fixture.library());
        try (var prepared = ProjectInputs.open(project)) {
            assertEquals(1, prepared.locations().stream().filter(DefaultRuntimeAnalysisInputLocation.class::isInstance).count());
            assertEquals(List.of(SourceType.Application, SourceType.Library, SourceType.Library),
                    prepared.locations().stream().map(location -> location.getSourceType()).toList());
            assertEquals(List.of(fixture.resources(), fixture.configuration()), prepared.resources().stream()
                    .map(source -> ((PathResourceSource) source).path()).toList());
            var view = new JavaView(new ArrayList<>(prepared.locations()));
            assertTrue(view.getClass(view.getIdentifierFactory().getClassType("cachedproject.Entry")).orElseThrow().isApplicationClass());
            assertTrue(view.getClass(view.getIdentifierFactory().getClassType("cachedproject.Repository")).orElseThrow().isApplicationClass());
            assertTrue(view.getClass(view.getIdentifierFactory().getClassType("dep.Marker")).orElseThrow().isLibraryClass());
            var analyzer = analyzer();
            var request = new AnalysisRequest(prepared.locations(), prepared.resources(), AnalysisOptions.defaults().withCleanupClassOutput(true));
            for (int i = 0; i < 2; i++) verify(analyzer.analyze(request, SIGNATURE));
            // AnalysisRequest 不接管输入，第一次分析后仍可从同一输入解析真实库类型。
            assertTrue(new JavaView(new ArrayList<>(prepared.locations())).getClass(view.getIdentifierFactory().getClassType("dep.Marker")).isPresent());
        }
        // 旧四参数描述默认把所有输出根作为资源来源，资源-only 模块仍不成为 Application。
        var legacy = new CompiledProject(project.directory(), project.inputMode(), project.classDirectories(), project.dependencies());
        try (var prepared = ProjectInputs.open(legacy)) {
            verify(analyzer().analyze(new AnalysisRequest(prepared.locations(), prepared.resources()), SIGNATURE));
        }
        assertArrayEquals(before, Files.readAllBytes(fixture.library()));
        assertTrue(Files.isRegularFile(fixture.entry().resolve("cachedproject/Entry.class")));
        assertTrue(Files.isRegularFile(fixture.repository().resolve("cachedproject/Repository.class")));
        assertTrue(Files.isRegularFile(fixture.resources().resolve("mapper.xml")));
    }

    @Test
    void opensOrdinaryApplicationJarAndDoesNotDuplicateRuntimeForPathResolver() throws Exception {
        Fixture fixture = fixture();
        Path repositoryJar = jar(fixture.repository(), temporary.resolve("repository.jar"), "mapper/users.xml", XML);
        var project = new CompiledProject(temporary.resolve("missing-source"), InputMode.MAVEN_PROJECT,
                List.of(fixture.entry(), repositoryJar), fixture.dependencies(), List.of(repositoryJar, fixture.configuration()));
        try (var prepared = ProjectInputs.open(project, false)) {
            assertEquals(List.of(SourceType.Application, SourceType.Library),
                    prepared.locations().stream().map(location -> location.getSourceType()).toList());
            assertTrue(prepared.locations().stream().noneMatch(DefaultRuntimeAnalysisInputLocation.class::isInstance));
            var sources = new ArrayList<sootup.core.inputlocation.AnalysisInputLocation>(prepared.locations());
            try (var runtime = new DefaultRuntimeAnalysisInputLocation()) {
                sources.add(runtime);
                var result = analyzer().analyze(new AnalysisRequest(sources, prepared.resources()), SIGNATURE);
                verify(result);
                assertEquals("jar", result.calls().getFirst().statements().getFirst().definition().orElseThrow().location().resource().getScheme());
            }
        }
        assertTrue(Files.isRegularFile(repositoryJar));
    }

    @Test
    void rejectsMissingUnknownAndBootInputsWithoutDeletingGoodArtifacts() throws Exception {
        Fixture fixture = fixture();
        Path unknown = Files.writeString(temporary.resolve("unknown.bin"), "not a classpath");
        Path corrupt = Files.writeString(temporary.resolve("corrupt.jar"), "not a zip");
        Path boot = temporary.resolve("boot.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(boot))) {
            output.putNextEntry(new JarEntry("BOOT-INF/classes/cachedproject/Entry.class"));
            Files.copy(fixture.entry().resolve("cachedproject/Entry.class"), output);output.closeEntry();
        }
        var invalid = List.of(
                new CompiledProject(temporary, InputMode.AUTO, List.of(temporary.resolve("missing-classes")), fixture.dependencies(), List.of(fixture.resources())),
                new CompiledProject(temporary, InputMode.AUTO, List.of(unknown), fixture.dependencies(), List.of(fixture.resources())),
                new CompiledProject(temporary, InputMode.AUTO, List.of(fixture.entry()), List.of(temporary.resolve("missing.jar")), List.of(fixture.resources())),
                new CompiledProject(temporary, InputMode.AUTO, List.of(fixture.entry()), List.of(unknown), List.of(fixture.resources())),
                new CompiledProject(temporary, InputMode.AUTO, List.of(fixture.entry()), List.of(corrupt), List.of(fixture.resources())),
                new CompiledProject(temporary, InputMode.AUTO, List.of(fixture.entry()), fixture.dependencies(), List.of(temporary.resolve("missing.xml"))),
                new CompiledProject(temporary, InputMode.AUTO, List.of(fixture.resources()), fixture.dependencies(), List.of(fixture.resources())),
                new CompiledProject(temporary, InputMode.AUTO, List.of(boot), fixture.dependencies(), List.of(fixture.resources())));
        byte[] application = Files.readAllBytes(fixture.entry().resolve("cachedproject/Entry.class"));
        byte[] library = Files.readAllBytes(fixture.library());
        for (var project : invalid) assertThrows(AnalysisInputException.class, () -> ProjectInputs.open(project));
        assertArrayEquals(application, Files.readAllBytes(fixture.entry().resolve("cachedproject/Entry.class")));
        assertArrayEquals(library, Files.readAllBytes(fixture.library()));
        assertTrue(Files.isRegularFile(fixture.resources().resolve("mapper.xml")));
    }

    private Fixture fixture() throws Exception {
        Path libraryClasses = compile("library", "Marker.java", "package dep; public class Marker {}", List.of());
        Path library = jar(libraryClasses, temporary.resolve("library.jar"), null, null);
        // 未被引用的损坏类用于确认 Library 没有被当成应用代码全量解析。
        Path libraryWithUnused = temporary.resolve("library-with-unused.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(libraryWithUnused))) {
            output.putNextEntry(new JarEntry("dep/Marker.class"));Files.copy(libraryClasses.resolve("dep/Marker.class"), output);output.closeEntry();
            output.putNextEntry(new JarEntry("dep/UnusedBroken.class"));output.write(new byte[]{0, 1, 2, 3});output.closeEntry();
        }
        Path repository = compile("repository", "Repository.java", """
                package cachedproject;
                public class Repository {
                    public interface UserMapper { int select(@org.apache.ibatis.annotations.Param("id") long id); }
                    public static int query(UserMapper mapper,dep.Marker marker) { return mapper.select(7L); }
                }
                """, List.of(library));
        Path entry = compile("entry", "Entry.java", """
                package cachedproject;
                public class Entry {
                    public static int run(Repository.UserMapper mapper,dep.Marker marker) { return Repository.query(mapper,marker); }
                }
                """, List.of(library, repository));
        Path resources = Files.createDirectories(temporary.resolve("resource-only-module"));
        Files.writeString(resources.resolve("mapper.xml"), XML);
        Files.write(resources.resolve("module-info.class"), new byte[]{0, 1, 2, 3});
        Path configuration = Files.writeString(temporary.resolve("application.properties"), "mybatis-plus.global-config.db-config.table-prefix=cached_\n");
        Path mybatis = Path.of(org.apache.ibatis.annotations.Param.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return new Fixture(entry, repository, resources, configuration, libraryWithUnused, List.of(libraryWithUnused, mybatis));
    }

    private Path compile(String name, String fileName, String source, List<Path> dependencies) throws Exception {
        Path root = Files.createDirectories(temporary.resolve(name));
        Path file = Files.writeString(root.resolve(fileName), source);
        Path classes = Files.createDirectories(root.resolve("classes"));
        String classpath = System.getProperty("java.class.path") + File.pathSeparator
                + String.join(File.pathSeparator, dependencies.stream().map(Path::toString).toList());
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-parameters",
                "-classpath", classpath, "-d", classes.toString(), file.toString()));
        return classes;
    }

    private static Path jar(Path classes, Path file, String resourceName, String resourceText) throws Exception {
        try (var output = new JarOutputStream(Files.newOutputStream(file));var paths = Files.walk(classes)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                output.putNextEntry(new JarEntry(classes.relativize(path).toString().replace('\\', '/')));
                Files.copy(path, output);output.closeEntry();
            }
            if (resourceName != null) {
                output.putNextEntry(new JarEntry(resourceName));output.write(resourceText.getBytes(java.nio.charset.StandardCharsets.UTF_8));output.closeEntry();
            }
        }
        return file;
    }

    private static Analyzer analyzer() {
        var config = AnalyzerConfig.defaults().withInferenceOptions(InferenceOptions.defaults().withMode(InferenceOptions.Mode.JOINT));
        return new Analyzer(config, List.of(new MybatisAnalysisModule()), List.of());
    }
    private static void verify(AnalysisResult result) {
        assertEquals(AnalysisResult.Status.SUCCESS, result.status(), result.diagnostics().toString());
        assertEquals(1, result.calls().size(), result.diagnostics().toString());
        var call = result.calls().getFirst();
        assertEquals("cachedproject.Repository", call.callSite().caller().orElseThrow().getDeclClassType().toString());
        assertTrue(call.callChain().stream().anyMatch(site -> site.caller().map(method -> method.getDeclClassType().toString().equals("cachedproject.Entry")).orElse(false)));
        var sample = call.samples().getFirst();
        assertEquals(SQL, sample.sql().orElseThrow().replaceAll("\\s+", " ").strip());
        assertEquals(List.of(new SqlValue.Known(7L)), sample.bindings().stream().map(SqlSample.Binding::value).toList());
    }
    private record Fixture(Path entry, Path repository, Path resources, Path configuration, Path library, List<Path> dependencies) {}
}
