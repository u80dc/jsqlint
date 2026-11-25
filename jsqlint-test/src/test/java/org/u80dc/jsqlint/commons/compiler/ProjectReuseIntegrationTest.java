package org.u80dc.jsqlint.commons.compiler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.u80dc.jsqlint.commons.location.*;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.core.spi.AnalysisInputContext;
import org.u80dc.jsqlint.core.statistics.StatisticsRecorder;
import org.u80dc.jsqlint.jdbc.JdbcAnalysisModule;
import sootup.java.core.views.JavaView;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 真实构建只发生于 cold/变更，warm 与 REUSE 读取同一快照；不以速度阈值判断缓存。 */
@Tag("integration")
@Isolated("Maven Embedder、Gradle 和 java.io.tmpdir 使用进程状态")
class ProjectReuseIntegrationTest {
    private static final String ENTRY = "<reuse.App: void run(java.sql.Connection)>";
    private static final String SQL = "SELECT id FROM users WHERE id=?";
    private static final String MAPPER_XML = "<mapper namespace='reuse.UnusedMapper'><select id='find'>SELECT 1</select></mapper>";
    @TempDir Path temporary;
    private String previousTemporary;

    @BeforeEach void temporaryDirectory() throws Exception {
        previousTemporary = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", Files.createDirectories(temporary.resolve("system-temp")).toString());
    }
    @AfterEach void restoreTemporaryDirectory() {
        if (previousTemporary == null) System.clearProperty("java.io.tmpdir");
        else System.setProperty("java.io.tmpdir", previousTemporary);
    }

    @Test
    void mavenColdWarmReuseAndExternalManifestKeepSqlAndBorrowedOutputs() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("maven"));
        Path library = library(root.resolve("third-party.jar"));
        write(root, "pom.xml", """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                  <groupId>reuse.fixture</groupId><artifactId>reactor</artifactId><version>1</version><packaging>pom</packaging>
                  <modules><module>helper</module><module>app</module></modules>
                  <properties><maven.compiler.release>8</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                  <build>
                    <resources><resource><directory>src/main/resources</directory><targetPath>${project.build.outputDirectory}</targetPath></resource></resources>
                    <plugins>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId><version>3.4.0</version></plugin>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.15.0</version><configuration><parameters>true</parameters><proc>none</proc></configuration></plugin>
                  </plugins></build>
                </project>
                """);
        write(root, "helper/pom.xml", module("helper", """
                <dependency><groupId>fixture.external</groupId><artifactId>marker</artifactId><version>1</version><scope>system</scope><systemPath>%s</systemPath></dependency>
                """.formatted(xml(library.toString()))));
        write(root, "app/pom.xml", module("app", """
                <dependency><groupId>reuse.fixture</groupId><artifactId>helper</artifactId><version>1</version></dependency>
                """));
        write(root, "helper/src/main/java/reuse/Helper.java", helper(SQL));
        write(root, "app/src/main/java/reuse/App.java", application());
        write(root, "helper/src/main/resources/mapper.xml", MAPPER_XML);
        // 系统临时目录位于项目内时，本次 settings/probe 也必须作为输出排除。
        System.setProperty("java.io.tmpdir", Files.createDirectories(root.resolve("invocation-temp")).toString());
        var options = new InputLocationOptions(InputMode.MAVEN_PROJECT, List.of(), List.of("-o"), null)
                .withBuildPolicy(BuildPolicy.AUTO).withProjectCacheDirectory(temporary.resolve("maven-cache"))
                .withCacheMaxAge(Duration.ZERO);

        Built cold = build(root, options);
        compiled(cold, true);
        assertEquals(2, cold.project().classDirectories().size());
        List<Object> expected = query(cold.project(), SQL);
        Built warm = build(root, options);
        compiled(warm, false);
        assertEquals(cold.project(), warm.project());
        assertEquals(expected, query(warm.project(), SQL));
        Built reused = build(root, options.withBuildPolicy(BuildPolicy.REUSE));
        compiled(reused, false);
        assertEquals(cold.project(), reused.project());
        assertEquals(expected, query(reused.project(), SQL));

        // 缓存/借用输入不会申请或删除本次 classOutputId 对应的临时目录。
        Path sentinel = write(Path.of(System.getProperty("java.io.tmpdir")), ".jsqlint/reused-project/sentinel.txt", "preserve");
        var analyzer = new Analyzer(config(), List.of(new JdbcAnalysisModule()), List.of(new AutoCompileInputResolver(options)));
        var result = analyzer.bind(root).analyze(AnalysisOptions.defaults().withStatistics(true)
                .withClassOutputId("reused-project").withCleanupClassOutput(true), ENTRY);
        assertEquals(expected, query(result, SQL));
        assertFalse(result.statistics().orElseThrow().phases().containsKey(AnalysisStatistics.Phase.COMPILATION));
        assertEquals("preserve", Files.readString(sentinel));
        cold.project().classDirectories().forEach(path -> assertTrue(Files.isDirectory(path)));
        resource(cold.project(), "mapper.xml");

        // REUSE 的语义是读取已选择的历史产物，不检查当前源码是否已修改。
        write(root, "app/src/main/java/reuse/App.java", application().replace("setLong(1,7L)", "setLong(1,99L)"));
        Path manifest = temporary.resolve("external-project.properties");
        CompiledProjectManifest.write(manifest, cold.project());
        var explicit = options.withCompiledProjectManifest(manifest);
        assertEquals(BuildPolicy.REUSE, explicit.buildPolicy());
        Built external = build(root, explicit);
        compiled(external, false);
        assertEquals(expected, query(external.project(), SQL));
        assertEquals("preserve", Files.readString(sentinel));
    }

    @Test
    void mavenGeneratedSourceOutputDoesNotMaskTemplateOrOrdinarySourceChanges() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("maven-generated-source"));
        Path library = library(root.resolve("third-party.jar"));
        String generatedComment = "\n// generated at @buildtime@\n";
        Path template = write(temporary, "external-template/Helper.java.template",
                helper(SQL).replace("package reuse;", "package generated;") + generatedComment);
        write(root, "pom.xml", """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                  <groupId>reuse.fixture</groupId><artifactId>generated-source</artifactId><version>1</version>
                  <properties><maven.compiler.release>8</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                  <dependencies>
                    <dependency><groupId>fixture.external</groupId><artifactId>marker</artifactId><version>1</version><scope>system</scope><systemPath>%s</systemPath></dependency>
                  </dependencies>
                  <build><plugins>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId><version>3.4.0</version>
                      <configuration><resources><resource>
                        <directory>${project.basedir}/src/main/resources</directory><targetPath>%s</targetPath>
                      </resource></resources></configuration>
                    </plugin>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.15.0</version><configuration><parameters>true</parameters><proc>none</proc></configuration></plugin>
                    <plugin><groupId>com.google.code.maven-replacer-plugin</groupId><artifactId>replacer</artifactId><version>1.5.3</version>
                      <executions><execution><id>generate-helper</id><phase>process-sources</phase><goals><goal>replace</goal></goals>
                        <configuration>
                          <file>%s</file><outputFile>${project.basedir}/src/main/java/generated/Helper.java</outputFile>
                          <replacements><replacement><token>@buildtime@</token><value>${maven.build.timestamp}</value></replacement></replacements>
                        </configuration>
                      </execution></executions>
                    </plugin>
                  </plugins></build>
                </project>
                """.formatted(xml(library.toString()), xml(root.resolve("target/classes").toString()), xml(template.toString())));
        Path generated = root.resolve("src/main/java/generated/Helper.java");
        write(root, "src/main/java/reuse/App.java", application().replace("Helper.sql()", "generated.Helper.sql()"));
        write(root, "src/main/resources/mapper.xml", MAPPER_XML);
        assertFalse(Files.exists(generated.getParent()));
        var options = new InputLocationOptions(InputMode.MAVEN_PROJECT, List.of(), List.of(), null)
                .withBuildPolicy(BuildPolicy.AUTO).withProjectCacheDirectory(temporary.resolve("generated-source-cache"))
                .withCacheMaxAge(Duration.ZERO);

        // 插件在源码根首次生成新的包目录；外部模板只通过 mojo 的 file 参数声明。
        Built cold = build(root, options);compiled(cold, true);
        List<Object> expected = query(cold.project(), SQL);
        assertTrue(Files.readString(generated).contains(SQL));
        assertFalse(Files.readString(generated).contains("@buildtime@"));
        Built warm = build(root, options);compiled(warm, false);
        assertEquals(cold.project(), warm.project());assertEquals(expected, query(warm.project(), SQL));

        // 生成源码属于构建输出，手动删除它不会改变已经发布的输入快照。
        Files.delete(generated);
        Built withoutGeneratedSource = build(root, options);compiled(withoutGeneratedSource, false);
        assertEquals(cold.project(), withoutGeneratedSource.project());
        assertEquals(expected, query(withoutGeneratedSource.project(), SQL));

        String changed = "SELECT id FROM template_changed_users WHERE id=?";
        Files.writeString(template, helper(changed).replace("package reuse;", "package generated;") + generatedComment);
        Built templateChanged = build(root, options);compiled(templateChanged, true);
        assertNotEquals(cold.project().classDirectories(), templateChanged.project().classDirectories());
        query(templateChanged.project(), changed);
        assertTrue(Files.readString(generated).contains(changed));

        // 精确排除生成文件不能掩盖同一源码根里其他普通 Java 源码的修改。
        String ordinaryChanged = "SELECT id FROM app_changed_users WHERE id=?";
        write(root, "src/main/java/reuse/App.java", application().replace("Helper.sql()", "\"" + ordinaryChanged + "\""));
        Built sourceChanged = build(root, options);compiled(sourceChanged, true);
        assertNotEquals(templateChanged.project().classDirectories(), sourceChanged.project().classDirectories());
        query(sourceChanged.project(), ordinaryChanged);
        assertEquals(expected, query(cold.project(), SQL));
    }

    @Test
    void mavenCanPublishWhenAPluginCreatesTheOnlyJavaSourceRoot() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("maven-generated-source-root"));
        Path library = library(root.resolve("third-party.jar"));
        Path template = write(temporary, "external-template/App.java.template",
                application().replace("Helper.sql()", "\"" + SQL + "\"") + "\n// generated at @buildtime@\n");
        write(root, "pom.xml", """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                  <groupId>reuse.fixture</groupId><artifactId>generated-source-root</artifactId><version>1</version>
                  <properties><maven.compiler.release>8</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                  <dependencies>
                    <dependency><groupId>fixture.external</groupId><artifactId>marker</artifactId><version>1</version><scope>system</scope><systemPath>%s</systemPath></dependency>
                  </dependencies>
                  <build><plugins>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId><version>3.4.0</version></plugin>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.15.0</version><configuration><parameters>true</parameters><proc>none</proc></configuration></plugin>
                    <plugin><groupId>com.google.code.maven-replacer-plugin</groupId><artifactId>replacer</artifactId><version>1.5.3</version>
                      <executions><execution><id>generate-app</id><phase>process-sources</phase><goals><goal>replace</goal></goals>
                        <configuration>
                          <file>%s</file><outputFile>${project.basedir}/src/main/java/reuse/App.java</outputFile>
                          <replacements><replacement><token>@buildtime@</token><value>${maven.build.timestamp}</value></replacement></replacements>
                        </configuration>
                      </execution></executions>
                    </plugin>
                  </plugins></build>
                </project>
                """.formatted(xml(library.toString()), xml(template.toString())));
        write(root, "src/main/resources/mapper.xml", MAPPER_XML);
        Path sourceRoot = root.resolve("src/main/java");
        assertFalse(Files.exists(sourceRoot), "The plugin must create the complete Java source root");
        var options = new InputLocationOptions(InputMode.MAVEN_PROJECT, List.of(), List.of(), null)
                .withBuildPolicy(BuildPolicy.AUTO).withProjectCacheDirectory(temporary.resolve("generated-source-root-cache"))
                .withCacheMaxAge(Duration.ZERO);

        Built cold = build(root, options);compiled(cold, true);
        assertTrue(Files.isRegularFile(sourceRoot.resolve("reuse/App.java")));
        List<Object> expected = query(cold.project(), SQL);
        Built warm = build(root, options);compiled(warm, false);
        assertEquals(cold.project(), warm.project());
        assertEquals(expected, query(warm.project(), SQL));
    }

    @Test
    void gradleTracksExternalSourceSetAndRebuildsOnlyAfterItsContentChanges() throws Exception {
        String installation = System.getProperty("jsqlint.test.gradle.home");
        assumeTrue(installation != null && Files.isDirectory(Path.of(installation)), "设置 jsqlint.test.gradle.home 使用本地 Gradle 安装");
        Path root = Files.createDirectories(temporary.resolve("gradle"));
        Path library = library(root.resolve("libs/marker.jar"));
        Path sources = Files.createDirectories(temporary.resolve("external-sources"));
        Path resources = Files.createDirectories(temporary.resolve("external-resources"));
        write(sources, "reuse/Helper.java", helper(SQL));write(sources, "reuse/App.java", application());
        write(resources, "mapper.xml", MAPPER_XML);
        write(root, "settings.gradle", "rootProject.name='reuse-gradle'\n");
        write(root, "build.gradle", """
                plugins { id 'java' }
                sourceCompatibility = '1.8'
                targetCompatibility = '1.8'
                dependencies { implementation files('%s') }
                sourceSets.main.java.setSrcDirs([file('%s')])
                sourceSets.main.resources.setSrcDirs([file('%s')])
                """.formatted(groovy(library), groovy(sources), groovy(resources)));
        var options = new InputLocationOptions(InputMode.GRADLE_PROJECT, List.of(), List.of("--offline"), Path.of(installation))
                .withBuildPolicy(BuildPolicy.AUTO).withProjectCacheDirectory(temporary.resolve("gradle-cache"))
                .withCacheMaxAge(Duration.ZERO);
        Built cold = build(root, options);compiled(cold, true);
        List<Object> expected = query(cold.project(), SQL);
        Built warm = build(root, options);compiled(warm, false);
        assertEquals(cold.project(), warm.project());assertEquals(expected, query(warm.project(), SQL));
        Built reused = build(root, options.withBuildPolicy(BuildPolicy.REUSE));compiled(reused, false);
        assertEquals(expected, query(reused.project(), SQL));

        String changed = "SELECT id FROM changed_users WHERE id=?";
        write(sources, "reuse/Helper.java", helper(changed));
        Built rebuilt = build(root, options);compiled(rebuilt, true);
        assertNotEquals(cold.project().classDirectories(), rebuilt.project().classDirectories());
        query(rebuilt.project(), changed);
        // 成功的历史 generation 仍保留，重新构建不删除旧快照。
        assertEquals(expected, query(cold.project(), SQL));
    }

    private Built build(Path root, InputLocationOptions options) {
        var recorder = StatisticsRecorder.create(AnalysisStatisticsOptions.timings());
        var project = ProjectCompiler.compile(root, options, new AnalysisInputContext(() -> {}, recorder));
        return new Built(project, recorder.snapshot().orElseThrow());
    }
    private static void compiled(Built build, boolean expected) {
        var phase = build.statistics().phases().get(AnalysisStatistics.Phase.COMPILATION);
        assertEquals(expected, phase != null && phase.invocations() > 0, build.statistics().toString());
        assertEquals(build.statistics().elapsedNanos(), build.statistics().phases().values().stream().mapToLong(AnalysisStatistics.PhaseCost::selfNanos).sum());
    }
    private static List<Object> query(CompiledProject project, String sql) throws Exception {
        resource(project, "mapper.xml");
        try (var input = ProjectInputs.open(project)) {
            var view = new JavaView(new ArrayList<>(input.locations()));
            assertTrue(view.getClass(view.getIdentifierFactory().getClassType("dep.Marker")).orElseThrow().isLibraryClass());
            var analyzer = new Analyzer(config(), List.of(new JdbcAnalysisModule()), List.of());
            var result = analyzer.analyze(new AnalysisRequest(input.locations(), input.resources(), AnalysisOptions.defaults().withStatistics(true)), ENTRY);
            assertFalse(result.statistics().orElseThrow().phases().containsKey(AnalysisStatistics.Phase.COMPILATION));
            return query(result, sql);
        }
    }
    private static List<Object> query(AnalysisResult result, String expected) {
        assertEquals(AnalysisResult.Status.SUCCESS, result.status(), result.diagnostics().toString());
        var calls = result.calls().stream().filter(call -> call.callSite().target().getName().equals("executeQuery")).toList();
        assertEquals(1, calls.size(), result.diagnostics().toString());
        var sample = calls.getFirst().samples().getFirst();
        String sql = sample.sql().orElseThrow().replaceAll("\\s+", " ").strip();
        assertEquals(expected, sql);
        var values = sample.bindings().stream().map(SqlSample.Binding::value).toList();
        assertEquals(List.of(new SqlValue.Known(7L)), values);
        return List.of(sql, values);
    }
    private static void resource(CompiledProject project, String file) throws Exception {
        Path path = project.resourceRoots().stream().map(root -> root.resolve(file)).filter(Files::isRegularFile)
                .findFirst().orElseThrow(() -> new AssertionError(project.resourceRoots().toString()));
        assertEquals(MAPPER_XML, Files.readString(path));
    }
    private static AnalyzerConfig config() {
        return AnalyzerConfig.defaults().withInferenceOptions(InferenceOptions.defaults().withMode(InferenceOptions.Mode.JOINT));
    }
    private Path library(Path jar) throws Exception {
        Path source = write(temporary, "library-source/Marker.java", "package dep; public final class Marker {} ");
        Path classes = Files.createDirectories(temporary.resolve("library-classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "--release", "8", "-d", classes.toString(), source.toString()));
        Files.createDirectories(jar.getParent());
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("dep/Marker.class"));Files.copy(classes.resolve("dep/Marker.class"), output);output.closeEntry();
        }
        return jar;
    }
    private static Path write(Path root, String name, String contents) throws Exception {
        Path path = root.resolve(name);Files.createDirectories(path.getParent());return Files.writeString(path, contents);
    }
    private static String helper(String sql) {
        return "package reuse; public class Helper { private static dep.Marker marker; public static String sql() { return \"" + sql + "\"; } }";
    }
    private static String application() {
        return "package reuse; public class App { public static void run(java.sql.Connection c) throws Exception { java.sql.PreparedStatement p=c.prepareStatement(Helper.sql());p.setLong(1,7L);p.executeQuery(); } }";
    }
    private static String module(String name, String dependencies) {
        return "<project xmlns='http://maven.apache.org/POM/4.0.0'><modelVersion>4.0.0</modelVersion><parent><groupId>reuse.fixture</groupId><artifactId>reactor</artifactId><version>1</version></parent><artifactId>"
                + name + "</artifactId><dependencies>" + dependencies + "</dependencies></project>";
    }
    private static String xml(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;"); }
    private static String groovy(Path path) { return path.toAbsolutePath().normalize().toString().replace('\\', '/').replace("'", "\\'"); }
    private record Built(CompiledProject project, AnalysisStatistics statistics) {}
}
