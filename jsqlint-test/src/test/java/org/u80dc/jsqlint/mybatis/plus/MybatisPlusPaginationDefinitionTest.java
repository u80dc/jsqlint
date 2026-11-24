package org.u80dc.jsqlint.mybatis.plus;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.resource.PathResourceSource;
import org.u80dc.jsqlint.mybatis.MybatisAnalysisModule;
import org.u80dc.jsqlint.mybatis.MybatisOptions;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.DefaultRuntimeAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;

import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MybatisPlusPaginationDefinitionTest {
    private static final String MAPPER = "sample.PagingMapper";
    private static final List<String> PARAMETERS = List.of("com.baomidou.mybatisplus.core.metadata.IPage",
            "com.baomidou.mybatisplus.core.conditions.Wrapper");
    private static final String SQL = "SELECT id,name FROM explicit_page_users";
    @TempDir Path temporary;

    @Test void abstractRedeclarationDoesNotInventDefaultPageStatements() throws Exception {
        Path classes = compile();
        for (String method : List.of("selectPage", "selectMapsPage")) {
            var result = analyze(classes, method, null, new MybatisPlusExtension(MybatisPlusOptions.defaults()));
            assertEquals(AnalysisResult.Status.PARTIAL, result.status(), result.diagnostics().toString());
            assertTrue(result.calls().isEmpty());
            assertEquals(1, result.definitions().size());
            var definition = result.definitions().getFirst().definition().orElseThrow();
            assertEquals(MAPPER + "." + method, definition.id());
            assertTrue(definition.issues().stream().anyMatch(issue -> issue.startsWith(
                    "MyBatis-Plus default pagination method was redeclared abstract without an injected SQL statement:")),
                    definition.issues().toString());
            assertTrue(result.samples().stream().allMatch(sample -> sample.sql().isEmpty()));
        }
        // 独立核验运行时：现代默认注入器注入selectList/selectMaps，而非抽象重声明的page语句。
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            var configuration = new MybatisConfiguration();
            GlobalConfigUtils.setGlobalConfig(configuration, new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()));
            configuration.addMapper(loader.loadClass(MAPPER));
            assertTrue(configuration.hasStatement(MAPPER + ".selectList"));
            assertTrue(configuration.hasStatement(MAPPER + ".selectMaps"));
            assertFalse(configuration.hasStatement(MAPPER + ".selectPage"));
            assertFalse(configuration.hasStatement(MAPPER + ".selectMapsPage"));
        }
    }

    @Test void explicitXmlCanProvideTheAbstractPageStatement() throws Exception {
        Path classes = compile();
        Path resources = Files.createDirectories(temporary.resolve("xml"));
        Files.writeString(resources.resolve("mapper.xml"), "<mapper namespace='" + MAPPER
                + "'><select id='selectPage'>" + SQL + "</select></mapper>");
        var result = analyze(classes, "selectPage", resources, new MybatisPlusExtension(MybatisPlusOptions.defaults()));
        assertEquals(AnalysisResult.Status.SUCCESS, result.status(), result.diagnostics().toString());
        assertEquals(1, result.definitions().size());
        assertEquals(SQL, result.definitions().getFirst().sample(Map.of()).sql().orElseThrow());
        assertEquals(resources.resolve("mapper.xml").toUri(), result.definitions().getFirst().location().resource());
    }

    @Test void explicitInjectorCanProvideTheAbstractPageStatement() throws Exception {
        Path classes = compile();
        var model = new PlusSqlInjectorModel("sample.PageInjector", "3.5.16", false,
                List.of(new PlusSqlInjectorModel.Method("selectPage", PARAMETERS, "select", SQL)));
        var extension = new MybatisPlusExtension(MybatisPlusOptions.defaults(), PlusPluginConfig.none(), false, Optional.of(model));
        var result = analyze(classes, "selectPage", null, extension);
        assertEquals(AnalysisResult.Status.SUCCESS, result.status(), result.diagnostics().toString());
        assertEquals(1, result.definitions().size());
        var definition = result.definitions().getFirst().definition().orElseThrow();
        assertEquals("mybatis-plus-injector", definition.metadata().get("framework"));
        assertTrue(definition.complete(), definition.issues().toString());
        assertEquals(SQL, result.definitions().getFirst().sample(Map.of()).sql().orElseThrow());
    }

    private Path compile() throws Exception {
        Path source = Files.writeString(temporary.resolve("Fixture.java"), """
                package sample;
                import com.baomidou.mybatisplus.core.mapper.BaseMapper;
                import com.baomidou.mybatisplus.core.metadata.IPage;
                import com.baomidou.mybatisplus.core.conditions.Wrapper;
                import com.baomidou.mybatisplus.annotation.*;
                import java.util.Map;
                @TableName("page_users") class User { @TableId(type=IdType.INPUT) String id; String name; }
                interface PagingMapper extends BaseMapper<User> {
                    <P extends IPage<User>> P selectPage(P page, Wrapper<User> wrapper);
                    <P extends IPage<Map<String,Object>>> P selectMapsPage(P page, Wrapper<User> wrapper);
                }
                public class Fixture {}
                """);
        Path classes = Files.createDirectories(temporary.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-parameters",
                "-classpath", System.getProperty("java.class.path"), "-d", classes.toString(), source.toString()));
        return classes;
    }

    private static AnalysisResult analyze(Path classes, String method, Path resources, MybatisPlusExtension extension) throws Exception {
        String library = Path.of(BaseMapper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        try (var code = new JavaClassPathAnalysisInputLocation(classes.toString(), SourceType.Application);
             var dependency = new JavaClassPathAnalysisInputLocation(library, SourceType.Library);
             var runtime = new DefaultRuntimeAnalysisInputLocation()) {
            var analyzer = new Analyzer(List.of(new MybatisAnalysisModule(MybatisOptions.defaults(), List.of(extension))), List.of());
            var request = new AnalysisRequest(List.of(code, dependency, runtime), resources == null ? List.of() : List.of(new PathResourceSource(resources)));
            return analyzer.analyze(request, "<" + MAPPER + ": com.baomidou.mybatisplus.core.metadata.IPage " + method
                    + "(" + String.join(",", PARAMETERS) + ")>");
        }
    }
}
