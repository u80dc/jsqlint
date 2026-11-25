package org.u80dc.jsqlint.commons.compiler;

import org.u80dc.jsqlint.commons.location.*;
import org.u80dc.jsqlint.core.AnalysisStatistics;
import org.u80dc.jsqlint.core.spi.AnalysisInputContext;
import org.u80dc.jsqlint.core.statistics.StatisticsRecorder;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.LongSupplier;

/** 成功构建按代保存；原子指针发布，不把共享产物交给单次分析删除。 */
final class ProjectCache {
    @FunctionalInterface interface Builder {
        CompiledProject build(Path root, InputLocationOptions options, StatisticsRecorder recorder) throws IOException;
    }
    private static final ThreadLocal<ProjectBuildObserver> OBSERVER = new ThreadLocal<>();
    private static final int MAX_STATE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_PATHS = 8192;
    private static final String VERSION = "1";

    private ProjectCache() {}
    static ProjectBuildObserver currentObserver() { return OBSERVER.get(); }

    static CompiledProject readExplicit(Path root, InputLocationOptions options, AnalysisInputContext context) {
        if (refresh(options)) throw new IllegalArgumentException("REUSE cannot request dependency refresh");
        try {
            context.checkpoint().run();
            CompiledProject project = CompiledProjectManifest.read(options.compiledProjectManifest());
            verifyIdentity(root, options, project);
            var dependencies = new LinkedHashSet<>(project.dependencies());dependencies.addAll(options.classpath());
            project = new CompiledProject(project.directory(), project.inputMode(), project.classDirectories(),
                    List.copyOf(dependencies), project.resourceRoots());
            validate(project);
            metric(context, "project.outputs.reused", 1);
            return project;
        } catch (IOException error) { throw new AnalysisInputException("Cannot reuse compiled project manifest: " + options.compiledProjectManifest(), error); }
    }

    static CompiledProject resolve(Path root, InputLocationOptions options, AnalysisInputContext context,
                                    Builder builder, LongSupplier clock) throws IOException {
        Objects.requireNonNull(builder);Objects.requireNonNull(clock);
        context.checkpoint().run();
        Path source = root.toAbsolutePath().normalize();
        Path cache = cacheDirectory(options);
        if (source.startsWith(cache)) throw new IOException("Project cache must not contain the source project: " + cache);
        for (Path input : options.additionalBuildInputs()) if (input.startsWith(cache))
            throw new IOException("Project cache must not contain additional build inputs: " + input);
        String configuration = ProjectFingerprints.configuration(source, options);
        Path key = cache.resolve("project-" + configuration);
        if (options.buildPolicy() == BuildPolicy.REUSE) {
            if (refresh(options)) throw new IllegalArgumentException("REUSE cannot request dependency refresh");
            Entry entry = readEntry(key, configuration, source, options, context);
            metric(context, "project.cache.hit", 1);metric(context, "project.outputs.reused", 1);
            return entry.project();
        }
        if (options.buildPolicy() != BuildPolicy.AUTO) throw new IllegalArgumentException("Expected AUTO or REUSE build policy");
        ensureDirectory(cache);ensureDirectory(key);
        try (var channel = FileChannel.open(key.resolve("build.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = lock(channel, context)) {
            Entry previous = null;
            try { previous = readEntry(key, configuration, source, options, context); }
            catch (IOException | IllegalArgumentException invalid) {
                metric(context, invalid instanceof NoSuchFileException ? "project.cache.noSnapshot" : "project.cache.invalidSnapshot", 1);
            }
            boolean expired = previous != null && !options.cacheMaxAge().isZero()
                    && Math.max(0, clock.getAsLong() - previous.created()) >= options.cacheMaxAge().toMillis();
            if (previous != null && !refresh(options) && !expired
                    && previous.sourceHash().equals(ProjectFingerprints.fingerprint(ProjectFingerprints.snapshot(
                            previous.inputs(), previous.outputs(), previous.initial(), context.checkpoint())))) {
                metric(context, "project.cache.hit", 1);metric(context, "project.outputs.reused", 1);
                return previous.project();
            }
            metric(context, "project.cache.miss", 1);
            if (previous != null) metric(context, refresh(options) ? "project.cache.refreshRequested"
                    : expired ? "project.cache.expired" : "project.cache.inputsChanged", 1);
            Path generation = key.resolve("generation-" + UUID.randomUUID());Files.createDirectory(generation);
            var before = OBSERVER.get();
            try {
                ensureDirectory(generation);
                Path classes = Files.createDirectory(generation.resolve("classes"));
                var tracker = new Tracker(source, options, cache, context);
                InputLocationOptions fresh = options.withBuildPolicy(BuildPolicy.ALWAYS).withClassOutputDirectory(classes);
                if (expired && !refresh(options)) fresh = fresh.withBuildArguments(refreshedArguments(fresh));
                OBSERVER.set(tracker);
                CompiledProject compiled;
                try { compiled = builder.build(source, fresh, context.statisticsRecorder()); }
                catch (IOException | RuntimeException failure) {
                    tracker.checkFailure();throw failure;
                } finally { if (before == null) OBSERVER.remove();else OBSERVER.set(before); }
                tracker.checkFailure();context.checkpoint().run();
                String sourceHash = tracker.finish();
                CompiledProject snapshot = freeze(compiled, generation, context);
                validate(snapshot);
                String artifactHash = ProjectFingerprints.artifacts(snapshot, context.checkpoint());
                Path manifest = generation.resolve("project.properties");CompiledProjectManifest.write(manifest, snapshot);
                var state = new Properties();
                state.setProperty("schema.version", VERSION);state.setProperty("configuration", configuration);
                state.setProperty("created", Long.toString(Math.max(0, clock.getAsLong())));
                state.setProperty("source.hash", sourceHash);state.setProperty("artifact.hash", artifactHash);
                state.setProperty("manifest.hash", ProjectFingerprints.file(manifest, context.checkpoint()));
                state.setProperty("initial", Boolean.toString(!tracker.observed));
                putPaths(state, "inputs", tracker.inputs);putPaths(state, "outputs", tracker.outputs);
                writeProperties(generation.resolve("state.properties"), state);
                // 副本和摘要计算期间同样可能修改源码；发布前最后校验一次。
                if (!sourceHash.equals(tracker.finish())) throw new IOException("Build inputs changed before snapshot publication");
                context.checkpoint().run();
                var pointer = new Properties();pointer.setProperty("schema.version", VERSION);
                pointer.setProperty("generation", generation.getFileName().toString());
                writeProperties(key.resolve("current.properties"), pointer);
                return snapshot;
            } catch (IOException | RuntimeException | Error failure) {
                try (var cleanup = context.statisticsRecorder().measure(AnalysisStatistics.Phase.CLEANUP)) { deleteGeneration(key, generation); }
                catch (IOException close) { failure.addSuppressed(close); }
                throw failure;
            } finally { if (before == null) OBSERVER.remove();else OBSERVER.set(before); }
        }
    }

    private static Path cacheDirectory(InputLocationOptions options) {
        return options.projectCacheDirectory() == null
                ? Path.of(System.getProperty("java.io.tmpdir"), ".jsqlint-project-cache").toAbsolutePath().normalize()
                : options.projectCacheDirectory();
    }

    private static boolean refresh(InputLocationOptions options) { return options.buildArguments().stream().anyMatch(ProjectFingerprints::refreshArgument); }

    private static List<String> refreshedArguments(InputLocationOptions options) {
        var arguments = new ArrayList<>(options.buildArguments());
        if (!arguments.contains("-o") && !arguments.contains("--offline"))
            arguments.add(options.inputMode() == InputMode.MAVEN_PROJECT ? "-U" : "--refresh-dependencies");
        return arguments;
    }

    private static FileLock lock(FileChannel channel, AnalysisInputContext context) throws IOException {
        try (var waiting = context.statisticsRecorder().measure(AnalysisStatistics.Phase.WAITING)) {
            while (true) {
                context.checkpoint().run();
                try { FileLock lock = channel.tryLock();if (lock != null) return lock; }
                catch (OverlappingFileLockException busy) { /* 同 JVM 的其它请求也按可取消方式等待。 */ }
                try { Thread.sleep(25); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();context.checkpoint().run();
                    throw new IOException("Interrupted while waiting for project cache", interrupted);
                }
            }
        }
    }

    private static Entry readEntry(Path key, String configuration, Path root, InputLocationOptions options,
                                    AnalysisInputContext context) throws IOException {
        var pointer = readProperties(key.resolve("current.properties"));
        checkVersion(pointer);String name = required(pointer, "generation");
        if (!name.matches("generation-[0-9a-f-]{36}") || pointer.size() != 2) throw new IOException("Invalid cache generation pointer");
        Path generation = key.resolve(name);verifyDirectory(generation);
        var state = readProperties(generation.resolve("state.properties"));checkVersion(state);
        if (!configuration.equals(required(state, "configuration"))) throw new IOException("Project cache configuration mismatch");
        Path manifest = generation.resolve("project.properties");
        if (!required(state, "manifest.hash").equals(ProjectFingerprints.file(manifest, context.checkpoint())))
            throw new IOException("Compiled project manifest changed");
        var project = CompiledProjectManifest.read(manifest);verifyIdentity(root, options, project);validate(project);
        for (Path application : project.classDirectories()) if (!application.startsWith(generation)
                || !application.toRealPath().startsWith(generation.toRealPath()))
            throw new IOException("Cached application output is outside its generation: " + application);
        for (Path resource : project.resourceRoots()) if (!resource.startsWith(generation)
                || !resource.toRealPath().startsWith(generation.toRealPath()))
            throw new IOException("Cached resource output is outside its generation: " + resource);
        if (!required(state, "artifact.hash").equals(ProjectFingerprints.artifacts(project, context.checkpoint())))
            throw new IOException("Compiled project artifacts or runtime dependencies changed");
        long created;
        try { created = Long.parseLong(required(state, "created"));if (created < 0) throw new NumberFormatException(); }
        catch (NumberFormatException invalid) { throw new IOException("Invalid cache creation timestamp", invalid); }
        String initial = required(state, "initial");
        if (!initial.equals("true") && !initial.equals("false")) throw new IOException("Invalid cache input mode");
        return new Entry(project, required(state, "source.hash"), created, paths(state, "inputs"), paths(state, "outputs"), Boolean.parseBoolean(initial));
    }

    private record Entry(CompiledProject project, String sourceHash, long created,
                          List<Path> inputs, List<Path> outputs, boolean initial) {}

    private static void verifyIdentity(Path root, InputLocationOptions options, CompiledProject project) throws IOException {
        Path origin = project.directory().toAbsolutePath().normalize();
        if (Files.exists(origin)) origin = origin.toRealPath();
        if (!origin.equals(root.toRealPath())) throw new IOException("Compiled project manifest describes another project: " + project.directory());
        if (options.inputMode() != InputMode.AUTO && options.inputMode() != project.inputMode())
            throw new IOException("Compiled project build mode does not match input options");
    }

    private static void validate(CompiledProject project) throws IOException {
        if (project.classDirectories().isEmpty()) throw new IOException("Compiled project has no application outputs");
        for (Path path : project.classDirectories()) if (!Files.isDirectory(path) && !Files.isRegularFile(path)) throw new IOException("Missing class output: " + path);
        for (Path path : project.dependencies()) if (!Files.isDirectory(path) && !Files.isRegularFile(path)) throw new IOException("Missing runtime dependency: " + path);
        for (Path path : project.resourceRoots()) if (!Files.isDirectory(path) && !Files.isRegularFile(path)) throw new IOException("Missing resource output: " + path);
        try (var input = ProjectInputs.open(project)) { /* 复用与首次发布使用同一应用/库/归档校验规则。 */ }
        catch (Exception invalid) { throw new IOException("Invalid compiled project inputs", invalid); }
    }

    private static CompiledProject freeze(CompiledProject compiled, Path generation, AnalysisInputContext context) throws IOException {
        var mapped = new LinkedHashMap<Path, Path>();var applications = new ArrayList<Path>();int index = 0;
        for (Path value : compiled.classDirectories()) {
            Path source = value.toAbsolutePath().normalize();
            Path target = source.startsWith(generation) ? source : copy(source, generation.resolve("applications/" + index++), context);
            mapped.put(source, target);applications.add(target);
        }
        var resources = new LinkedHashSet<Path>();index = 0;
        for (Path value : compiled.resourceRoots()) {
            Path source = value.toAbsolutePath().normalize();
            Path target = mapped.get(source);
            if (target == null) target = source.startsWith(generation) ? source : copy(source, generation.resolve("resources/" + index++), context);
            resources.add(target);
        }
        return new CompiledProject(compiled.directory(), compiled.inputMode(), applications, compiled.dependencies(), List.copyOf(resources));
    }

    private static Path copy(Path source, Path destination, AnalysisInputContext context) throws IOException {
        if (Files.isRegularFile(source)) {
            Files.createDirectories(destination);Path target = destination.resolve(source.getFileName());
            context.checkpoint().run();Files.copy(source.toRealPath(), target);return target;
        }
        if (!Files.isDirectory(source)) throw new IOException("Missing resource to snapshot: " + source);
        Files.createDirectories(destination);
        Files.walkFileTree(source, EnumSet.of(FileVisitOption.FOLLOW_LINKS), 128, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                context.checkpoint().run();Files.createDirectories(destination.resolve(source.relativize(directory)));return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                context.checkpoint().run();Files.copy(file.toRealPath(), destination.resolve(source.relativize(file)));return FileVisitResult.CONTINUE;
            }
        });
        return destination;
    }

    private static void metric(AnalysisInputContext context, String name, long value) {
        context.statisticsRecorder().recordMeasurement(name, value, AnalysisStatistics.Unit.COUNT, AnalysisStatistics.Scope.REQUEST);
    }

    private static final class Tracker implements ProjectBuildObserver {
        private final LinkedHashSet<Path> inputs = new LinkedHashSet<>(), outputs = new LinkedHashSet<>();
        private final List<Path> initialInputs;
        private final AnalysisInputContext context;
        private Map<String, String> before;
        private boolean observed;
        private Exception failure;

        Tracker(Path root, InputLocationOptions options, Path cache, AnalysisInputContext context) throws IOException {
            this.context = context;inputs.add(root);inputs.addAll(options.additionalBuildInputs());
            inputs.addAll(ProjectFingerprints.argumentInputs(root, options));outputs.add(cache);
            Path repository = options.mavenRepositories().localRepository();
            outputs.add(repository == null ? Path.of(System.getProperty("user.home"), ".m2", "repository") : repository);
            initialInputs = List.copyOf(inputs);
            before = ProjectFingerprints.snapshot(inputs, outputs, true, context.checkpoint());
        }
        @Override public synchronized void observe(Collection<Path> inputPaths, Collection<Path> outputPaths) throws IOException {
            try {
                if (inputPaths.isEmpty()) {
                    outputPaths.forEach(path -> outputs.add(path.toAbsolutePath().normalize()));
                    before.entrySet().removeIf(this::excluded);
                    return;
                }
                var added = new LinkedHashSet<Path>();
                for (Path path : inputPaths) if (inputs.add(path.toAbsolutePath().normalize())) added.add(path.toAbsolutePath().normalize());
                outputPaths.forEach(path -> outputs.add(path.toAbsolutePath().normalize()));
                if (!observed) {
                    var full = ProjectFingerprints.snapshot(inputs, outputs, false, context.checkpoint());
                    before.entrySet().removeIf(this::excluded);
                    var known = ProjectFingerprints.snapshot(initialInputs, outputs, true, context.checkpoint());
                    if (!before.equals(known))
                        throw new IOException("Build inputs changed before build tasks: " + changedPaths(before, known));
                    for (var entry : known.entrySet()) {
                        if (!Objects.equals(entry.getValue(), full.get(entry.getKey())))
                            throw new IOException("Build inputs changed before build tasks: " + entry.getKey());
                    }
                    before = full;observed = true;
                } else {
                    before.entrySet().removeIf(this::excluded);
                    var extra = ProjectFingerprints.snapshot(added, outputs, false, context.checkpoint());
                    for (var entry : extra.entrySet()) {
                        if (before.containsKey(entry.getKey()) && !Objects.equals(before.get(entry.getKey()), entry.getValue()))
                            throw new IOException("Build inputs changed while preparing tasks: " + entry.getKey());
                        before.put(entry.getKey(), entry.getValue());
                    }
                }
            } catch (IOException | RuntimeException error) { failure = error;throw error; }
        }
        private boolean excluded(Map.Entry<String, String> entry) {
            Path path = Path.of(entry.getKey());
            if ((entry.getValue().startsWith("directory:") || entry.getValue().equals("missing"))
                    && outputs.stream().anyMatch(output -> !output.equals(path) && output.startsWith(path))) return true;
            Collection<Path> roots = observed ? inputs : initialInputs;
            // 显式根及其内容覆盖祖先输出，即使文件刚被删除也不能丢失原来的摘要。
            if (roots.contains(path)) return false;
            return outputs.stream().anyMatch(output -> path.startsWith(output)
                    && roots.stream().noneMatch(root -> path.startsWith(root) && root.startsWith(output)));
        }
        private static List<String> changedPaths(Map<String, String> before, Map<String, String> after) {
            var changed = new TreeSet<>(before.keySet());changed.addAll(after.keySet());
            return changed.stream().filter(path -> !Objects.equals(before.get(path), after.get(path))).limit(12).toList();
        }
        synchronized String finish() throws IOException {
            checkFailure();var after = ProjectFingerprints.snapshot(inputs, outputs, !observed, context.checkpoint());
            if (!before.equals(after)) {
                throw new IOException("Build inputs changed during compilation; snapshot was not published: "
                        + changedPaths(before, after));
            }
            return ProjectFingerprints.fingerprint(after);
        }
        synchronized void checkFailure() throws IOException {
            if (failure instanceof IOException error) throw error;
            if (failure instanceof RuntimeException error) throw error;
        }
    }

    private static void ensureDirectory(Path path) throws IOException { Files.createDirectories(path);verifyDirectory(path); }
    private static void verifyDirectory(Path path) throws IOException {
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("Cache directory must not be redirected: " + path);
    }
    private static void deleteGeneration(Path key, Path generation) throws IOException {
        if (!generation.getParent().equals(key) || !generation.getFileName().toString().matches("generation-[0-9a-f-]{36}"))
            throw new IOException("Invalid owned cache generation: " + generation);
        verifyDirectory(key);verifyDirectory(generation);
        if (!generation.toRealPath().startsWith(key.toRealPath())) throw new IOException("Cache cleanup is outside its owned directory");
        Files.walkFileTree(generation, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (attributes.isOther()) throw new IOException("Cache cleanup must not traverse directory junctions: " + directory);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException { Files.delete(path);return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path path, IOException failure) throws IOException {
                if (failure != null) throw failure;Files.delete(path);return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void writeProperties(Path path, Properties properties) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), ".cache-state-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { properties.store(writer, "jsqlint project cache"); }
            if (Files.size(temporary) > MAX_STATE_BYTES) throw new IOException("Project cache state exceeds maximum size");
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private static Properties readProperties(Path path) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(MAX_STATE_BYTES + 1); }
        if (bytes.length > MAX_STATE_BYTES) throw new IOException("Project cache state exceeds maximum size");
        var properties = new Properties() {
            @Override public synchronized Object put(Object key, Object value) {
                if (containsKey(key) || size() > MAX_PATHS * 2 + 16) throw new IllegalArgumentException("Duplicate or excessive cache state fields");
                return super.put(key, value);
            }
        };
        try (var reader = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) { properties.load(reader); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid project cache state", invalid); }
        return properties;
    }
    private static void checkVersion(Properties state) throws IOException { if (!VERSION.equals(required(state, "schema.version"))) throw new IOException("Unsupported project cache schema"); }
    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);if (value == null) throw new IOException("Missing project cache field: " + key);return value;
    }
    private static void putPaths(Properties properties, String prefix, Collection<Path> paths) throws IOException {
        if (paths.size() > MAX_PATHS) throw new IOException("Too many recorded project inputs");
        properties.setProperty(prefix + ".count", Integer.toString(paths.size()));int index = 0;
        for (Path path : paths) properties.setProperty(prefix + "." + index++, path.toAbsolutePath().normalize().toString());
    }
    private static List<Path> paths(Properties properties, String prefix) throws IOException {
        int count;
        try { count = Integer.parseInt(required(properties, prefix + ".count")); }
        catch (NumberFormatException invalid) { throw new IOException("Invalid project input count", invalid); }
        if (count < 0 || count > MAX_PATHS) throw new IOException("Invalid project input count");
        var paths = new ArrayList<Path>(count);
        for (int i = 0; i < count; i++) {
            Path path;
            try { path = Path.of(required(properties, prefix + "." + i)); }
            catch (InvalidPathException invalid) { throw new IOException("Invalid project cache input path", invalid); }
            if (!path.isAbsolute()) throw new IOException("Project cache paths must be absolute");paths.add(path.normalize());
        }
        return List.copyOf(paths);
    }
}
