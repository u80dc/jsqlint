package org.u80dc.jsqlint.commons.compiler;

import com.sun.net.httpserver.BasicAuthenticator;
import com.sun.net.httpserver.HttpServer;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.io.xpp3.SettingsXpp3Reader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.u80dc.jsqlint.commons.location.*;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.node.SqlNode;
import org.u80dc.jsqlint.core.node.SqlNodeRenderer;
import org.u80dc.jsqlint.core.spi.AnalysisSeed;
import org.u80dc.jsqlint.core.statistics.StatisticsRecorder;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** 使用本地 HTTP 私服验证认证、父 POM、插件和依赖下载，以及仓库配置的调用隔离。 @author u80dc */
@Isolated("Maven Embedder 和临时目录属性属于进程共享资源")
class MavenProjectCompilerTest {
    @TempDir Path temporary;
    private String previousTemporary;

    @BeforeEach
    void useOwnTemporaryDirectory() throws IOException {
        previousTemporary = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", Files.createDirectories(temporary.resolve("tmp")).toString());
    }

    @AfterEach
    void restoreTemporaryDirectory() {
        if (previousTemporary == null) System.clearProperty("java.io.tmpdir");
        else System.setProperty("java.io.tmpdir", previousTemporary);
    }

    @Test
    void defaultsUseOfficialCentralForDependenciesAndPluginsAndRemoveSettings() throws Exception {
        Path settingsPath;
        Path globalPath;
        try (var generated = MavenBuildSettings.create(MavenRepositoryOptions.defaults())) {
            settingsPath = Path.of(generated.arguments().get(1));
            globalPath = Path.of(generated.arguments().get(3));
            Settings settings = read(settingsPath);
            assertEquals(Path.of(System.getProperty("user.home"), ".m2/repository").toAbsolutePath().normalize(),
                    Path.of(settings.getLocalRepository()));
            var profile = settings.getProfiles().getFirst();
            assertEquals(List.of(profile.getId()), settings.getActiveProfiles());
            for (var repositories : List.of(profile.getRepositories(), profile.getPluginRepositories())) {
                assertEquals(1, repositories.size());
                assertEquals("central", repositories.getFirst().getId());
                assertEquals("https://repo.maven.apache.org/maven2", repositories.getFirst().getUrl());
                assertTrue(repositories.getFirst().getReleases().isEnabled());
                assertFalse(repositories.getFirst().getSnapshots().isEnabled());
            }
            assertTrue(settings.getMirrors().isEmpty());
            assertTrue(settings.getServers().isEmpty());
            assertTrue(read(globalPath).getProfiles().isEmpty());
        }
        assertFalse(Files.exists(settingsPath));
        assertFalse(Files.exists(globalPath));
        assertFalse(Files.exists(settingsPath.getParent()));
    }

    @Test
    void privatePoliciesCredentialsAndLocalPathSurviveOutputOverride() throws Exception {
        var remote = new MavenRemoteRepository("private", URI.create("https://repo.example.test/maven/"))
                .withSnapshotsEnabled(true).withReleasesEnabled(false)
                .withCredentials("test<&user", "test<&password").withMirrorOf("*,!other");
        var list = new ArrayList<>(List.of(remote));
        var repositories = new MavenRepositoryOptions(list, temporary.resolve("local 仓库"));
        list.clear();
        var options = InputLocationOptions.defaults().withMavenRepositories(repositories)
                .withClassOutputDirectory(temporary.resolve("classes"));
        assertSame(repositories, options.mavenRepositories());
        assertFalse(options.toString().contains("test<&password"));
        try (var generated = MavenBuildSettings.create(options.mavenRepositories())) {
            var settings = read(Path.of(generated.arguments().get(1)));
            var profile = settings.getProfiles().getFirst();
            assertEquals(List.of("private", "central"), profile.getRepositories().stream().map(r -> r.getId()).toList());
            assertFalse(profile.getRepositories().getFirst().getReleases().isEnabled());
            assertTrue(profile.getPluginRepositories().getFirst().getSnapshots().isEnabled());
            assertEquals("test<&password", settings.getServers().getFirst().getPassword());
            assertEquals("test<&user", settings.getServers().getFirst().getUsername());
            assertEquals("private", settings.getServers().getFirst().getId());
            assertEquals("*,!other", settings.getMirrors().getFirst().getMirrorOf());
            assertEquals(repositories.localRepository(), Path.of(settings.getLocalRepository()));
        }
        assertTrue(Files.isDirectory(repositories.localRepository()));
    }

    @Test
    void invalidRepositoriesAndConflictingRawArgumentsFailEarly() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new MavenRemoteRepository(" ", URI.create("https://repo.example.test")));
        assertThrows(IllegalArgumentException.class, () -> new MavenRemoteRepository("repo", URI.create("https://user:secret@repo.example.test")));
        assertThrows(IllegalArgumentException.class, () -> new MavenRemoteRepository("repo", URI.create("relative/path")));
        var remote = new MavenRemoteRepository("repo", URI.create("https://repo.example.test"));
        assertThrows(IllegalArgumentException.class, () -> remote.withCredentials("user", null));
        assertThrows(IllegalArgumentException.class, () -> remote.withReleasesEnabled(false));
        assertThrows(IllegalArgumentException.class, () -> new MavenRepositoryOptions(List.of(remote, remote), null));
        Path root = Files.createDirectories(temporary.resolve("project"));
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        for (var arguments : List.of(List.of("-s", "custom.xml"), List.of("--global-settings=x"),
                List.of("-Dmaven.repo.local=elsewhere"), List.of("--define", "maven.repo.local=elsewhere"))) {
            var options = new InputLocationOptions(InputMode.MAVEN_PROJECT, List.of(), arguments, null);
            assertThrows(IllegalArgumentException.class, () -> MavenProjectCompiler.compile(root, options));
        }
        assertNoTemporarySettings();
    }

    @Test
    void authenticatedRepositoryBuildsIntoIsolatedLocalCacheAndWorksOffline() throws Exception {
        Path remote = Files.createDirectories(temporary.resolve("remote"));
        publishFixtures(remote);
        Path cachedArtifacts = Path.of(System.getProperty("maven.repo.local",
                Path.of(System.getProperty("user.home"), ".m2/repository").toString())).toAbsolutePath().normalize();
        // 已有缓存仅作为测试 HTTP 服务的插件文件来源；被测 Maven 使用全新的本地仓库。
        assertTrue(Files.isRegularFile(cachedArtifacts.resolve("org/apache/maven/plugins/maven-compiler-plugin/3.15.0/maven-compiler-plugin-3.15.0.jar")));
        var requests = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var context = server.createContext("/", exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getPath().substring(1);
                requests.add(path);
                byte[] bytes = repositoryFile(remote, path);
                if (bytes == null) bytes = repositoryFile(cachedArtifacts, path);
                if (bytes == null) { exchange.sendResponseHeaders(404, -1); return; }
                if (exchange.getRequestMethod().equals("HEAD")) {
                    exchange.getResponseHeaders().set("Content-Length", String.valueOf(bytes.length));
                    exchange.sendResponseHeaders(200, -1);
                } else {
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            }
        });
        context.setAuthenticator(new BasicAuthenticator("test-repository") {
            public boolean checkCredentials(String username, String password) {
                return username.equals("test-user") && password.equals("test<&password");
            }
        });
        server.start();
        var repository = new MavenRemoteRepository("private", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"))
                .withCredentials("test-user", "test<&password").withMirrorOf("*");
        Path local = temporary.resolve("local repository");
        var repositories = MavenRepositoryOptions.defaults().withRemoteRepositories(List.of(repository)).withLocalRepository(local);
        Path project = Files.createDirectories(temporary.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <parent><groupId>org.u80dc.fixture.repo</groupId><artifactId>parent</artifactId><version>1</version><relativePath/></parent>
                <artifactId>application</artifactId>
                <dependencies><dependency><groupId>org.u80dc.fixture.repo</groupId><artifactId>library</artifactId><version>1</version></dependency></dependencies>
                </project>
                """);
        Path source = Files.createDirectories(project.resolve("src/main/java"));
        Files.writeString(source.resolve("Example.java"), """
                public class Example {
                    public int value() { return fixture.Library.value(); }
                    public void query(java.sql.Connection connection) throws Exception { connection.prepareStatement("SELECT 1"); }
                }
                """);
        var options = InputLocationOptions.defaults().withMavenRepositories(repositories)
                .withClassOutputDirectory(temporary.resolve("compiled"));
        try {
            List<String> sql = new ArrayList<>();
            List<String> diagnostics = new ArrayList<>();
            var listener = new AnalysisListener() {
                public void onSql(String module, AnalysisSeed seed, SqlNode template, SqlNodeRenderer.RenderedSql result) {
                    result.sql().ifPresent(sql::add);
                }
                public void onDiagnostic(String code, String message) { diagnostics.add(code + ": " + message); }
            };
            // 用户只构造配置并交给 Analyzer，真实 ServiceLoader 链路完成仓库传递、编译和 SQL 分析。
            var analyzer = new Analyzer(AnalyzerConfig.defaults().withInputOptions(options));
            var result = analyzer.analyze(project, AnalysisOptions.defaults().withClassOutputId("maven-project")
                    .withListener(listener).withStatistics(true));
            assertEquals(AnalysisResult.Status.SUCCESS, result.status(), () -> String.join("\n", diagnostics));
            assertCompilationAccounting(result.statistics().orElseThrow());
            assertEquals(List.of("SELECT 1"), sql);
            assertTrue(Files.isRegularFile(temporary.resolve("tmp/.jsqlint/maven-project/Example.class")));
            assertFalse(Files.exists(options.classOutputDirectory()));
            assertTrue(Files.isRegularFile(local.resolve("org/u80dc/fixture/repo/library/1/library-1.jar")));
            assertTrue(requests.contains("org/u80dc/fixture/repo/parent/1/parent-1.pom"));
            assertTrue(requests.contains("org/u80dc/fixture/repo/library/1/library-1.jar"));
            assertTrue(requests.contains("org/u80dc/fixture/repo/legacy-plugin/1/legacy-plugin-1.jar"));
            assertTrue(requests.contains("org/apache/maven/plugins/maven-compiler-plugin/3.15.0/maven-compiler-plugin-3.15.0.jar"));
            assertTrue(Files.readString(project.resolve("legacy-compatibility.txt")).contains("DefaultArtifactCollector"));
            assertNoTemporarySettings();
        } finally { server.stop(0); }

        Files.delete(project.resolve("legacy-compatibility.txt"));
        var offline = new InputLocationOptions(InputMode.MAVEN_PROJECT, List.of(), List.of("-o"), null)
                .withMavenRepositories(repositories).withClassOutputDirectory(temporary.resolve("offline-classes"));
        try (var input = new AutoCompileAnalysisInputLocation(project, offline)) {
            assertTrue(Files.isRegularFile(offline.classOutputDirectory().resolve("Example.class")));
            assertTrue(Files.readString(project.resolve("legacy-compatibility.txt")).contains("DefaultArtifactCollector"));
            assertEquals(List.of(local.resolve("org/u80dc/fixture/repo/library/1/library-1.jar")), input.getDependencies());
            var view = input.createView();
            var type = view.getClass(view.getIdentifierFactory().getClassType("Example")).orElseThrow();
            assertTrue(type.getMethods().stream().filter(method -> method.getName().equals("value"))
                    .findFirst().orElseThrow().getBody().toString().contains("fixture.Library"));
        }
        // 下一次调用指定其他本地仓库，不能泄漏上一次调用的缓存配置。
        var missingCache = offline.withMavenRepositories(repositories.withLocalRepository(temporary.resolve("empty-cache")));
        var failure = assertThrows(AnalysisInputException.class, () -> new AutoCompileAnalysisInputLocation(project, missingCache));
        assertFalse(failure.getMessage().contains("test<&password"));
        assertNoTemporarySettings();
        assertTrue(Files.isRegularFile(local.resolve("org/u80dc/fixture/repo/library/1/library-1.jar")));
    }

    @Test
    void failedBuildRetainsOriginalPomErrorAndReleasesTemporarySettings() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("broken-project"));
        Files.writeString(project.resolve("pom.xml"), "<project><broken>");
        var options = new InputLocationOptions(InputMode.MAVEN_PROJECT, List.of(), List.of("-o"), null)
                .withMavenRepositories(MavenRepositoryOptions.defaults().withLocalRepository(temporary.resolve("empty-cache")));
        var originalOut = System.out;
        var originalError = System.err;
        var recorder = StatisticsRecorder.create(AnalysisStatisticsOptions.timings());
        var failure = assertThrows(AnalysisInputException.class, () -> MavenProjectCompiler.compile(project, options, recorder));
        assertCompilationAccounting(recorder.snapshot().orElseThrow());
        assertTrue(failure.getMessage().contains("Maven compilation failed"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Non-readable POM"), failure.getMessage());
        assertTrue(failure.getMessage().contains("pom.xml"), failure.getMessage());
        assertSame(originalOut, System.out);
        assertSame(originalError, System.err);
        assertNoTemporarySettings();
    }

    private static void assertCompilationAccounting(AnalysisStatistics statistics) {
        for (var phase : List.of(AnalysisStatistics.Phase.COMPILATION, AnalysisStatistics.Phase.WAITING,
                AnalysisStatistics.Phase.CLEANUP)) {
            assertNotNull(statistics.phases().get(phase), "实际构建应记录 " + phase);
            assertTrue(statistics.phases().get(phase).invocations() > 0);
        }
        assertEquals(statistics.elapsedNanos(), statistics.phases().values().stream()
                .mapToLong(AnalysisStatistics.PhaseCost::selfNanos).sum());
        assertTrue(statistics.measurements().isEmpty(), "编译资源必须在统计冻结前正常关闭");
    }

    private void publishFixtures(Path repository) throws Exception {
        Path parent = Files.createDirectories(repository.resolve("org/u80dc/fixture/repo/parent/1"));
        Files.writeString(parent.resolve("parent-1.pom"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>org.u80dc.fixture.repo</groupId><artifactId>parent</artifactId><version>1</version><packaging>pom</packaging>
                <properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                <build><plugins>
                <plugin><groupId>org.u80dc.fixture.repo</groupId><artifactId>legacy-plugin</artifactId><version>1</version>
                <executions><execution><phase>initialize</phase><goals><goal>check</goal></goals></execution></executions></plugin>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId><version>3.4.0</version></plugin>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.15.0</version></plugin>
                </plugins></build></project>
                """);
        Path library = Files.createDirectories(repository.resolve("org/u80dc/fixture/repo/library/1"));
        Files.writeString(library.resolve("library-1.pom"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>org.u80dc.fixture.repo</groupId><artifactId>library</artifactId><version>1</version></project>
                """);
        Path java = temporary.resolve("Library.java");
        Files.writeString(java, "package fixture; public class Library { public static int value() { return 7; } }");
        Path classes = Files.createDirectories(temporary.resolve("fixture-classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-proc:none", "-d", classes.toString(), java.toString()));
        try (var jar = new JarOutputStream(Files.newOutputStream(library.resolve("library-1.jar")))) {
            jar.putNextEntry(new JarEntry("fixture/Library.class"));
            Files.copy(classes.resolve("fixture/Library.class"), jar);
            jar.closeEntry();
        }
        publishLegacyPlugin(repository);
    }

    private void publishLegacyPlugin(Path repository) throws Exception {
        Path plugin = Files.createDirectories(repository.resolve("org/u80dc/fixture/repo/legacy-plugin/1"));
        Files.writeString(plugin.resolve("legacy-plugin-1.pom"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>org.u80dc.fixture.repo</groupId><artifactId>legacy-plugin</artifactId><version>1</version>
                <packaging>maven-plugin</packaging></project>
                """);
        Path source = temporary.resolve("LegacyMojo.java");
        Files.writeString(source, """
                package fixture;
                public final class LegacyMojo extends org.apache.maven.plugin.AbstractMojo {
                    private org.apache.maven.artifact.resolver.ArtifactCollector artifactCollector;
                    private java.io.File basedir;
                    public void execute() throws org.apache.maven.plugin.MojoExecutionException {
                        if (artifactCollector == null) throw new org.apache.maven.plugin.MojoExecutionException("Missing legacy ArtifactCollector");
                        try {
                            java.nio.file.Files.writeString(basedir.toPath().resolve("legacy-compatibility.txt"), artifactCollector.getClass().getName());
                        } catch (java.io.IOException error) { throw new org.apache.maven.plugin.MojoExecutionException("Cannot write marker", error); }
                    }
                }
                """);
        Path classes = Files.createDirectories(temporary.resolve("plugin-classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none",
                "-classpath", System.getProperty("java.class.path"), "-d", classes.toString(), source.toString()));
        String descriptor = """
                <plugin><name>Legacy compatibility fixture</name>
                  <groupId>org.u80dc.fixture.repo</groupId><artifactId>legacy-plugin</artifactId><version>1</version>
                  <goalPrefix>legacy</goalPrefix><mojos><mojo>
                    <goal>check</goal><implementation>fixture.LegacyMojo</implementation><language>java</language>
                    <description>Checks legacy ArtifactCollector injection.</description><instantiationStrategy>per-lookup</instantiationStrategy>
                    <parameters><parameter><name>basedir</name><type>java.io.File</type><required>true</required><editable>false</editable></parameter></parameters>
                    <configuration><basedir implementation="java.io.File" default-value="${project.basedir}"/></configuration>
                    <requirements><requirement><role>org.apache.maven.artifact.resolver.ArtifactCollector</role><field-name>artifactCollector</field-name></requirement></requirements>
                  </mojo></mojos><dependencies/>
                </plugin>
                """;
        try (var jar = new JarOutputStream(Files.newOutputStream(plugin.resolve("legacy-plugin-1.jar")))) {
            jar.putNextEntry(new JarEntry("fixture/LegacyMojo.class"));
            Files.copy(classes.resolve("fixture/LegacyMojo.class"), jar);
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("META-INF/maven/plugin.xml"));
            jar.write(descriptor.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    private static byte[] repositoryFile(Path root, String name) throws IOException {
        Path file = root.resolve(name).normalize();
        if (!file.startsWith(root)) return null;
        if (Files.isRegularFile(file)) return Files.readAllBytes(file);
        if (name.endsWith(".sha1")) {
            byte[] content = repositoryFile(root, name.substring(0, name.length() - 5));
            if (content != null) {
                try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(content)).getBytes(StandardCharsets.US_ASCII); }
                catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
            }
        }
        return null;
    }

    private static Settings read(Path path) throws Exception {
        try (var reader = Files.newBufferedReader(path)) { return new SettingsXpp3Reader().read(reader); }
    }

    private void assertNoTemporarySettings() throws IOException {
        try (var paths = Files.list(temporary.resolve("tmp"))) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().startsWith("jsqlint-maven-")));
        }
    }
}
