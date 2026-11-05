package org.u80dc.jsqlint.commons.location;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.u80dc.jsqlint.core.AnalysisListener;
import org.u80dc.jsqlint.core.AnalysisOptions;
import org.u80dc.jsqlint.core.Analyzer;
import org.u80dc.jsqlint.core.spi.AnalysisInputResolver;
import org.u80dc.jsqlint.core.spi.PreparedInput;
import sootup.core.frontend.SootClassSource;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.interceptor.BodyInterceptor;
import sootup.core.model.SourceType;
import sootup.core.types.ClassType;
import sootup.core.views.View;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.u80dc.jsqlint.core.AnalysisResult.Status.CANCELLED;
import static org.u80dc.jsqlint.core.AnalysisResult.Status.FAILED;
import static org.u80dc.jsqlint.core.AnalysisResult.Status.SUCCESS;

/**
 * 验证 Analyzer 的输出标识、目录清理及完整路径传递。
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
    void analyzerCleansPreviousOutputBeforeReusingId() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source"));
        Files.writeString(source.resolve("Sample.java"), "public class Sample {}");
        Path obsolete = Files.writeString(source.resolve("Old.java"), "public class Old {}");
        Path output = defaultRoot().resolve("caller-classes");
        var options = AnalysisOptions.defaults().withClassOutputId("caller-classes");
        var analyzer = new Analyzer();
        assertEquals(SUCCESS, analyzer.analyze(source, null, options).status());
        assertTrue(Files.isRegularFile(output.resolve("Sample.class")));
        assertTrue(Files.isRegularFile(output.resolve("Old.class")));
        Files.delete(obsolete);
        Files.writeString(Files.createDirectories(output.resolve("nested/deeper")).resolve("old.txt"), "old");
        Path sibling = Files.writeString(Files.createDirectories(defaultRoot().resolve("another-id")).resolve("keep.txt"), "keep");
        assertEquals(SUCCESS, analyzer.analyze(source, null, options).status());
        assertEquals(2, outputs().size());
        assertTrue(Files.isRegularFile(output.resolve("Sample.class")));
        assertFalse(Files.exists(output.resolve("Old.class")));
        assertFalse(Files.exists(output.resolve("nested")));
        assertEquals("keep", Files.readString(sibling));
    }

    @Test
    void cleanupRemovesNamedAndRandomOutputsOnlyAfterAnalysis() throws Exception {
        Path source = Files.writeString(temporary.resolve("Sample.java"), "public class Sample {}");
        for (String id : new String[]{"cleanup", null}) {
            List<Path> observed = new ArrayList<>();
            AnalysisListener listener = new AnalysisListener() {
                @Override public void onProgress(Progress progress) {
                    if (progress.stage() == Stage.RESOURCES) {
                        try {
                            Path output = outputs().getFirst();
                            assertTrue(Files.isRegularFile(output.resolve("Sample.class")));
                            if (observed.isEmpty()) observed.add(output);
                            else assertEquals(observed.getFirst(), output);
                        } catch (IOException e) { throw new RuntimeException(e); }
                    }
                    if (progress.stage() == Stage.FINISHED) {
                        assertEquals(1, observed.size());
                        assertFalse(Files.exists(observed.getFirst()));
                    }
                }
            };
            var options = new AnalysisOptions(Duration.ZERO, 32, 128, () -> false, listener)
                    .withCleanupClassOutput(true).withClassOutputId(id);
            assertEquals(SUCCESS, new Analyzer().analyze(source, null, options).status());
            assertTrue(outputs().isEmpty());
        }
        assertTrue(Files.isRegularFile(source));
    }

    @Test
    void compilationFailureHonorsCleanupOptionAndNeverKeepsStaleClasses() throws Exception {
        Path source = Files.writeString(temporary.resolve("Sample.java"), "public class Sample { invalid }");
        for (boolean cleanup : List.of(false, true)) {
            String id = "failed-" + cleanup;
            Path output = Files.createDirectories(defaultRoot().resolve(id));
            Files.writeString(output.resolve("Old.class"), "stale");
            var options = AnalysisOptions.defaults().withClassOutputId(id).withCleanupClassOutput(cleanup);
            assertEquals(FAILED, new Analyzer().analyze(source, null, options).status());
            assertEquals(!cleanup, Files.isDirectory(output));
            assertFalse(Files.exists(output.resolve("Old.class")));
        }
        assertTrue(Files.isRegularFile(source));
    }

    @Test
    void cancellationAndCloseFailureStillCleanOutputsAfterClosingInputs() throws Exception {
        Path source = Files.writeString(temporary.resolve("fixture.input"), "input");
        for (boolean closeFails : List.of(false, true)) {
            AtomicBoolean cancelled = new AtomicBoolean();
            AtomicBoolean closed = new AtomicBoolean();
            Path output = defaultRoot().resolve("cancelled-" + closeFails);
            var resolver = new AnalysisInputResolver() {
                public boolean supports(Path path) { return true; }
                public boolean requiresCompilation(Path path) { return true; }
                public PreparedInput resolve(Path path, Runnable checkpoint) { throw new UnsupportedOperationException(); }
                public PreparedInput resolve(Path path, Path classes, Runnable checkpoint) throws IOException {
                    Path marker = Files.writeString(classes.resolve("partial.txt"), "compiled");
                    var location = new EmptyLocation() {
                        @Override public void close() throws IOException {
                            // SootUp 关闭输入时仍可访问产物，清理不能提前执行。
                            assertTrue(Files.isRegularFile(marker));
                            closed.set(true);
                            if (closeFails) throw new IOException("close failed");
                        }
                    };
                    cancelled.set(true);
                    return new PreparedInput(List.of(location), List.of());
                }
            };
            var options = new AnalysisOptions(Duration.ZERO, 32, 128, cancelled::get, AnalysisListener.NONE)
                    .withClassOutputId(output.getFileName().toString()).withCleanupClassOutput(true);
            assertEquals(closeFails ? FAILED : CANCELLED,
                    new Analyzer(List.of(), List.of(resolver)).analyze(source, null, options).status());
            assertTrue(closed.get());
            assertFalse(Files.exists(output));
        }
    }

    @Test
    void listenerFailureStillCleansCompiledOutput() throws Exception {
        Path source = Files.writeString(temporary.resolve("Sample.java"), "public class Sample {}");
        Path output = defaultRoot().resolve("listener-failed");
        AnalysisListener listener = new AnalysisListener() {
            @Override public void onProgress(Progress progress) {
                if (progress.stage() == Stage.RESOURCES) {
                    assertTrue(Files.isRegularFile(output.resolve("Sample.class")));
                    throw new IllegalStateException("listener failed");
                }
            }
        };
        var options = new AnalysisOptions(Duration.ZERO, 32, 128, () -> false, listener)
                .withClassOutputId("listener-failed").withCleanupClassOutput(true);
        assertEquals(FAILED, new Analyzer().analyze(source, null, options).status());
        assertFalse(Files.exists(output));
    }

    @Test
    void cleanupFailureIsReportedWithoutDeletingReplacementFile() throws Exception {
        Path source = Files.writeString(temporary.resolve("fixture.input"), "input");
        Path output = defaultRoot().resolve("blocked");
        List<String> diagnostics = new ArrayList<>();
        var resolver = new AnalysisInputResolver() {
            public boolean supports(Path path) { return true; }
            public boolean requiresCompilation(Path path) { return true; }
            public PreparedInput resolve(Path path, Runnable checkpoint) {
                var location = new EmptyLocation() {
                    @Override public void close() throws IOException {
                        Files.delete(output);
                        Files.writeString(output, "replacement");
                    }
                };
                return new PreparedInput(List.of(location), List.of());
            }
        };
        AnalysisListener listener = new AnalysisListener() {
            @Override public void onDiagnostic(String code, String message) { diagnostics.add(message); }
        };
        var options = new AnalysisOptions(Duration.ZERO, 32, 128, () -> false, listener)
                .withClassOutputId("blocked").withCleanupClassOutput(true);
        assertEquals(FAILED, new Analyzer(List.of(), List.of(resolver)).analyze(source, null, options).status());
        assertTrue(diagnostics.stream().anyMatch(message -> message.contains("without redirection")));
        assertEquals("replacement", Files.readString(output));
    }

    @Test
    void cleanupDoesNotFollowLinksOutsideAllocatedDirectory() throws Exception {
        Path source = Files.writeString(temporary.resolve("Sample.java"), "public class Sample {}");
        Path external = Files.createDirectories(temporary.resolve("external"));
        Path marker = Files.writeString(external.resolve("keep.txt"), "keep");
        Path output = Files.createDirectories(defaultRoot().resolve("links"));
        Path link = output.resolve("external");
        try {
            Files.createSymbolicLink(link, external);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "当前环境无法创建符号链接：" + e.getMessage());
        }
        var options = AnalysisOptions.defaults().withClassOutputId("links").withCleanupClassOutput(true);
        assertEquals(SUCCESS, new Analyzer().analyze(source, null, options).status());
        assertFalse(Files.exists(output));
        assertEquals("keep", Files.readString(marker));

        // 输出目录本身被链接重定向时应拒绝清理，不能把链接目标当作输出目录。
        Files.createSymbolicLink(output, external);
        try {
            assertEquals(FAILED, new Analyzer().analyze(source, null, options).status());
            assertEquals("keep", Files.readString(marker));
        } finally {
            Files.delete(output);
        }
    }

    @Test
    void outputIdMustBeASingleDirectoryName() {
        for (String id : List.of("", " ", ".", "..", "../escape", "..\\escape", "nested/path",
                "nested\\path", temporary.toString(), "name.", "name ")) {
            assertThrows(IllegalArgumentException.class,
                    () -> AnalysisOptions.defaults().withClassOutputId(id), id);
        }
        assertNull(AnalysisOptions.defaults().withClassOutputId("sample").withClassOutputId(null).classOutputId());
        assertFalse(AnalysisOptions.defaults().cleanupClassOutput());
        assertFalse(AnalysisOptions.defaults().withCleanupClassOutput(true)
                .withClassOutputId("sample").withCleanupClassOutput(false).cleanupClassOutput());
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
                AnalysisOptions.defaults().withClassOutputId("unused").withCleanupClassOutput(true)).status());
        assertFalse(Files.exists(defaultRoot()));
        Path existing = Files.writeString(Files.createDirectories(defaultRoot().resolve("unused")).resolve("keep.txt"), "keep");
        assertEquals(SUCCESS, new Analyzer().analyze(classes, null,
                AnalysisOptions.defaults().withClassOutputId("unused").withCleanupClassOutput(true)).status());
        assertEquals("keep", Files.readString(existing));
    }

    @Test
    void outputContainingSourceOrDependencyIsRejectedBeforeDeletingAnything() throws Exception {
        Path output = Files.createDirectories(defaultRoot().resolve("protected"));
        Path source = Files.writeString(output.resolve("Sample.java"), "public class Sample {}");
        var options = AnalysisOptions.defaults().withClassOutputId("protected").withCleanupClassOutput(true);
        assertEquals(FAILED, new Analyzer().analyze(source, null, options).status());
        assertEquals("public class Sample {}", Files.readString(source));

        Path externalSource = Files.writeString(temporary.resolve("External.java"), "public class External {}");
        Path dependency = Files.createDirectories(output.resolve("libraries"));
        Path marker = Files.writeString(dependency.resolve("keep.txt"), "dependency");
        assertEquals(FAILED, new Analyzer().analyze(externalSource, dependency, options).status());
        assertEquals("dependency", Files.readString(marker));
        assertTrue(Files.isRegularFile(source));
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

    private static class EmptyLocation implements AnalysisInputLocation {
        public SourceType getSourceType() { return SourceType.Application; }
        public List<BodyInterceptor> getBodyInterceptors() { return List.of(); }
        public Optional<? extends SootClassSource> getClassSource(ClassType type, View view) { return Optional.empty(); }
        public Stream<? extends SootClassSource> getClassSources(View view) { return Stream.empty(); }
        public void close() throws IOException {}
    }
}
