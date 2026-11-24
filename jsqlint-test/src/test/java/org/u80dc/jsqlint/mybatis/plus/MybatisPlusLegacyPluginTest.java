package org.u80dc.jsqlint.mybatis.plus;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.u80dc.jsqlint.commons.compiler.CompiledProject;
import org.u80dc.jsqlint.commons.compiler.MavenProjectCompiler;
import org.u80dc.jsqlint.commons.location.InputLocationOptions;
import org.u80dc.jsqlint.commons.location.InputMode;
import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.node.SqlNodeRenderer.*;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.views.JavaView;

import java.io.File;
import java.io.ObjectInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 对照真实旧版插件与模型侧固定插件；旧运行时和 JDBC 代理在独立 JVM，不替换目标依赖。 */
@Isolated("Maven Embedder 使用进程共享状态")
class MybatisPlusLegacyPluginTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"3.0.7", "3.3.2"})
    void legacyOptimisticProtocolsKeepTheSameIntegerSqlAndBindings(String version) throws Exception {
        var project = compile(version);
        var rows = oracle(project, "optimistic", version);
        assertEquals(3, rows.size());
        var report = new StringBuilder();
        for (var row : rows) {
            assertEquals(version, row.get("frameworkVersion"));
            var before = snapshot(row, "before");var after = snapshot(row, "after");
            var config = new PlusPluginConfig(List.of(new PlusPluginConfig.OptimisticLock()));
            var transform = new PlusSqlTransform(config, Optional.empty(), false, false);
            var versionInput = Map.<String, Object>of("version", new SqlValue.Known(row.get("version")));
            BoundSql modeled = transform.apply(bound(before, versionInput), metadata("update", version, project), context(), reference -> new SqlValueOrigin.Input(reference.name()));
            report.append("target: ").append(version).append("\nversion: ").append(row.get("version"))
                    .append("\nprotocol: ").append(row.get("protocol")).append("\nparameter keys: ").append(row.get("parameterKeys"))
                    .append("\nentity keys: ").append(row.get("entityKeys")).append("\nbefore: ").append(before)
                    .append("\nlegacy SQL: ").append(after.get("sql")).append("\nlegacy values: ").append(values(after))
                    .append("\nfixed model SQL: ").append(modeled.rendered().sql()).append("\nfixed model values: ").append(modeled.bindings().stream().map(BoundParameter::value).toList()).append('\n');
            assertEquals(normalize((String) after.get("sql")), normalize(modeled.rendered().sql().orElseThrow()), report.toString());
            assertEquals(values(after).stream().map(SqlValue.Known::new).toList(), modeled.bindings().stream().map(BoundParameter::value).toList(), report.toString());
            boolean mappedEntity = version.equals("3.0.7") && row.get("version") != null;
            assertEquals(mappedEntity ? "et-map" : "entity", row.get("protocol"));
        }
        Files.createDirectories(Path.of("target"));Files.writeString(Path.of("target/legacy-optimistic-" + version + ".txt"), report);
    }

    @ParameterizedTest
    @ValueSource(strings = {"3.0.7", "3.3.2"})
    void legacyPaginationMatchesTheTargetsCountAndBindingProtocol(String version) throws Exception {
        var project = compile(version);
        var rows = oracle(project, "pagination", version);
        assertEquals(2, rows.size(), rows.toString());
        var count = rows.stream().filter(row -> row.get("kind").equals("pagination-count")).findFirst().orElseThrow();
        var main = rows.stream().filter(row -> row.get("kind").equals("pagination-main")).findFirst().orElseThrow();
        var config = new PlusPluginConfig(List.of(new PlusPluginConfig.Pagination(PlusPluginConfig.Dialect.MYSQL)));
        var page = Optional.of(new PlusSqlTransform.PageRequest(2, 10, true, true, true, Optional.empty(), "current", "size"));
        var metadata = metadata("select", version, project);
        var modeledCount = new PlusSqlTransform(config, page, true, true).apply(bound(snapshot(count, "before")), metadata, context(), reference -> new SqlValueOrigin.Input(reference.name()));
        var modeledMain = new PlusSqlTransform(config, page, true, false).apply(bound(snapshot(main, "before")), metadata, context(), reference -> new SqlValueOrigin.Input(reference.name()));
        String legacyCount = normalize((String) snapshot(count, "after").get("sql"));
        String currentCount = normalize(modeledCount.rendered().sql().orElseThrow());
        assertTrue(legacyCount.contains("COUNT(1)"), legacyCount);
        assertEquals(legacyCount, currentCount);
        assertEquals(values(snapshot(count, "after")).stream().map(SqlValue.Known::new).toList(), modeledCount.bindings().stream().map(BoundParameter::value).toList());
        assertEquals(normalize((String) snapshot(main, "after").get("sql")), normalize(modeledMain.rendered().sql().orElseThrow()));
        assertEquals(values(snapshot(main, "after")).stream().map(SqlValue.Known::new).toList(), modeledMain.bindings().stream().map(BoundParameter::value).toList());
        var report = "target: " + version + "\nlegacy count: " + legacyCount + "\nfixed model count: " + currentCount
                + "\nlegacy main: " + snapshot(main, "after") + "\nfixed model main: " + modeledMain.rendered().sql()
                + "\nfixed model values: " + modeledMain.bindings().stream().map(BoundParameter::value).toList() + '\n';
        Files.createDirectories(Path.of("target"));Files.writeString(Path.of("target/legacy-pagination-" + version + ".txt"), report);
    }

    private CompiledProject compile(String version) throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Path fixture = Path.of("src/test/resources/mybatis-plus-legacy-plugins").toAbsolutePath();
        try (var paths = Files.walk(fixture)) {
            for (Path source : paths.filter(Files::isRegularFile).filter(path -> !path.startsWith(fixture.resolve("target"))).toList()) {
                Path target = project.resolve(fixture.relativize(source));Files.createDirectories(target.getParent());Files.copy(source, target);
            }
        }
        return MavenProjectCompiler.compile(project, new InputLocationOptions(InputMode.MAVEN_PROJECT, List.of(),
                List.of("-Dplus.version=" + version), temporary.resolve("classes")));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> oracle(CompiledProject project, String operation, String version) throws Exception {
        var paths = new ArrayList<>(project.classDirectories());paths.addAll(project.dependencies());
        String classpath = String.join(File.pathSeparator, paths.stream().map(Path::toString).toList());
        Path output = temporary.resolve(operation + ".bin");Path log = temporary.resolve(operation + ".log");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
                "-cp", classpath, "legacyplugin.LegacyPluginOracle", operation, output.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly();fail("旧插件 oracle 超时：" + version); }
        assertEquals(0, process.exitValue(), Files.readString(log));
        try (var input = new ObjectInputStream(Files.newInputStream(output))) {
            return (List<Map<String, Object>>) input.readObject();
        }
    }

    private static Map<String, String> metadata(String command, String version, CompiledProject project) throws Exception {
        var metadata = new java.util.LinkedHashMap<>(Map.of("framework", "mybatis-plus", "frameworkVersion", version,
                "pluginFrameworkVersion", version, "command", command,
                "method", "<legacyplugin.Fixture$UserMapper: int updateById(java.lang.Object)>", "versionColumn", "rev", "versionProperty", "version"));
        var locations = new ArrayList<JavaClassPathAnalysisInputLocation>();
        try {
            for (Path dependency : project.dependencies()) locations.add(new JavaClassPathAnalysisInputLocation(dependency.toString(),SourceType.Library));
            var defaults = PlusPaginationDefaults.read(new JavaView(List.copyOf(locations))).orElseThrow();
            metadata.putAll(defaults);
        } finally {
            for (var location : locations) location.close();
        }
        return Map.copyOf(metadata);
    }

    private static SqlEvaluationContext context() { return SqlEvaluationContext.defaults(() -> {}, ignored -> {}); }

    private static BoundSql bound(Map<String, Object> snapshot) {
        return bound(snapshot, Map.of());
    }

    private static BoundSql bound(Map<String, Object> snapshot, Map<String, Object> inputs) {
        var parameters = new ArrayList<BoundParameter>();
        for (var binding : bindings(snapshot)) {
            var value = new SqlValue.Known(binding.get("value"));
            parameters.add(new BoundParameter(parameters.size() + 1, (String) binding.get("expression"), (String) binding.get("type"), "", value, new SqlValueOrigin.Literal(value)));
        }
        return new BoundSql(new RenderedSql(Optional.of((String) snapshot.get("sql")), ValueSqlNode.raw("legacySnapshot"), inputs, List.of()), parameters);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> snapshot(Map<String, Object> row, String key) { return (Map<String, Object>) row.get(key); }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> bindings(Map<String, Object> snapshot) { return (List<Map<String, Object>>) snapshot.get("bindings"); }

    private static List<Object> values(Map<String, Object> snapshot) { return bindings(snapshot).stream().map(binding -> binding.get("value")).toList(); }

    private static String normalize(String sql) { return sql.replaceAll("\\s+", " ").replaceAll("\\s*([(),=])\\s*", "$1").strip(); }
}
