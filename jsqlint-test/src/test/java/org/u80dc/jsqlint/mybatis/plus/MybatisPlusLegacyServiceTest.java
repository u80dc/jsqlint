package org.u80dc.jsqlint.mybatis.plus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.u80dc.jsqlint.commons.compiler.CompiledProject;
import org.u80dc.jsqlint.commons.compiler.MavenProjectCompiler;
import org.u80dc.jsqlint.commons.location.InputLocationOptions;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.mybatis.MybatisAnalysisModule;
import org.u80dc.jsqlint.mybatis.MybatisOptions;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.DefaultRuntimeAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;

import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 3.3.2 的 IService/ServiceImpl 与隔离运行时校验旧版 Service，不连接数据库。 */
@Isolated("Maven Embedder 使用进程共享状态")
class MybatisPlusLegacyServiceTest {
    private static final String SERVICE = "legacyservice.UserService";
    private static final String MAPPER = "legacyservice.UserMapper";
    private static final String SIGNATURE = "<legacyservice.Fixture: void run(" + SERVICE + ")>";
    @TempDir Path temporary;

    @Test
    void legacyServiceMatchesRealDefaultsAndRejectsMixedComponents() throws Exception {
        CompiledProject project = compile();
        var result = analyze(project, project.dependencies());
        assertTrue(result.status() == AnalysisResult.Status.SUCCESS || result.status() == AnalysisResult.Status.PARTIAL,
                result.diagnostics().toString());
        assertTrue(result.diagnostics().stream().allMatch(diagnostic -> Set.of("METHOD_SCOPE_UNRESOLVED", "JOINT_ANALYSIS_INCOMPLETE").contains(diagnostic.code())),
                result.diagnostics().toString());
        assertEquals(Set.of(
                "Unresolved downstream call from " + SIGNATURE + ": <com.baomidou.mybatisplus.core.conditions.query.QueryWrapper: void <init>()>",
                "Unresolved downstream call from " + SIGNATURE + ": <com.baomidou.mybatisplus.core.conditions.query.QueryWrapper: java.lang.Object eq(java.lang.Object,java.lang.Object)>"),
                Set.copyOf(result.diagnostics().stream().filter(diagnostic -> diagnostic.code().equals("METHOD_SCOPE_UNRESOLVED"))
                        .map(AnalysisDiagnostic::message).toList()));
        assertTrue(result.diagnostics().stream().filter(diagnostic -> diagnostic.code().equals("JOINT_ANALYSIS_INCOMPLETE"))
                .allMatch(diagnostic -> diagnostic.message().equals("Template control conditions are not included in joint path solving")
                        && diagnostic.affectedDimensions().equals(Set.of(SqlAssessment.Dimension.PATH_COVERAGE))));
        assertEquals(5, result.calls().size(), result.diagnostics().toString());
        var actual = new ArrayList<List<Object>>();
        for (var call : result.calls()) {
            assertEquals(1, call.samples().size());
            var sample = call.samples().getFirst();
            var definition = sample.definition().orElseThrow();
            assertEquals("3.3.2", definition.metadata().get("frameworkVersion"));
            assertEquals(MAPPER, definition.metadata().get("mapperType"));
            assertTrue(definition.complete(), definition.issues().toString());
            assertTrue(definition.metadata().get("logicalMapperMethod").contains(MAPPER));
            assertTrue(sample.bindings().stream().allMatch(binding -> binding.value() instanceof SqlValue.Known));
            actual.add(List.of(normalize(sample.sql().orElseThrow(() -> new AssertionError(sample.issues().toString()))),
                    sample.bindings().stream().map(SqlSample.Binding::value).toList()));
        }
        assertEquals(oracle(project), actual);

        // 真实旧 core 与真实新 extension/spring 组合，不能作为同一发行版的 Service 模型处理。
        var mixed = new ArrayList<>(project.dependencies().stream()
                .filter(path -> !path.getFileName().toString().startsWith("mybatis-plus-extension-")).toList());
        for (Class<?> type : List.of(com.baomidou.mybatisplus.extension.repository.IRepository.class,
                com.baomidou.mybatisplus.extension.service.impl.ServiceImpl.class))
            mixed.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()));
        var inconsistent = analyze(project, mixed);
        assertEquals(AnalysisResult.Status.PARTIAL, inconsistent.status());
        assertEquals(5, inconsistent.calls().size(), inconsistent.diagnostics().toString());
        assertTrue(inconsistent.samples().stream().allMatch(sample -> sample.sql().isEmpty()));
        assertTrue(inconsistent.samples().stream().flatMap(sample -> sample.issues().stream()).anyMatch(issue ->
                issue.contains("inconsistent Service framework versions") && issue.contains("3.3.2") && issue.contains("3.5.16")));
    }

    private CompiledProject compile() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                  <groupId>fixture</groupId><artifactId>legacy-plus-service</artifactId><version>1</version>
                  <properties><maven.compiler.release>8</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                  <dependencies>
                    <dependency><groupId>com.baomidou</groupId><artifactId>mybatis-plus-extension</artifactId><version>3.3.2</version></dependency>
                    <dependency><groupId>org.springframework</groupId><artifactId>spring-jdbc</artifactId><version>5.2.6.RELEASE</version></dependency>
                    <dependency><groupId>org.springframework</groupId><artifactId>spring-context</artifactId><version>5.2.6.RELEASE</version></dependency>
                  </dependencies>
                  <build><plugins>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId><version>3.4.0</version></plugin>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.15.0</version><configuration><parameters>true</parameters><proc>none</proc></configuration></plugin>
                  </plugins></build>
                </project>
                """);
        Path sources = Files.createDirectories(project.resolve("src/main/java/legacyservice"));
        Files.writeString(sources.resolve("Fixture.java"), """
                package legacyservice;
                import com.baomidou.mybatisplus.annotation.*;
                import com.baomidou.mybatisplus.core.mapper.BaseMapper;
                import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
                import com.baomidou.mybatisplus.extension.service.IService;
                import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
                @TableName("legacy_users") class User {
                    @TableId(type=IdType.INPUT) String id;
                    String name;
                    User(String id,String name) { this.id=id;this.name=name; }
                    public String getId() { return id; }
                    public String getName() { return name; }
                }
                interface UserMapper extends BaseMapper<User> {}
                class UserService extends ServiceImpl<UserMapper,User> {
                    UserService(UserMapper mapper) { baseMapper=mapper; }
                }
                public class Fixture {
                    public static UserService service(UserMapper mapper) { return new UserService(mapper); }
                    public static void run(UserService service) {
                        service.save(new User("one","Alice"));
                        service.getById("one");
                        IService<User> alias=service;
                        alias.removeById("two");
                        QueryWrapper<User> wrapper=new QueryWrapper<>();
                        wrapper.eq("name","Alice");
                        service.list(wrapper);
                        service.count();
                    }
                }
                """);
        return MavenProjectCompiler.compile(project, InputLocationOptions.defaults()
                .withClassOutputDirectory(temporary.resolve("classes")));
    }

    private static AnalysisResult analyze(CompiledProject project, List<Path> dependencies) throws Exception {
        var locations = new ArrayList<JavaClassPathAnalysisInputLocation>();
        try (var runtime = new DefaultRuntimeAnalysisInputLocation()) {
            for (Path path : project.classDirectories()) locations.add(new JavaClassPathAnalysisInputLocation(path.toString(), SourceType.Application));
            for (Path path : dependencies.stream().distinct().toList()) locations.add(new JavaClassPathAnalysisInputLocation(path.toString(), SourceType.Library));
            var inputs = new ArrayList<sootup.core.inputlocation.AnalysisInputLocation>(locations);inputs.add(runtime);
            return new Analyzer(List.of(new MybatisAnalysisModule(MybatisOptions.defaults(), List.of(new MybatisPlusExtension()))), List.of())
                    .analyze(new AnalysisRequest(inputs, List.of()), SIGNATURE);
        } finally {
            for (var location : locations) location.close();
        }
    }

    private static List<List<Object>> oracle(CompiledProject project) throws Exception {
        var paths = new ArrayList<>(project.classDirectories());paths.addAll(project.dependencies());
        var urls = new ArrayList<URL>();for (Path path : paths) urls.add(path.toUri().toURL());
        // 旧版框架从专属依赖加载，不能从分析器运行时继承 3.5.16 类。
        try (var loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            assertEquals("3.3.2", loader.loadClass("com.baomidou.mybatisplus.core.mapper.BaseMapper").getPackage().getImplementationVersion());
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("com.baomidou.mybatisplus.extension.repository.IRepository"));
            Class<?> configurationType = loader.loadClass("org.apache.ibatis.session.Configuration");
            Object configuration = loader.loadClass("com.baomidou.mybatisplus.core.MybatisConfiguration").getConstructor().newInstance();
            Class<?> globalType = loader.loadClass("com.baomidou.mybatisplus.core.config.GlobalConfig");
            Class<?> dbType = loader.loadClass("com.baomidou.mybatisplus.core.config.GlobalConfig$DbConfig");
            Object global = loader.loadClass("com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils")
                    .getMethod("getGlobalConfig", configurationType).invoke(null, configuration);
            globalType.getMethod("setDbConfig", dbType).invoke(global, dbType.getConstructor().newInstance());
            Class<?> mapperType = loader.loadClass(MAPPER);
            configuration.getClass().getMethod("addMapper", Class.class).invoke(configuration, mapperType);
            Class<?> sessionType = loader.loadClass("org.apache.ibatis.session.SqlSession");
            var expected = new ArrayList<List<Object>>();
            Object session = Proxy.newProxyInstance(loader, new Class<?>[]{sessionType}, (proxy, method, arguments) -> {
                if (method.getName().equals("getConfiguration")) return configuration;
                if (!Set.of("insert", "update", "delete", "selectOne", "selectList").contains(method.getName())) return null;
                String statement = (String) arguments[0];Object parameter = arguments.length > 1 ? arguments[1] : null;
                Object mapped = configuration.getClass().getMethod("getMappedStatement", String.class).invoke(configuration, statement);
                Object bound = mapped.getClass().getMethod("getBoundSql", Object.class).invoke(mapped, parameter);
                String sql = (String) bound.getClass().getMethod("getSql").invoke(bound);
                var values = new ArrayList<SqlValue>();
                for (Object mapping : (List<?>) bound.getClass().getMethod("getParameterMappings").invoke(bound)) {
                    String property = (String) mapping.getClass().getMethod("getProperty").invoke(mapping);
                    Object value;
                    if ((boolean) bound.getClass().getMethod("hasAdditionalParameter", String.class).invoke(bound, property))
                        value = bound.getClass().getMethod("getAdditionalParameter", String.class).invoke(bound, property);
                    else if (parameter == null) value = null;
                    else {
                        Object registry = configuration.getClass().getMethod("getTypeHandlerRegistry").invoke(configuration);
                        if ((boolean) registry.getClass().getMethod("hasTypeHandler", Class.class).invoke(registry, parameter.getClass())) value = parameter;
                        else {
                            Object metadata = configuration.getClass().getMethod("newMetaObject", Object.class).invoke(configuration, parameter);
                            value = metadata.getClass().getMethod("getValue", String.class).invoke(metadata, property);
                        }
                    }
                    values.add(new SqlValue.Known(value));
                }
                expected.add(List.of(normalize(sql), List.copyOf(values)));
                if (Set.of("insert", "update", "delete").contains(method.getName())) return 1;
                if (statement.endsWith(".selectCount")) return 1;
                return method.getName().equals("selectList") ? List.of() : null;
            });
            Object mapper = configuration.getClass().getMethod("getMapper", Class.class, sessionType).invoke(configuration, mapperType, session);
            Class<?> fixture = loader.loadClass("legacyservice.Fixture");
            Object service = fixture.getMethod("service", mapperType).invoke(null, mapper);
            fixture.getMethod("run", loader.loadClass(SERVICE)).invoke(null, service);
            return List.copyOf(expected);
        }
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").replaceAll("\\s*([(),=])\\s*", "$1").strip();
    }
}
