package org.u80dc.jsqlint.mybatis.plus;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.u80dc.jsqlint.commons.compiler.CompiledProject;
import org.u80dc.jsqlint.commons.compiler.MavenProjectCompiler;
import org.u80dc.jsqlint.commons.location.InputLocationOptions;
import org.u80dc.jsqlint.commons.location.InputMode;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.mybatis.MybatisAnalysisModule;
import org.u80dc.jsqlint.mybatis.MybatisOptions;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.DefaultRuntimeAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;

import java.lang.reflect.InvocationTargetException;
import java.io.File;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** 真实依赖/字节码/BoundSql对照；oracle.javaHome可全局或逐版本指定兼容JDK，不连接数据库。 */
@Tag("integration")
@Isolated("Maven Embedder 使用进程共享状态")
class MybatisPlusVersionMatrixTest {
    private static final String DEFAULT_VERSIONS = "3.0-RELEASE,3.0.7,3.1.2,3.2.0,3.3.2,3.4.3.4,3.5.0,3.5.3.2,3.5.6,3.5.9,3.5.16,3.5.18.1";
    private static final String RESOURCE = "mybatis-plus-version-matrix/";
    private static final Set<String> CASES = Set.of(
            "plainSelectById", "plainSelectNull", "plainSelectQuery", "plainSelectLambda",
            "plainCountNull", "plainCountSelected", "plainSelectObjs", "plainInsert", "plainInsertAutoId", "plainUpdateById", "plainDeleteById",
            "logicSelectById", "logicSelectNull", "logicSelectQuery", "logicSelectLambda",
            "logicCountNull", "logicCountSelected", "logicSelectObjs", "logicInsert", "logicInsertAutoId", "logicUpdateById", "logicDeleteById");

    @TempDir Path temporary;

    static Stream<String> versions() {
        return Arrays.stream(System.getProperty("jsqlint.plus.versions", DEFAULT_VERSIONS).split(","))
                .map(String::strip).filter(version -> !version.isEmpty()).distinct().peek(version -> {
                    if (!version.startsWith("3.") || !version.matches("[A-Za-z0-9._-]+"))
                        throw new IllegalArgumentException("Expected a published MyBatis-Plus 3.x version: " + version);
                });
    }

    @ParameterizedTest(name = "MyBatis-Plus {0}: real CRUD/Wrapper SQL and bindings")
    @MethodSource("versions")
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void supportedThreeXVersionsMatchTheirActualBoundSql(String version) throws Exception {
        var report = new StringBuilder("target version: ").append(version).append('\n');
        try {
            CompiledProject project = compile(version);
            report.append("class directories: ").append(project.classDirectories()).append("\ndependencies: ")
                    .append(project.dependencies()).append('\n');
            AnalysisResult result = analyze(project);
            appendResult(report, result);
            String oracleHome = oracleJavaHome(version);
            report.append("oracle configured Java home: ").append(oracleHome.isEmpty() ? "<current JVM>" : oracleHome).append('\n');
            Map<String, List<Map<String, Object>>> expected = oracle(project, version, report);
            report.append("runtime oracle: ").append(new TreeMap<>(expected)).append('\n');

            assertTrue(result.status() == AnalysisResult.Status.SUCCESS || result.status() == AnalysisResult.Status.PARTIAL,
                    result.diagnostics().toString());
            assertTrue(result.diagnostics().stream().allMatch(diagnostic -> Set.of("SQL_DATA_FLOW_INCOMPLETE",
                    "JOINT_ANALYSIS_INCOMPLETE").contains(diagnostic.code())), result.diagnostics().toString());
            assertTrue(result.definitions().isEmpty());
            assertEquals(CASES, expected.keySet(), "Oracle 必须执行全部公开受控 Mapper 调用");
            assertEquals(CASES.size(), result.calls().size(), result.diagnostics().toString());
            Map<String, SqlExtraction> calls = new LinkedHashMap<>();
            for (var call : result.calls()) {
                String caller = call.callSite().caller().orElseThrow().getName();
                assertNull(calls.put(caller, call), "每个场景应有一个独立 SQL 调用");
                assertEquals("mybatis", call.moduleId());
                assertTrue(call.issues().isEmpty(), call.issues().toString());
                assertEquals(1, call.statements().size());
                var definition = call.statements().getFirst().definition().orElseThrow();
                assertTrue(definition.complete(), definition.issues().toString());
                assertEquals(version, definition.metadata().get("frameworkVersion"));
                assertEquals(caller.startsWith("logic") ? "matrix.LogicNodeMapper" : "matrix.NodeMapper",
                        definition.metadata().get("mapperType"));
                assertEquals(1, call.samples().size(), caller);
                var sample = call.samples().getFirst();
                assertTrue(sample.sql().isPresent(), sample.issues().toString());
                assertTrue(sample.bindings().stream().allMatch(binding -> binding.value() instanceof SqlValue.Known),
                        sample.bindings().toString());
                assertEquals(1, expected.get(caller).size(), "一次 Mapper 调用应提交一次 SqlSession 操作：" + caller);
                var snapshot = expected.get(caller).getFirst();
                assertEquals(normalize((String) snapshot.get("sql")), normalize(sample.sql().orElseThrow()),
                        () -> version + " " + caller + " SQL 与目标版本 BoundSql 不一致");
                var bindings = ((List<?>) snapshot.get("bindings")).stream().map(SqlValue.Known::new).toList();
                assertEquals(bindings, sample.bindings().stream().map(SqlSample.Binding::value).toList(),
                        version + " " + caller + " 绑定顺序和值必须匹配目标版本");
            }
            assertEquals(CASES, calls.keySet());
            report.append("verified calls: ").append(calls.size()).append("\nverification: PASSED\n");
        } catch (Exception | Error failure) {
            report.append("verification: FAILED\nerror: ").append(failure).append('\n');
            throw failure;
        } finally {
            Path directory = Files.createDirectories(Path.of("target/mybatis-plus-version-matrix"));
            Files.writeString(directory.resolve(version + ".txt"), report);
            System.out.println("MyBatis-Plus " + version + " matrix report: " + directory.resolve(version + ".txt"));
        }
    }

    private CompiledProject compile(String version) throws Exception {
        Path root = Files.createDirectories(temporary.resolve("project"));
        for (String name : List.of("pom.xml", "src/main/java/matrix/Fixture.java", "src/main/java/matrix/RuntimeOracle.java")) {
            Path file = root.resolve(name);
            Files.createDirectories(file.getParent());
            try (var input = getClass().getClassLoader().getResourceAsStream(RESOURCE + name)) {
                assertNotNull(input, "Missing matrix fixture resource: " + name);
                Files.copy(input, file);
            }
        }
        return MavenProjectCompiler.compile(root, new InputLocationOptions(InputMode.MAVEN_PROJECT, List.of(),
                List.of("-Dplus.version=" + version), temporary.resolve("classes")));
    }

    private static AnalysisResult analyze(CompiledProject project) throws Exception {
        var locations = new ArrayList<JavaClassPathAnalysisInputLocation>();
        try (var runtime = new DefaultRuntimeAnalysisInputLocation()) {
            for (Path path : project.classDirectories()) locations.add(new JavaClassPathAnalysisInputLocation(path.toString(), SourceType.Application));
            for (Path path : project.dependencies()) locations.add(new JavaClassPathAnalysisInputLocation(path.toString(), SourceType.Library));
            var inputs = new ArrayList<AnalysisInputLocation>(locations);
            inputs.add(runtime);
            return new Analyzer(AnalyzerConfig.defaults().withTimeout(Duration.ofMinutes(3)),
                    List.of(new MybatisAnalysisModule(MybatisOptions.defaults(), List.of(new MybatisPlusExtension()))), List.of())
                    .analyze(new AnalysisRequest(inputs, List.of()));
        } finally {
            for (var location : locations) location.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<Map<String, Object>>> oracle(CompiledProject project, String version,
                                                               StringBuilder report) throws Exception {
        String javaHome = oracleJavaHome(version);
        if (!javaHome.isEmpty()) return processOracle(project, version, Path.of(javaHome), report);
        report.append("oracle mode: isolated classloader\noracle Java version: ").append(System.getProperty("java.version"))
                .append("\noracle runtime Java home: ").append(System.getProperty("java.home")).append('\n');
        var paths = new ArrayList<>(project.classDirectories());
        paths.addAll(project.dependencies());
        var urls = new ArrayList<URL>();
        for (Path path : paths) urls.add(path.toUri().toURL());
        Path core = project.dependencies().stream().filter(path -> path.getFileName().toString()
                .equals("mybatis-plus-core-" + version + ".jar")).findFirst().orElseThrow();
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (var loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> baseMapper = loader.loadClass("com.baomidou.mybatisplus.core.mapper.BaseMapper");
            assertSame(loader, baseMapper.getClassLoader(), "Oracle 不能继承分析器运行时的 MyBatis-Plus");
            assertEquals(core.toRealPath(), Path.of(baseMapper.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
            report.append("oracle framework source: ").append(baseMapper.getProtectionDomain().getCodeSource().getLocation()).append('\n');
            try {
                return (Map<String, List<Map<String, Object>>>) loader.loadClass("matrix.RuntimeOracle")
                        .getMethod("snapshots").invoke(null);
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof Exception exception) throw exception;
                if (failure.getCause() instanceof Error error) throw error;
                throw failure;
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<Map<String, Object>>> processOracle(CompiledProject project, String version,
                                                                      Path javaHome, StringBuilder report) throws Exception {
        Path executable = javaHome.toAbsolutePath().normalize().resolve("bin")
                .resolve(System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        assertTrue(Files.isRegularFile(executable), "Oracle Java executable does not exist: " + executable);
        var paths = new ArrayList<>(project.classDirectories());
        paths.addAll(project.dependencies());
        String classpath = String.join(File.pathSeparator, paths.stream().map(Path::toString).toList());
        Path snapshots = project.directory().resolve("oracle-snapshots.bin");
        Path logs = Files.createDirectories(Path.of("target/mybatis-plus-version-matrix")).toAbsolutePath().normalize();
        Path log = logs.resolve(version + "-oracle.log");
        Process process = new ProcessBuilder(executable.toString(), "-Dfile.encoding=UTF-8", "-cp", classpath,
                "matrix.RuntimeOracle", snapshots.toString()).directory(project.directory().toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), () -> "Target Oracle timed out after 60 seconds: " + readLog(log));
            assertEquals(0, process.exitValue(), () -> "Target Oracle failed: " + readLog(log));
            assertTrue(Files.isRegularFile(snapshots), "Target Oracle did not write its snapshot: " + readLog(log));
            Map<String, Object> output;
            try (var input = new ObjectInputStream(Files.newInputStream(snapshots))) {
                input.setObjectInputFilter(info -> {
                    if (info.depth() > 32 || info.references() > 10_000 || info.streamBytes() > 4_194_304)
                        return ObjectInputFilter.Status.REJECTED;
                    Class<?> type = info.serialClass();
                    if (type == null) return ObjectInputFilter.Status.UNDECIDED;
                    while (type.isArray()) type = type.getComponentType();
                    return type.isPrimitive() || type.getName().startsWith("java.lang.") || type.getName().startsWith("java.util.")
                            ? ObjectInputFilter.Status.ALLOWED : ObjectInputFilter.Status.REJECTED;
                });
                output = (Map<String, Object>) input.readObject();
            }
            Path core = project.dependencies().stream().filter(path -> path.getFileName().toString()
                    .equals("mybatis-plus-core-" + version + ".jar")).findFirst().orElseThrow();
            assertEquals(core.toRealPath(), Path.of(URI.create((String) output.get("frameworkSource"))).toRealPath(),
                    "独立Oracle必须加载目标版本的真实框架JAR");
            report.append("oracle mode: independent JVM\noracle Java version: ").append(output.get("javaVersion"))
                    .append("\noracle runtime Java home: ").append(output.get("javaHome"))
                    .append("\noracle framework source: ").append(output.get("frameworkSource"))
                    .append("\noracle executable: ").append(executable).append('\n');
            System.out.println("MyBatis-Plus " + version + " Oracle Java " + output.get("javaVersion") + " from " + executable);
            return (Map<String, List<Map<String, Object>>>) output.get("snapshots");
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static String readLog(Path path) {
        try { return Files.readString(path); }
        catch (Exception failure) { return "Cannot read " + path + ": " + failure; }
    }

    private static String oracleJavaHome(String version) {
        return System.getProperty("jsqlint.plus.oracle.javaHome." + version,
                System.getProperty("jsqlint.plus.oracle.javaHome", "")).strip();
    }

    private static void appendResult(StringBuilder report, AnalysisResult result) {
        report.append("analysis status: ").append(result.status()).append("\ndiagnostics: ").append(result.diagnostics()).append('\n');
        for (var call : result.calls()) {
            report.append("call: ").append(call.callSite()).append("\ncall issues: ").append(call.issues()).append('\n');
            for (var statement : call.statements()) statement.definition().ifPresent(definition -> report
                    .append("metadata: ").append(new TreeMap<>(definition.metadata())).append("\ndefinition issues: ")
                    .append(definition.issues()).append('\n'));
            for (var sample : call.samples()) report.append("SQL: ").append(sample.sql().orElse("<unknown>"))
                    .append("\nbindings: ").append(sample.bindings().stream().map(binding ->
                            binding.key() + "[" + binding.type() + "]=" + binding.value()).toList())
                    .append("\nassessment: ").append(sample.assessment())
                    .append("\nissues: ").append(sample.issues()).append('\n');
        }
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").replaceAll("\\s*([(),=])\\s*", "$1").strip();
    }
}
