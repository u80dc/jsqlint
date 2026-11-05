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
        Files.writeString(source.resolve("Example.java"), "public class Example { public int value() { return fixture.Library.value(); } }");
        var options = InputLocationOptions.defaults().withMavenRepositories(repositories)
                .withClassOutputDirectory(temporary.resolve("compiled"));
        try {
            try (var input = new AutoCompileAnalysisInputLocation(project, options)) {
                assertTrue(Files.isRegularFile(options.classOutputDirectory().resolve("Example.class")));
                assertEquals(List.of(local.resolve("org/u80dc/fixture/repo/library/1/library-1.jar")), input.getDependencies());
                var view = input.createView();
                var type = view.getClass(view.getIdentifierFactory().getClassType("Example")).orElseThrow();
                assertTrue(type.getMethods().stream().filter(method -> method.getName().equals("value"))
                        .findFirst().orElseThrow().getBody().toString().contains("fixture.Library"));
            }
            assertTrue(requests.contains("org/u80dc/fixture/repo/parent/1/parent-1.pom"));
            assertTrue(requests.contains("org/u80dc/fixture/repo/library/1/library-1.jar"));
            assertTrue(requests.contains("org/apache/maven/plugins/maven-compiler-plugin/3.15.0/maven-compiler-plugin-3.15.0.jar"));
            assertNoTemporarySettings();
        } finally { server.stop(0); }

        var offline = new InputLocationOptions(InputMode.MAVEN_PROJECT, List.of(), List.of("-o"), null)
                .withMavenRepositories(repositories).withClassOutputDirectory(temporary.resolve("offline-classes"));
        try (var input = new AutoCompileAnalysisInputLocation(project, offline)) {
            assertTrue(Files.isRegularFile(offline.classOutputDirectory().resolve("Example.class")));
            assertEquals(1, input.getDependencies().size());
        }
        // 下一次调用指定其他本地仓库，不能泄漏上一次调用的缓存配置。
        var missingCache = offline.withMavenRepositories(repositories.withLocalRepository(temporary.resolve("empty-cache")));
        var failure = assertThrows(AnalysisInputException.class, () -> new AutoCompileAnalysisInputLocation(project, missingCache));
        assertFalse(failure.getMessage().contains("test<&password"));
        assertNoTemporarySettings();
        assertTrue(Files.isRegularFile(local.resolve("org/u80dc/fixture/repo/library/1/library-1.jar")));
    }

    private void publishFixtures(Path repository) throws Exception {
        Path parent = Files.createDirectories(repository.resolve("org/u80dc/fixture/repo/parent/1"));
        Files.writeString(parent.resolve("parent-1.pom"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>org.u80dc.fixture.repo</groupId><artifactId>parent</artifactId><version>1</version><packaging>pom</packaging>
                <properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                <build><plugins>
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
