package org.u80dc.jsqlint.mybatis.plus;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.u80dc.jsqlint.core.AnalysisRequest;
import org.u80dc.jsqlint.core.AnalysisResult;
import org.u80dc.jsqlint.core.Analyzer;
import org.u80dc.jsqlint.mybatis.MybatisAnalysisModule;
import org.u80dc.jsqlint.mybatis.MybatisOptions;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.DefaultRuntimeAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.views.JavaView;

import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** 仅验证元数据来源/组件不一致；改元数据的真实字节码副本不用于跨版本SQL oracle。 */
class PlusFrameworkArtifactMetadataTest {
    private static final String PROPERTIES = "META-INF/maven/com.baomidou/mybatis-plus-core/pom.properties";
    private static final String PAGINATION = "com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor";
    @TempDir Path temporary;

    @Test void corePomVersionWinsOverApplicationManifest() throws Exception {
        Path artifact = artifact("1.0.0", "3.5.16");
        try (var input = new JavaClassPathAnalysisInputLocation(artifact.toString(), SourceType.Library)) {
            assertEquals("3.5.16", PlusFrameworkVersion.read(new JavaView(List.of(input))));
        }
    }

    @Test void blankCorePomVersionFallsBackToManifest() throws Exception {
        Path artifact = artifact("3.5.16", " ");
        try (var input = new JavaClassPathAnalysisInputLocation(artifact.toString(), SourceType.Library)) {
            assertEquals("3.5.16", PlusFrameworkVersion.read(new JavaView(List.of(input))));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"3.4.3.4", "3.5.18.1"})
    void paginationArtifactPomWinsOverDifferentManifest(String version) throws Exception {
        Path target = componentArtifact(PaginationInnerInterceptor.class, "mybatis-plus-jsqlparser", version);
        try (var jar = new JarFile(target.toFile())) {
            assertEquals("3.5.16", jar.getManifest().getMainAttributes().getValue("Implementation-Version"));
        }
        try (var input = new JavaClassPathAnalysisInputLocation(target.toString(), SourceType.Library)) {
            assertEquals(version, PlusFrameworkVersion.read(new JavaView(List.of(input)), PAGINATION));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"3.4.3.4", "3.5.18.1"})
    void springArtifactPomWinsOverDifferentManifest(String version) throws Exception {
        Path target = componentArtifact(ServiceImpl.class, "mybatis-plus-spring", version);
        try (var jar = new JarFile(target.toFile())) {
            assertEquals("3.5.16", jar.getManifest().getMainAttributes().getValue("Implementation-Version"));
        }
        try (var input = new JavaClassPathAnalysisInputLocation(target.toString(), SourceType.Library)) {
            assertEquals(version, PlusFrameworkVersion.read(new JavaView(List.of(input)), ServiceImpl.class.getName()));
            assertEquals(version, PlusFrameworkVersion.read(new JavaView(List.of(input)),
                    "com.baomidou.mybatisplus.extension.service.IService"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"3.4.3.4", "3.5.18.1"})
    void configuredPaginationRejectsItsActualArtifactVersionMismatch(String version) throws Exception {
        Path target = componentArtifact(PaginationInnerInterceptor.class, "mybatis-plus-jsqlparser", version);
        Path source = Files.writeString(temporary.resolve("Fixture.java"), """
                package sample;
                import com.baomidou.mybatisplus.core.mapper.BaseMapper;
                import com.baomidou.mybatisplus.annotation.*;
                @TableName("users") class User { @TableId(type=IdType.INPUT) String id; String name; }
                interface UserMapper extends BaseMapper<User> {}
                public class Fixture {}
                """);
        Path classes = Files.createDirectories(temporary.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-parameters",
                "-classpath", System.getProperty("java.class.path"), "-d", classes.toString(), source.toString()));
        var libraries = new LinkedHashSet<Path>();
        for (Class<?> type : List.of(BaseMapper.class, Page.class, ServiceImpl.class, TableName.class))
            libraries.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()));
        libraries.add(target);
        var locations = new ArrayList<JavaClassPathAnalysisInputLocation>();
        AnalysisResult result;
        try (var runtime = new DefaultRuntimeAnalysisInputLocation()) {
            locations.add(new JavaClassPathAnalysisInputLocation(classes.toString(), SourceType.Application));
            for (Path library : libraries) locations.add(new JavaClassPathAnalysisInputLocation(library.toString(), SourceType.Library));
            var inputs = new ArrayList<AnalysisInputLocation>(locations);
            inputs.add(runtime);
            var plugins = new PlusPluginConfig(List.of(new PlusPluginConfig.Pagination(PlusPluginConfig.Dialect.MYSQL)));
            result = new Analyzer(List.of(new MybatisAnalysisModule(MybatisOptions.defaults(),
                    List.of(new MybatisPlusExtension(MybatisPlusOptions.defaults(), plugins)))), List.of())
                    .analyze(new AnalysisRequest(inputs, List.of()), "<sample.UserMapper: java.lang.Object selectById(java.io.Serializable)>");
        } finally {
            for (var location : locations) location.close();
        }
        assertEquals(AnalysisResult.Status.PARTIAL, result.status(), result.diagnostics().toString());
        assertTrue(result.calls().isEmpty());
        assertEquals(1, result.definitions().size());
        var definition = result.definitions().getFirst().definition().orElseThrow();
        String component = "3.5.16/component(" + PAGINATION + "):" + version;
        assertEquals("3.5.16", definition.metadata().get("frameworkVersion"));
        assertEquals(component, definition.metadata().get("pluginFrameworkVersion"));
        assertEquals(List.of("Inconsistent MyBatis-Plus plugin component versions: " + component), definition.issues());
        assertFalse(definition.complete());
        assertFalse(result.samples().isEmpty());
        assertTrue(result.samples().stream().allMatch(sample -> sample.sql().isEmpty()));
    }

    /** 保持组件所有字节码和Manifest原样，只替换声明的Maven版本以测试来源分类。 */
    private Path componentArtifact(Class<?> anchor, String artifact, String version) throws Exception {
        Path runtime = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
        String properties = "META-INF/maven/com.baomidou/" + artifact + "/pom.properties";
        Path target = temporary.resolve(artifact + "-metadata-" + version + ".jar");
        try (var source = new ZipFile(runtime.toFile());var output = new JarOutputStream(Files.newOutputStream(target))) {
            for (var entry : source.stream().toList()) {
                if (entry.isDirectory() || entry.getName().equals(properties)) continue;
                output.putNextEntry(new JarEntry(entry.getName()));
                try (var input = source.getInputStream(entry)) { input.transferTo(output); }
                output.closeEntry();
            }
            output.putNextEntry(new JarEntry(properties));
            output.write(("groupId=com.baomidou\nartifactId=" + artifact + "\nversion=" + version + "\n").getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return target;
    }

    private Path artifact(String manifestVersion, String pomVersion) throws Exception {
        Path runtime = Path.of(BaseMapper.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path target = temporary.resolve("packaged-core.jar");
        var manifest = new Manifest();manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Implementation-Version", manifestVersion);
        try (var source = new ZipFile(runtime.toFile());var output = new JarOutputStream(Files.newOutputStream(target), manifest)) {
            for (var entry : source.stream().toList()) {
                if (entry.isDirectory() || entry.getName().equalsIgnoreCase("META-INF/MANIFEST.MF") || entry.getName().equals(PROPERTIES)) continue;
                output.putNextEntry(new JarEntry(entry.getName()));
                try (var input = source.getInputStream(entry)) { input.transferTo(output); }
                output.closeEntry();
            }
            output.putNextEntry(new JarEntry(PROPERTIES));
            output.write(("groupId=com.baomidou\nartifactId=mybatis-plus-core\nversion=" + pomVersion + "\n").getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return target;
    }
}
