package org.u80dc.jsqlint.mybatis.plus;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.u80dc.jsqlint.commons.compiler.CompiledProject;
import org.u80dc.jsqlint.commons.compiler.MavenProjectCompiler;
import org.u80dc.jsqlint.commons.location.InputLocationOptions;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.mybatis.MybatisAnalysisModule;
import org.u80dc.jsqlint.mybatis.MybatisOptions;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.DefaultRuntimeAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;

import java.io.File;
import java.io.ObjectInputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 对照真实旧/新乐观锁，NEVER 只影响 SET，原始版本来自字段而非计算 getter。 */
@Isolated("Maven Embedder 使用进程共享状态")
class MybatisPlusOptimisticNeverTest {
    private static final String FIXTURE = "optimisticnever.Fixture";
    private static final String MAPPER = FIXTURE + "$UserMapper";
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"3.3.2", "3.5.16"})
    void neverVersionMatchesTheTargetsOriginalFieldAndNullProtocol(String version) throws Exception {
        var project = compile(version);
        var result = analyze(project, "entry");
        var expected = oracle(project, version);
        assertEquals(3, expected.size());
        assertEquals(3, result.calls().size(), result.diagnostics().toString());
        assertTrue(result.diagnostics().stream().allMatch(diagnostic ->
                Set.of("METHOD_SCOPE_UNRESOLVED", "JOINT_ANALYSIS_INCOMPLETE").contains(diagnostic.code())),
                result.diagnostics().toString());

        var actual = new ArrayList<List<Object>>();
        for (var call : result.calls()) {
            assertEquals(1, call.samples().size(), call.issues().toString());
            var sample = call.samples().getFirst();
            var definition = sample.definition().orElseThrow();
            assertTrue(definition.complete(), definition.issues().toString());
            assertEquals(version, definition.metadata().get("frameworkVersion"));
            assertEquals(version, definition.metadata().get("pluginFrameworkVersion"));
            String slot = definition.metadata().get("originalVersionInput");
            assertNotNull(slot, definition.metadata().toString());
            assertEquals("java.lang.Integer", definition.metadata().get("originalVersionType"));
            String sql = normalize(sample.sql().orElseThrow(() -> new AssertionError(sample.issues().toString())));
            assertTrue(sql.startsWith("UPDATE optimistic_users SET name=? WHERE id=?"), sql);
            assertTrue(sql.endsWith("AND deleted=0"), sql);
            var values = sample.bindings().stream().map(SqlSample.Binding::value).toList();
            assertTrue(values.stream().allMatch(value -> value instanceof SqlValue.Known), values.toString());
            actual.add(List.of(sql, values));

            var rewrite = sample.rewrites().getLast();
            assertEquals("optimistic-lock", rewrite.id());
            assertEquals(2, rewrite.beforeBindings().size(), rewrite.toString());
            assertTrue(rewrite.beforeBindings().stream().noneMatch(binding -> binding.expression().contains("version")));
            if (sample.bindings().size() == 3) {
                assertTrue(sql.contains("AND rev=? AND deleted=0"), sql);
                var original = sample.bindings().getLast();
                var trace = original.trace().orElseThrow();
                assertTrue(trace.complete(), trace.toString());
                assertTrue(trace.variable().expression().contains(": java.lang.Integer version>"), trace.toString());
                assertFalse(trace.toString().contains("getVersion("), trace.toString());
            } else {
                assertEquals(2, sample.bindings().size());
                assertFalse(sql.contains("rev="), sql);
            }
        }
        assertEquals(expected, actual);
        assertEquals(List.of(new SqlValue.Known("Alice"), new SqlValue.Known("one"), new SqlValue.Known(3)), actual.getFirst().get(1));
        assertEquals(List.of(new SqlValue.Known("Alice"), new SqlValue.Known("two")), actual.get(1).get(1));
        assertEquals(List.of(new SqlValue.Known("Alice"), new SqlValue.Known("three"), new SqlValue.Known(Integer.MAX_VALUE)), actual.get(2).get(1));

        var unknown = analyze(project, "unknown");
        assertEquals(AnalysisResult.Status.PARTIAL, unknown.status());
        assertEquals(1, unknown.calls().size(), unknown.diagnostics().toString());
        var sample = unknown.calls().getFirst().samples().getFirst();
        assertTrue(sample.definition().orElseThrow().complete());
        assertTrue(sample.sql().isEmpty());
        assertTrue(sample.rendered().diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code().equals("SQL_PLUGIN_UNRESOLVED") && diagnostic.message().contains("Original version is unknown")),
                sample.issues().toString());
        assertTrue(sample.rewrites().getLast().beforeSql().isPresent());
        assertEquals(List.of(new SqlValue.Known("Alice"), new SqlValue.Known("unknown")),
                sample.rewrites().getLast().beforeBindings().stream().map(binding -> binding.value()).toList());
    }

    private CompiledProject compile(String version) throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                  <groupId>fixture</groupId><artifactId>plus-optimistic-never</artifactId><version>1</version>
                  <properties><maven.compiler.release>8</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                  <dependencies>
                    <dependency><groupId>com.baomidou</groupId><artifactId>mybatis-plus-extension</artifactId><version>%s</version></dependency>
                  </dependencies>
                  <build><plugins>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId><version>3.4.0</version></plugin>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.15.0</version><configuration><parameters>true</parameters><proc>none</proc></configuration></plugin>
                  </plugins></build>
                </project>
                """.formatted(version));
        Path sources = Files.createDirectories(project.resolve("src/main/java/optimisticnever"));
        Files.writeString(sources.resolve("Fixture.java"), """
                package optimisticnever;
                import com.baomidou.mybatisplus.annotation.*;
                import com.baomidou.mybatisplus.core.mapper.BaseMapper;
                public class Fixture {
                    @TableName("optimistic_users") public static class User {
                        @TableId(type=IdType.INPUT) private String id;
                        private String name;
                        @Version @TableField(value="rev",updateStrategy=FieldStrategy.NEVER) private Integer version;
                        @TableLogic private Integer deleted=0;
                        public User(String id,Integer version) { this.id=id;this.name="Alice";this.version=version; }
                        public String getId() { return id; }
                        public String getName() { return name; }
                        public Integer getVersion() { return version==null?null:version+10; }
                        public Integer getDeleted() { return deleted; }
                    }
                    public interface UserMapper extends BaseMapper<User> {}
                    public static void entry(UserMapper mapper) {
                        mapper.updateById(new User("one",3));
                        mapper.updateById(new User("two",null));
                        mapper.updateById(new User("three",Integer.MAX_VALUE));
                    }
                    public static native Integer unknownVersion();
                    public static void unknown(UserMapper mapper) {
                        mapper.updateById(new User("unknown",unknownVersion()));
                    }
                }
                """);
        Files.writeString(sources.resolve("NeverOracle.java"), oracleSource(version));
        return MavenProjectCompiler.compile(project, InputLocationOptions.defaults()
                .withClassOutputDirectory(temporary.resolve("classes")));
    }

    private static String oracleSource(String version) {
        String invocation = version.equals("3.3.2")
                ? "Executor intercepted=(Executor)new com.baomidou.mybatisplus.extension.plugins.OptimisticLockerInterceptor().plugin(executor);intercepted.update(mapped,parameters);"
                : "new com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor().beforeUpdate(executor,mapped,parameters);rows.add(snapshot(mapped.getBoundSql(parameters),parameters,configuration));";
        return """
                package optimisticnever;
                import com.baomidou.mybatisplus.core.MybatisConfiguration;
                import com.baomidou.mybatisplus.core.config.GlobalConfig;
                import com.baomidou.mybatisplus.core.mapper.BaseMapper;
                import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
                import org.apache.ibatis.executor.Executor;
                import org.apache.ibatis.mapping.BoundSql;
                import org.apache.ibatis.mapping.MappedStatement;
                import org.apache.ibatis.mapping.ParameterMapping;
                import java.io.*;
                import java.lang.reflect.Proxy;
                import java.util.*;
                public class NeverOracle {
                    public static void main(String[] arguments) throws Exception {
                        final MybatisConfiguration configuration=new MybatisConfiguration();
                        GlobalConfigUtils.getGlobalConfig(configuration).setDbConfig(new GlobalConfig.DbConfig());
                        configuration.addMapper(Fixture.UserMapper.class);
                        final MappedStatement mapped=configuration.getMappedStatement(Fixture.UserMapper.class.getName()+".updateById");
                        final List<List<Object>> rows=new ArrayList<List<Object>>();
                        Integer[] versions={3,null,Integer.MAX_VALUE};String[] ids={"one","two","three"};
                        for(int i=0;i<versions.length;i++) {
                            final Map<String,Object> parameters=new HashMap<String,Object>();
                            parameters.put("et",new Fixture.User(ids[i],versions[i]));
                            Executor executor=(Executor)Proxy.newProxyInstance(Executor.class.getClassLoader(),new Class<?>[]{Executor.class},(proxy,method,args)->{
                                if(method.getName().equals("update")) {
                                    rows.add(snapshot(((MappedStatement)args[0]).getBoundSql(args[1]),args[1],configuration));return 1;
                                }
                                if(method.getReturnType()==boolean.class)return false;
                                if(method.getReturnType()==int.class)return 0;
                                if(method.getReturnType()==long.class)return 0L;return null;
                            });
                            %s
                        }
                        Map<String,Object> output=new LinkedHashMap<String,Object>();output.put("rows",rows);
                        output.put("frameworkSource",BaseMapper.class.getProtectionDomain().getCodeSource().getLocation().toString());
                        output.put("javaVersion",System.getProperty("java.version"));
                        try(ObjectOutputStream stream=new ObjectOutputStream(new FileOutputStream(arguments[0]))) { stream.writeObject(output); }
                    }
                    private static List<Object> snapshot(BoundSql bound,Object parameters,MybatisConfiguration configuration) {
                        List<Object> values=new ArrayList<Object>();
                        for(ParameterMapping mapping:bound.getParameterMappings()) {
                            Object value=bound.hasAdditionalParameter(mapping.getProperty())?bound.getAdditionalParameter(mapping.getProperty())
                                : configuration.newMetaObject(parameters).getValue(mapping.getProperty());values.add(value);
                        }
                        return Arrays.<Object>asList(bound.getSql(),values);
                    }
                }
                """.formatted(invocation);
    }

    private static AnalysisResult analyze(CompiledProject project, String method) throws Exception {
        var locations = new ArrayList<JavaClassPathAnalysisInputLocation>();
        try (var runtime = new DefaultRuntimeAnalysisInputLocation()) {
            for (Path path : project.classDirectories()) locations.add(new JavaClassPathAnalysisInputLocation(path.toString(), SourceType.Application));
            for (Path path : project.dependencies()) locations.add(new JavaClassPathAnalysisInputLocation(path.toString(), SourceType.Library));
            var inputs = new ArrayList<AnalysisInputLocation>(locations);inputs.add(runtime);
            var plugins = new PlusPluginConfig(List.of(new PlusPluginConfig.OptimisticLock()));
            return new Analyzer(List.of(new MybatisAnalysisModule(MybatisOptions.defaults(),
                    List.of(new MybatisPlusExtension(MybatisPlusOptions.defaults(), plugins)))), List.of())
                    .analyze(new AnalysisRequest(inputs, List.of()), "<" + FIXTURE + ": void " + method + "(" + MAPPER + ")>");
        } finally {
            for (var location : locations) location.close();
        }
    }

    @SuppressWarnings("unchecked")
    private List<List<Object>> oracle(CompiledProject project, String version) throws Exception {
        String configuredJavaHome = System.getProperty("jsqlint.plus.oracle.javaHome." + version,
                System.getProperty("jsqlint.plus.oracle.javaHome", "")).strip();
        String javaHome = configuredJavaHome.isEmpty() ? System.getProperty("java.home") : configuredJavaHome;
        Path executable = Path.of(javaHome).resolve("bin").resolve(System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        assertTrue(Files.isRegularFile(executable), "Oracle Java executable does not exist: " + executable);
        var paths = new ArrayList<>(project.classDirectories());paths.addAll(project.dependencies());
        Path snapshots = temporary.resolve("oracle.bin");Path log = temporary.resolve("oracle.log");
        var command = new ArrayList<String>();command.add(executable.toString());
        if (configuredJavaHome.isEmpty()) command.addAll(List.of("--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED"));
        command.addAll(List.of("-Dfile.encoding=UTF-8", "-cp", String.join(File.pathSeparator, paths.stream().map(Path::toString).toList()),
                "optimisticnever.NeverOracle", snapshots.toString()));
        var process = new ProcessBuilder(command)
                .directory(project.directory().toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Target optimistic oracle timed out: " + version);
            assertEquals(0, process.exitValue(), () -> readLog(log));
            Map<String, Object> output;
            try (var input = new ObjectInputStream(Files.newInputStream(snapshots))) { output = (Map<String, Object>) input.readObject(); }
            Path core = project.dependencies().stream().filter(path -> path.getFileName().toString().equals("mybatis-plus-core-" + version + ".jar")).findFirst().orElseThrow();
            assertEquals(core.toRealPath(), Path.of(URI.create((String) output.get("frameworkSource"))).toRealPath());
            System.out.println("MyBatis-Plus " + version + " NEVER optimistic oracle Java " + output.get("javaVersion") + " from " + executable);
            var rows = (List<List<Object>>) output.get("rows");
            return rows.stream().map(row -> List.<Object>of(normalize((String) row.getFirst()),
                    ((List<Object>) row.get(1)).stream().map(SqlValue.Known::new).toList())).toList();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static String readLog(Path path) {
        try { return Files.readString(path); }
        catch (Exception failure) { return failure.toString(); }
    }

    private static String normalize(String sql) { return sql.replaceAll("\\s+", " ").replaceAll("\\s*([(),=])\\s*", "$1").strip(); }
}
