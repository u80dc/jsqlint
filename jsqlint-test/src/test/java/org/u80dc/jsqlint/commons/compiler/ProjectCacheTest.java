package org.u80dc.jsqlint.commons.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.u80dc.jsqlint.commons.location.BuildPolicy;
import org.u80dc.jsqlint.commons.location.InputLocationOptions;
import org.u80dc.jsqlint.commons.location.InputMode;
import org.u80dc.jsqlint.core.AnalysisStatistics;
import org.u80dc.jsqlint.core.AnalysisStatisticsOptions;
import org.u80dc.jsqlint.core.spi.AnalysisInputContext;
import org.u80dc.jsqlint.core.statistics.StatisticsRecorder;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.AnalysisStatistics.Phase.COMPILATION;

/** Uses real class files and a counted backend without invoking Maven or Gradle. */
class ProjectCacheTest {
    @TempDir Path temporary;
    private Path project;
    private Path sources;
    private Path fixture;
    private Path resource;
    private Path cache;
    private InputLocationOptions options;
    private CountingBuilder builder;
    private final AtomicLong clock = new AtomicLong(1_000);

    @BeforeEach
    void createProject() throws IOException {
        project = Files.createDirectories(temporary.resolve("project"));
        sources = Files.createDirectories(project.resolve("src/main/java/cachefixture"));
        fixture = Files.writeString(sources.resolve("Fixture.java"), fixtureSource("SELECT 1"));
        Files.writeString(project.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>fixture</groupId><artifactId>application</artifactId><version>1</version>
                </project>
                """);
        resource = Files.writeString(Files.createDirectories(project.resolve("src/main/resources"))
                .resolve("query.sql"), "SELECT 1");
        cache = temporary.resolve("project-cache");
        options = new InputLocationOptions(InputMode.MAVEN_PROJECT, List.of(), List.of(), null)
                .withBuildPolicy(BuildPolicy.AUTO).withProjectCacheDirectory(cache)
                .withCacheMaxAge(Duration.ZERO);
        builder = new CountingBuilder(List.of());
    }

    @Test
    void coldAutoWarmAutoAndReuseShareOneSnapshotAndOnlyColdBuildRecordsCompilation() throws Exception {
        Path firstRequestedOutput = temporary.resolve("first-request-output");
        Path secondRequestedOutput = temporary.resolve("second-request-output");
        Run first = run(options.withClassOutputDirectory(firstRequestedOutput));
        Map<String, String> published = fileTree(cache);
        Run warm = run(options.withClassOutputDirectory(secondRequestedOutput));
        Run reused = run(options.withBuildPolicy(BuildPolicy.REUSE));

        assertEquals(1, builder.calls.get());
        assertEquals(first.project(), warm.project());
        assertEquals(first.project(), reused.project());
        assertEquals("SELECT 1", sql(reused.project()));
        assertEquals("SELECT 1", Files.readString(reused.project().resourceRoots().getFirst().resolve("query.sql")));
        assertEquals(published, fileTree(cache), "Cache hits must not rewrite the published snapshot");
        assertFalse(Files.exists(firstRequestedOutput));
        assertFalse(Files.exists(secondRequestedOutput));
        assertEquals(Set.of(generation(first.project())), generations());
        assertTrue(first.statistics().phases().get(COMPILATION).invocations() > 0);
        assertMeasurement(first.statistics(), "project.cache.miss", 1);
        for (Run hit : List.of(warm, reused)) {
            assertFalse(hit.statistics().phases().containsKey(COMPILATION));
            assertMeasurement(hit.statistics(), "project.cache.hit", 1);
            assertMeasurement(hit.statistics(), "project.outputs.reused", 1);
        }
    }

    @Test
    void coldReuseDoesNotCreateCacheDirectoriesOrInvokeTheBackend() throws Exception {
        Map<String, String> before = fileTree(temporary);
        assertThrows(IOException.class, () -> run(options.withBuildPolicy(BuildPolicy.REUSE)));
        assertEquals(0, builder.calls.get());
        assertFalse(Files.exists(cache));
        assertEquals(before, fileTree(temporary));
    }

    @ParameterizedTest
    @EnumSource(SourceChange.class)
    void autoDetectsAddedDeletedAndSameSizeEditedSourcesWhileReuseKeepsTheChosenSnapshot(SourceChange change) throws Exception {
        if (change == SourceChange.DELETE) writeClass("Obsolete");
        Run first = run(options);
        Map<String, String> oldGeneration = fileTree(generation(first.project()));
        switch (change) {
            case ADD -> writeClass("Added");
            case DELETE -> Files.delete(sources.resolve("Obsolete.java"));
            case EDIT_WITH_UNCHANGED_MTIME -> {
                long size = Files.size(fixture);
                var modified = Files.getLastModifiedTime(fixture);
                Files.writeString(fixture, fixtureSource("SELECT 2"));
                Files.setLastModifiedTime(fixture, modified);
                assertEquals(size, Files.size(fixture));
                assertEquals(modified, Files.getLastModifiedTime(fixture));
            }
        }

        Run reused = run(options.withBuildPolicy(BuildPolicy.REUSE));
        assertEquals(first.project(), reused.project());
        assertEquals("SELECT 1", sql(reused.project()));
        assertEquals(1, builder.calls.get());
        assertEquals(oldGeneration, fileTree(generation(first.project())));

        Run rebuilt = run(options);
        assertEquals(2, builder.calls.get());
        assertNotEquals(first.project().classDirectories(), rebuilt.project().classDirectories());
        assertEquals(oldGeneration, fileTree(generation(first.project())));
        switch (change) {
            case ADD -> {
                assertTrue(Files.isRegularFile(classFile(rebuilt.project(), "Added")));
                assertFalse(Files.exists(classFile(first.project(), "Added")));
            }
            case DELETE -> {
                assertFalse(Files.exists(classFile(rebuilt.project(), "Obsolete")));
                assertTrue(Files.isRegularFile(classFile(first.project(), "Obsolete")));
            }
            case EDIT_WITH_UNCHANGED_MTIME -> assertEquals("SELECT 2", sql(rebuilt.project()));
        }
    }

    @ParameterizedTest
    @EnumSource(BuildInputChange.class)
    void autoTracksPomResourcesAndDeclaredExternalInputsWhileReuseKeepsFrozenResources(BuildInputChange change) throws Exception {
        Path external = Files.writeString(temporary.resolve("external-build-input.txt"), "alpha");
        InputLocationOptions configured = options.withAdditionalBuildInputs(List.of(external));
        Run first = run(configured);
        switch (change) {
            case POM -> Files.writeString(project.resolve("pom.xml"), Files.readString(project.resolve("pom.xml"))
                    .replace("<version>1</version>", "<version>2</version>"));
            case RESOURCE -> Files.writeString(resource, "SELECT 2");
            case EXTERNAL -> {
                var modified = Files.getLastModifiedTime(external);
                Files.writeString(external, "bravo");
                Files.setLastModifiedTime(external, modified);
            }
        }
        Run reused = run(configured.withBuildPolicy(BuildPolicy.REUSE));
        assertEquals(first.project(), reused.project());
        assertEquals("SELECT 1", Files.readString(reused.project().resourceRoots().getFirst().resolve("query.sql")));
        assertEquals(1, builder.calls.get());

        Run rebuilt = run(configured);
        assertEquals(2, builder.calls.get());
        assertNotEquals(first.project().classDirectories(), rebuilt.project().classDirectories());
        assertEquals(change == BuildInputChange.RESOURCE ? "SELECT 2" : "SELECT 1",
                Files.readString(rebuilt.project().resourceRoots().getFirst().resolve("query.sql")));
    }

    @ParameterizedTest
    @EnumSource(ConfigurationChange.class)
    void argumentsAndAnalysisClasspathSelectSeparateSnapshotsAndReuseDoesNotCreateAMissingKey(ConfigurationChange change) throws Exception {
        Run first = run(options);
        Path library = writeLibrary(temporary.resolve("additional-library.jar"), "one");
        InputLocationOptions changed = switch (change) {
            case ARGUMENTS -> options.withBuildArguments(List.of("-Pdifferent-profile"));
            case CLASSPATH -> withClasspath(options, List.of(library));
        };
        Map<String, String> before = fileTree(cache);
        assertThrows(IOException.class, () -> run(changed.withBuildPolicy(BuildPolicy.REUSE)));
        assertEquals(1, builder.calls.get());
        assertEquals(before, fileTree(cache));

        Run rebuilt = run(changed);
        assertEquals(2, builder.calls.get());
        assertNotEquals(generation(first.project()).getParent(), generation(rebuilt.project()).getParent());
        if (change == ConfigurationChange.CLASSPATH) assertEquals(List.of(library), rebuilt.project().dependencies());
        assertEquals(first.project(), run(options.withBuildPolicy(BuildPolicy.REUSE)).project());
        assertEquals(2, builder.calls.get());
    }

    @Test
    void changedRuntimeDependencyContentsRejectReuseAndMakeAutoRebuildEvenWithTheSameSizeAndMtime() throws Exception {
        Path library = writeLibrary(temporary.resolve("runtime-library.jar"), "one");
        builder = new CountingBuilder(List.of(library));
        Run first = run(options);
        var modified = Files.getLastModifiedTime(library);
        long size = Files.size(library);
        writeLibrary(library, "two");
        Files.setLastModifiedTime(library, modified);
        assertEquals(size, Files.size(library));
        assertEquals(modified, Files.getLastModifiedTime(library));
        Map<String, String> before = fileTree(cache);

        assertThrows(IOException.class, () -> run(options.withBuildPolicy(BuildPolicy.REUSE)));
        assertEquals(1, builder.calls.get());
        assertEquals(before, fileTree(cache));
        Run rebuilt = run(options);
        assertEquals(2, builder.calls.get());
        assertNotEquals(first.project().classDirectories(), rebuilt.project().classDirectories());
        assertEquals(List.of(library), rebuilt.project().dependencies());
        assertTrue(Files.isDirectory(generation(first.project())));
    }

    @ParameterizedTest
    @EnumSource(SnapshotDamage.class)
    void corruptedClassesManifestsAndUnknownSchemasAreNeverReused(SnapshotDamage damage) throws Exception {
        Run first = run(options);
        Path generation = generation(first.project());
        switch (damage) {
            case CLASS_CONTENT -> rewriteSqlLiteral(classFile(first.project(), "Fixture"));
            case MISSING_CLASS -> Files.delete(classFile(first.project(), "Fixture"));
            case MANIFEST -> Files.writeString(generation.resolve("project.properties"), "\n# altered manifest\n", StandardOpenOption.APPEND);
            case POINTER_SCHEMA -> setProperty(generation.getParent().resolve("current.properties"), "schema.version", "999");
            case STATE_SCHEMA -> setProperty(generation.resolve("state.properties"), "schema.version", "999");
        }
        Map<String, String> before = fileTree(cache);
        assertThrows(IOException.class, () -> run(options.withBuildPolicy(BuildPolicy.REUSE)));
        assertEquals(1, builder.calls.get());
        assertEquals(before, fileTree(cache), "REUSE must leave even a damaged snapshot untouched");

        Run rebuilt = run(options);
        assertEquals(2, builder.calls.get());
        assertNotEquals(generation, generation(rebuilt.project()));
        assertEquals("SELECT 1", sql(rebuilt.project()));
        assertEquals(rebuilt.project(), run(options.withBuildPolicy(BuildPolicy.REUSE)).project());
    }

    @Test
    void failedRebuildRemovesItsGenerationWithoutPublishingOrFallingBackToThePreviousSnapshot() throws Exception {
        Run first = run(options);
        byte[] pointer = Files.readAllBytes(generation(first.project()).getParent().resolve("current.properties"));
        Map<String, String> originalGeneration = fileTree(generation(first.project()));
        Files.writeString(fixture, fixtureSource("SELECT 2"));
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<Path> failedGeneration = new AtomicReference<>();
        IOException failure = new IOException("backend failure after creating a partial output");
        ProjectCache.Builder failing = (root, fresh, recorder) -> {
            attempts.incrementAndGet();
            failedGeneration.set(fresh.classOutputDirectory().getParent());
            Files.writeString(fresh.classOutputDirectory().resolve("partial.txt"), "unfinished");
            throw failure;
        };
        var recorder = StatisticsRecorder.create(AnalysisStatisticsOptions.timings());
        assertSame(failure, assertThrows(IOException.class, () -> ProjectCache.resolve(project, options,
                new AnalysisInputContext(() -> {}, recorder), failing, clock::get)));
        assertEquals(1, attempts.get());
        assertFalse(Files.exists(failedGeneration.get()));
        assertEquals(Set.of(generation(first.project())), generations());
        assertArrayEquals(pointer, Files.readAllBytes(generation(first.project()).getParent().resolve("current.properties")));
        assertEquals(originalGeneration, fileTree(generation(first.project())));
        assertTrue(recorder.snapshot().orElseThrow().phases().get(AnalysisStatistics.Phase.CLEANUP).invocations() > 0);
        assertEquals("SELECT 1", sql(run(options.withBuildPolicy(BuildPolicy.REUSE)).project()));
        assertEquals("SELECT 2", sql(run(options).project()));
        assertEquals(2, builder.calls.get());
    }

    @Test
    void failedColdBuildLeavesNoPublishedOrReusableGeneration() throws Exception {
        AtomicReference<Path> failedGeneration = new AtomicReference<>();
        IllegalStateException failure = new IllegalStateException("failed before publication");
        ProjectCache.Builder failing = (root, fresh, recorder) -> {
            failedGeneration.set(fresh.classOutputDirectory().getParent());
            Files.writeString(fresh.classOutputDirectory().resolve("partial.txt"), "unfinished");
            throw failure;
        };
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> ProjectCache.resolve(project, options, context(), failing, clock::get)));
        assertFalse(Files.exists(failedGeneration.get()));
        assertTrue(generations().isEmpty());
        Map<String, String> before = fileTree(cache);
        assertThrows(IOException.class, () -> run(options.withBuildPolicy(BuildPolicy.REUSE)));
        assertEquals(0, builder.calls.get());
        assertEquals(before, fileTree(cache));
    }

    @Test
    void cancellationAfterBuildingRemovesOnlyTheUnpublishedGeneration() throws Exception {
        Run first = run(options);
        Map<String, String> before = fileTree(cache);
        Files.writeString(fixture, fixtureSource("SELECT 2"));
        AtomicBoolean cancelled = new AtomicBoolean();
        IllegalStateException stop = new IllegalStateException("cancelled by the fixture checkpoint");
        AnalysisInputContext context = new AnalysisInputContext(() -> {
            if (cancelled.get()) throw stop;
        }, StatisticsRecorder.none());
        ProjectCache.Builder cancelling = (root, fresh, recorder) -> {
            CompiledProject built = builder.build(root, fresh, recorder);
            cancelled.set(true);
            return built;
        };
        assertSame(stop, assertThrows(IllegalStateException.class,
                () -> ProjectCache.resolve(project, options, context, cancelling, clock::get)));
        assertEquals(2, builder.calls.get());
        assertFalse(Files.exists(builder.outputs.getLast().getParent()));
        assertEquals(before, fileTree(cache));
        assertEquals("SELECT 1", sql(run(options.withBuildPolicy(BuildPolicy.REUSE)).project()));
    }

    @Test
    void inputsChangedDuringCompilationCannotBePublishedAsAnUpToDateSnapshot() throws Exception {
        ProjectCache.Builder changing = (root, fresh, recorder) -> {
            CompiledProject built = builder.build(root, fresh, recorder);
            Files.writeString(fixture, fixtureSource("SELECT 2"));
            return built;
        };
        assertThrows(IOException.class, () -> ProjectCache.resolve(project, options, context(), changing, clock::get));
        assertEquals(1, builder.calls.get());
        assertFalse(Files.exists(builder.outputs.getFirst().getParent()));
        assertTrue(generations().isEmpty());
        assertThrows(IOException.class, () -> run(options.withBuildPolicy(BuildPolicy.REUSE)));
        assertEquals("SELECT 2", sql(run(options).project()));
    }

    @Test
    void deletedExplicitRepositoryFileBeforeFirstObservationCannotBePublished() throws Exception {
        Path repository = Files.createDirectories(temporary.resolve("local-repository"));
        Path input = Files.writeString(repository.resolve("build-input.txt"), "alpha");
        var configured = options.withMavenRepositories(MavenRepositoryOptions.defaults().withLocalRepository(repository))
                .withAdditionalBuildInputs(List.of(input));
        AtomicReference<Path> attemptedGeneration = new AtomicReference<>();
        ProjectCache.Builder deleting = (root, fresh, recorder) -> {
            attemptedGeneration.set(fresh.classOutputDirectory().getParent());
            Files.delete(input);
            ProjectCache.currentObserver().observe(List.of(root), List.of(fresh.classOutputDirectory()));
            return builder.build(root, fresh, recorder);
        };

        IOException failure = assertThrows(IOException.class,
                () -> ProjectCache.resolve(project, configured, context(), deleting, clock::get));
        assertTrue(failure.getMessage().contains("Build inputs changed"), failure::getMessage);
        assertNotNull(attemptedGeneration.get());
        assertFalse(Files.exists(attemptedGeneration.get()));
        assertTrue(generations().isEmpty());
        assertEquals(0, builder.calls.get());
        assertThrows(IOException.class, () -> run(configured.withBuildPolicy(BuildPolicy.REUSE)));

        Files.writeString(input, "alpha");
        assertEquals("SELECT 1", sql(run(configured).project()));
        assertEquals(1, builder.calls.get());
    }

    @Test
    void addedSourceBeforeFirstObservationCannotBePublished() throws Exception {
        AtomicReference<Path> attemptedGeneration = new AtomicReference<>();
        ProjectCache.Builder adding = (root, fresh, recorder) -> {
            attemptedGeneration.set(fresh.classOutputDirectory().getParent());
            writeClass("User");
            ProjectCache.currentObserver().observe(List.of(root), List.of(fresh.classOutputDirectory()));
            return builder.build(root, fresh, recorder);
        };

        IOException failure = assertThrows(IOException.class,
                () -> ProjectCache.resolve(project, options, context(), adding, clock::get));
        assertTrue(failure.getMessage().contains("Build inputs changed"), failure::getMessage);
        assertNotNull(attemptedGeneration.get());
        assertFalse(Files.exists(attemptedGeneration.get()));
        assertTrue(generations().isEmpty());
        assertEquals(0, builder.calls.get());
        assertThrows(IOException.class, () -> run(options.withBuildPolicy(BuildPolicy.REUSE)));

        CompiledProject stable = run(options).project();
        assertTrue(Files.isRegularFile(classFile(stable, "User")));
        assertEquals(1, builder.calls.get());
    }

    @Test
    void explicitInputDirectoryInsideRepositoryTracksChangesAndExcludesItsDeclaredOutputs() throws Exception {
        Path repository = Files.createDirectories(temporary.resolve("local-repository"));
        Path inputs = Files.createDirectories(repository.resolve("build-inputs"));
        Path schema = Files.writeString(inputs.resolve("schema.txt"), "alpha");
        Path generated = Files.createDirectories(inputs.resolve("generated"));
        Path marker = Files.writeString(generated.resolve("marker.txt"), "initial");
        var configured = options.withMavenRepositories(MavenRepositoryOptions.defaults().withLocalRepository(repository))
                .withAdditionalBuildInputs(List.of(inputs));
        ProjectCache.Builder observed = (root, fresh, recorder) -> {
            ProjectCache.currentObserver().observe(List.of(root, inputs), List.of(fresh.classOutputDirectory(), generated));
            return builder.build(root, fresh, recorder);
        };
        CompiledProject first = ProjectCache.resolve(project, configured, context(), observed, clock::get);

        Files.writeString(marker, "changed output");
        assertEquals(first, ProjectCache.resolve(project, configured, context(), observed, clock::get));
        assertEquals(1, builder.calls.get(), "Declared output changes must not invalidate an explicit input directory");

        var modified = Files.getLastModifiedTime(schema);
        Files.writeString(schema, "bravo");
        Files.setLastModifiedTime(schema, modified);
        CompiledProject rebuilt = ProjectCache.resolve(project, configured, context(), observed, clock::get);
        assertEquals(2, builder.calls.get(), "Explicit input roots must override the enclosing repository exclusion");
        assertNotEquals(first.classDirectories(), rebuilt.classDirectories());
        assertEquals("SELECT 1", sql(rebuilt));
        assertEquals(rebuilt, ProjectCache.resolve(project, configured, context(), observed, clock::get));
        assertEquals(2, builder.calls.get());
    }

    @Test
    void zeroMaxAgeDoesNotExpireAnUnchangedSnapshot() throws Exception {
        Run first = run(options.withCacheMaxAge(Duration.ZERO));
        clock.addAndGet(TimeUnit.DAYS.toMillis(400));
        assertEquals(first.project(), run(options.withCacheMaxAge(Duration.ZERO)).project());
        assertEquals(1, builder.calls.get());
    }

    @Test
    void autoExpiresAtTheExactTtlBoundaryButReuseDoesNotExpireTheChosenSnapshot() throws Exception {
        InputLocationOptions expiring = options.withCacheMaxAge(Duration.ofMillis(100));
        Run first = run(expiring);
        clock.addAndGet(99);
        assertEquals(first.project(), run(expiring).project());
        clock.incrementAndGet();
        assertEquals(first.project(), run(expiring.withBuildPolicy(BuildPolicy.REUSE)).project());
        assertEquals(1, builder.calls.get());
        Run rebuilt = run(expiring);
        assertEquals(2, builder.calls.get());
        assertNotEquals(first.project().classDirectories(), rebuilt.project().classDirectories());
        assertTrue(builder.received.getLast().buildArguments().contains("-U"));
        assertEquals(rebuilt.project(), run(expiring).project());
        assertEquals(2, builder.calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-U", "--update-snapshots", "--refresh-dependencies"})
    void explicitDependencyRefreshAlwaysRebuildsAndIsRejectedByReuse(String flag) throws Exception {
        InputLocationOptions configured = flag.equals("--refresh-dependencies")
                ? options.withInputMode(InputMode.GRADLE_PROJECT) : options;
        Run first = run(configured);
        InputLocationOptions refreshing = configured.withBuildArguments(List.of(flag));
        Run second = run(refreshing);
        Run third = run(refreshing);
        assertEquals(3, builder.calls.get());
        assertNotEquals(first.project().classDirectories(), second.project().classDirectories());
        assertNotEquals(second.project().classDirectories(), third.project().classDirectories());
        assertEquals(third.project(), run(configured.withBuildPolicy(BuildPolicy.REUSE)).project());
        Map<String, String> before = fileTree(cache);
        assertThrows(IllegalArgumentException.class, () -> run(refreshing.withBuildPolicy(BuildPolicy.REUSE)));
        assertEquals(3, builder.calls.get());
        assertEquals(before, fileTree(cache));
    }

    @Test
    void concurrentAutoRequestsPublishOneCompleteGenerationAndBuildOnlyOnce() throws Exception {
        CountDownLatch enteredBuilder = new CountDownLatch(1);
        CountDownLatch allowPublication = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        ProjectCache.Builder blocking = (root, fresh, recorder) -> {
            enteredBuilder.countDown();
            try {
                if (!allowPublication.await(10, TimeUnit.SECONDS)) throw new IOException("test did not release the builder");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("test builder interrupted", interrupted);
            }
            return builder.build(root, fresh, recorder);
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> ProjectCache.resolve(project, options, context(), blocking, clock::get));
            assertTrue(enteredBuilder.await(10, TimeUnit.SECONDS), "First request should reach the backend");
            Map<String, String> unpublished = fileTree(cache);
            assertThrows(IOException.class, () -> ProjectCache.resolve(project,
                    options.withBuildPolicy(BuildPolicy.REUSE), context(), blocking, clock::get));
            assertEquals(unpublished, fileTree(cache), "REUSE must not open or publish an unfinished generation");
            var second = executor.submit(() -> ProjectCache.resolve(project, options,
                    new AnalysisInputContext(secondStarted::countDown, StatisticsRecorder.none()), blocking, clock::get));
            assertTrue(secondStarted.await(10, TimeUnit.SECONDS), "Second request should enter cache resolution");
            allowPublication.countDown();
            CompiledProject firstResult = first.get(15, TimeUnit.SECONDS);
            CompiledProject secondResult = second.get(15, TimeUnit.SECONDS);
            assertEquals(1, builder.calls.get());
            assertEquals(firstResult, secondResult);
            assertEquals("SELECT 1", sql(secondResult));
            assertEquals(Set.of(generation(firstResult)), generations());
            assertEquals(firstResult, run(options.withBuildPolicy(BuildPolicy.REUSE)).project());
        } finally {
            allowPublication.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS), "Cache workers should release their resources");
        }
    }

    private Run run(InputLocationOptions configured) throws IOException {
        var recorder = StatisticsRecorder.create(AnalysisStatisticsOptions.timings());
        CompiledProject result = ProjectCache.resolve(project, configured,
                new AnalysisInputContext(() -> {}, recorder), builder, clock::get);
        return new Run(result, recorder.snapshot().orElseThrow());
    }

    private static AnalysisInputContext context() {
        return new AnalysisInputContext(() -> {}, StatisticsRecorder.none());
    }

    private void writeClass(String name) throws IOException {
        Files.writeString(sources.resolve(name + ".java"), "package cachefixture; public class " + name + " {}\n");
    }

    private static String fixtureSource(String sql) {
        return """
                package cachefixture;
                public class Fixture {
                    public static String sql() { return "%s"; }
                }
                """.formatted(sql);
    }

    private static Path classFile(CompiledProject compiled, String name) {
        return compiled.classDirectories().getFirst().resolve("cachefixture/" + name + ".class");
    }

    private static Path generation(CompiledProject compiled) {
        return compiled.classDirectories().getFirst().getParent();
    }

    private Set<Path> generations() throws IOException {
        if (!Files.exists(cache)) return Set.of();
        try (var paths = Files.walk(cache)) {
            return new LinkedHashSet<>(paths.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("generation-")).toList());
        }
    }

    private static String sql(CompiledProject compiled) throws IOException {
        AtomicReference<String> result = new AtomicReference<>();
        new ClassReader(Files.readAllBytes(classFile(compiled, "Fixture"))).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (!name.equals("sql")) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitLdcInsn(Object value) {
                        if (value instanceof String literal) result.set(literal);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        assertNotNull(result.get(), "Fixture.sql should retain its SQL literal in the compiled class");
        return result.get();
    }

    private static Path writeLibrary(Path path, String revision) throws IOException {
        try (var archive = new JarOutputStream(Files.newOutputStream(path))) {
            var entry = new JarEntry("revision.txt");
            entry.setTime(0);
            archive.putNextEntry(entry);
            archive.write(revision.getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
        }
        return path;
    }

    private static void rewriteSqlLiteral(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        byte[] literal = "SELECT 1".getBytes(StandardCharsets.UTF_8);
        for (int offset = 0; offset <= bytes.length - literal.length; offset++) {
            int index = 0;
            while (index < literal.length && bytes[offset + index] == literal[index]) index++;
            if (index == literal.length) {
                bytes[offset + literal.length - 1] = '2';
                Files.write(path, bytes);
                return;
            }
        }
        fail("Fixture class should contain the SELECT 1 literal");
    }

    private static void setProperty(Path path, String name, String value) throws IOException {
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(path)) { properties.load(reader); }
        properties.setProperty(name, value);
        try (var writer = Files.newBufferedWriter(path)) { properties.store(writer, "changed by cache contract test"); }
    }

    private static InputLocationOptions withClasspath(InputLocationOptions configured, List<Path> paths) {
        return new InputLocationOptions(configured.inputMode(), paths, configured.buildArguments(),
                configured.gradleInstallation(), configured.classOutputDirectory(), configured.mavenRepositories(),
                configured.buildPolicy(), configured.projectCacheDirectory(), configured.compiledProjectManifest(),
                configured.cacheMaxAge(), configured.additionalBuildInputs());
    }

    private static Map<String, String> fileTree(Path root) throws IOException {
        if (!Files.exists(root)) return Map.of();
        var result = new TreeMap<String, String>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) result.put(root.relativize(path).toString(), Files.isDirectory(path)
                    ? "directory" : path.getFileName().toString().equals("build.lock")
                    ? "synchronization lock" : digest(Files.readAllBytes(path)));
        }
        return result;
    }

    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static void assertMeasurement(AnalysisStatistics statistics, String name, long value) {
        var measurement = statistics.measurements().get(name);
        assertNotNull(measurement, name);
        assertEquals(BigDecimal.valueOf(value), measurement.value());
        assertEquals(AnalysisStatistics.Unit.COUNT, measurement.unit());
        assertEquals(AnalysisStatistics.Scope.REQUEST, measurement.scope());
    }

    private record Run(CompiledProject project, AnalysisStatistics statistics) {}
    private enum SourceChange { ADD, DELETE, EDIT_WITH_UNCHANGED_MTIME }
    private enum BuildInputChange { POM, RESOURCE, EXTERNAL }
    private enum ConfigurationChange { ARGUMENTS, CLASSPATH }
    private enum SnapshotDamage { CLASS_CONTENT, MISSING_CLASS, MANIFEST, POINTER_SCHEMA, STATE_SCHEMA }

    private static final class CountingBuilder implements ProjectCache.Builder {
        private final AtomicInteger calls = new AtomicInteger();
        private final List<Path> outputs = new CopyOnWriteArrayList<>();
        private final List<InputLocationOptions> received = new CopyOnWriteArrayList<>();
        private final List<Path> dependencies;

        private CountingBuilder(List<Path> dependencies) { this.dependencies = List.copyOf(dependencies); }

        @Override
        public CompiledProject build(Path root, InputLocationOptions fresh, StatisticsRecorder recorder) throws IOException {
            calls.incrementAndGet();
            outputs.add(fresh.classOutputDirectory());
            received.add(fresh);
            assertEquals(BuildPolicy.ALWAYS, fresh.buildPolicy());
            assertNotNull(fresh.classOutputDirectory());
            assertTrue(Files.isDirectory(fresh.classOutputDirectory()), "Cache must allocate a fresh output before building");
            JavaSourceCompiler.compile(JavaSourceFiles.collect(root.resolve("src/main/java")), fresh.classOutputDirectory(), recorder);
            var libraries = new LinkedHashSet<>(dependencies);
            libraries.addAll(fresh.classpath());
            return CompiledProject.fromOutputs(root, fresh.inputMode(), List.of(fresh.classOutputDirectory()),
                    libraries, List.of(root.resolve("src/main/resources")));
        }
    }
}
