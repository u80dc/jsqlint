package org.u80dc.jsqlint.commons.location;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.u80dc.jsqlint.core.AnalysisOptions;
import org.u80dc.jsqlint.core.Analyzer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.AnalysisResult.Status.FAILED;
import static org.u80dc.jsqlint.core.AnalysisResult.Status.SUCCESS;

/**
 * 验证 Analyzer 的输出标识、随机目录隔离及完整路径传递。
 *
 * @author u80dc
 */
@Isolated("测试期间覆盖 java.io.tmpdir，避免影响其他测试的临时目录")
class AutoCompileInputResolverTest {
    @TempDir Path temporary;
    private String previousTemporaryDirectory;

    @BeforeEach
    void useTestTemporaryDirectory() {
        previousTemporaryDirectory = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", temporary.resolve("system-temp").toString());
    }

    @AfterEach
    void restoreTemporaryDirectory() {
        if (previousTemporaryDirectory == null) System.clearProperty("java.io.tmpdir");
        else System.setProperty("java.io.tmpdir", previousTemporaryDirectory);
    }

    @Test
    void analyzerUsesIndependentTemporaryOutputsForRepeatedAnalysis() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source"));
        Files.writeString(source.resolve("Sample.java"), "public class Sample { public int value() { return 1; } }");
        Files.writeString(source.resolve("Old.java"), "public class Old {}");
        // 使用真实 ServiceLoader 调用链，复用同一个 Analyzer 及其解析器。
        var analyzer = new Analyzer();
        assertEquals(SUCCESS, analyzer.analyze(source, null).status());
        Path first = outputs().getFirst();
        assertTrue(Files.isRegularFile(first.resolve("Sample.class")));
        assertTrue(Files.isRegularFile(first.resolve("Old.class")));

        Files.delete(source.resolve("Old.java"));
        Files.writeString(source.resolve("Sample.java"), "public class Sample { public int value() { return 2; } }");
        assertEquals(SUCCESS, analyzer.analyze(source, null).status());
        List<Path> outputs = outputs();
        assertEquals(2, outputs.size());
        Path second = outputs.stream().filter(path -> !path.equals(first)).findFirst().orElseThrow();
        assertFalse(Files.exists(second.resolve("Old.class")));
        assertFalse(Files.exists(source.resolve("Sample.class")));
        try (var input = new AutoCompileAnalysisInputLocation(second)) {
            var view = input.createView();
            var clazz = view.getClass(view.getIdentifierFactory().getClassType("Sample")).orElseThrow();
            assertTrue(clazz.getMethods().stream().filter(method -> method.getName().equals("value"))
                    .findFirst().orElseThrow().getBody().toString().contains("return 2"));
        }
        assertTrue(Files.isRegularFile(first.resolve("Old.class")));
    }

    @Test
    void directResolverUsesConfiguredOutputDirectory() throws Exception {
        Path source = Files.writeString(temporary.resolve("Sample.java"), "public class Sample {}");
        Path output = temporary.resolve("explicit-classes");
        var options = InputLocationOptions.defaults().withClassOutputDirectory(output);
        try (var prepared = new AutoCompileInputResolver(options).resolve(source, () -> {})) {
            assertFalse(prepared.locations().isEmpty());
            assertTrue(Files.isRegularFile(output.resolve("Sample.class")));
        }
        assertFalse(Files.exists(defaultRoot()));
    }

    @Test
    void analyzerPassesPerCallOutputAndDoesNotChangeResolverConfiguration() throws Exception {
        Path source = Files.writeString(temporary.resolve("Sample.java"), "public class Sample {}");
        Path configured = temporary.resolve("configured-classes");
        var resolver = new AutoCompileInputResolver(InputLocationOptions.defaults().withClassOutputDirectory(configured));
        var analyzer = new Analyzer(List.of(), List.of(resolver));
        for (String id : List.of("first-classes", "第二次编译")) {
            assertEquals(SUCCESS, analyzer.analyze(source, null,
                    AnalysisOptions.defaults().withClassOutputId(id)).status());
            assertTrue(Files.isRegularFile(defaultRoot().resolve(id).resolve("Sample.class")));
            assertFalse(Files.exists(configured));
        }
        // 不指定标识时也必须由 Analyzer 生成路径，不能回退到解析器配置的完整路径。
        assertEquals(SUCCESS, analyzer.analyze(source, null).status());
        assertEquals(3, outputs().size());
        assertFalse(Files.exists(configured));
        // Analyzer 没有修改解析器的配置，直接调用解析器时仍能使用原路径。
        try (var prepared = resolver.resolve(source, () -> {})) {
            assertFalse(prepared.locations().isEmpty());
            assertTrue(Files.isRegularFile(configured.resolve("Sample.class")));
        }
    }

    @Test
    void analyzerForwardsOutputThroughDefaultServiceLoader() throws Exception {
        Path source = Files.writeString(temporary.resolve("Sample.java"), "public class Sample {}");
        Path output = defaultRoot().resolve("caller-classes");
        var options = AnalysisOptions.defaults().withClassOutputId("caller-classes");
        var analyzer = new Analyzer();
        assertEquals(SUCCESS, analyzer.analyze(source, null, options).status());
        assertTrue(Files.isRegularFile(output.resolve("Sample.class")));
        Files.writeString(output.resolve("keep.txt"), "caller-owned");
        assertEquals(SUCCESS, analyzer.analyze(source, null, options).status());
        assertEquals(List.of(output), outputs());
        assertEquals("caller-owned", Files.readString(output.resolve("keep.txt")));
    }

    @Test
    void outputIdMustBeASingleDirectoryName() {
        for (String id : List.of("", " ", ".", "..", "../escape", "..\\escape", "nested/path",
                "nested\\path", temporary.toString(), "name.", "name ")) {
            assertThrows(IllegalArgumentException.class,
                    () -> AnalysisOptions.defaults().withClassOutputId(id), id);
        }
        assertNull(AnalysisOptions.defaults().withClassOutputId("sample").withClassOutputId(null).classOutputId());
        assertFalse(Files.exists(defaultRoot()));
    }

    @Test
    void analyzerDoesNotCreateOutputDirectoryForBytecodeInput() throws Exception {
        Path source = Files.writeString(temporary.resolve("Sample.java"), "public class Sample {}");
        Path classes = temporary.resolve("classes");
        try (var input = new AutoCompileAnalysisInputLocation(source,
                InputLocationOptions.defaults().withClassOutputDirectory(classes))) {
            assertEquals(classes, input.getClassOutputDirectory());
        }
        assertEquals(SUCCESS, new Analyzer().analyze(classes, null,
                AnalysisOptions.defaults().withClassOutputId("unused")).status());
        assertFalse(Files.exists(defaultRoot()));
    }

    @Test
    void cannotCreateTemporaryOutputFailsWithoutCompilingIntoSourceDirectory() throws Exception {
        Path source = Files.writeString(temporary.resolve("Sample.java"), "public class Sample {}");
        Files.createDirectories(defaultRoot().getParent());
        Files.writeString(defaultRoot(), "existing-file");
        assertEquals(FAILED, new Analyzer().analyze(source, null).status());
        assertEquals("existing-file", Files.readString(defaultRoot()));
        assertFalse(Files.exists(source.resolveSibling("Sample.class")));
    }

    private Path defaultRoot() {
        return temporary.resolve("system-temp/.jsqlint");
    }

    private List<Path> outputs() throws IOException {
        try (var paths = Files.list(defaultRoot())) {
            return paths.filter(Files::isDirectory).toList();
        }
    }
}
