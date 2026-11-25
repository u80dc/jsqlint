package org.u80dc.jsqlint.commons.compiler;

import org.u80dc.jsqlint.commons.location.InputLocationOptions;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/** 内容摘要只描述输入数据；不保存编译器、程序视图或分析状态。 */
final class ProjectFingerprints {
    private static final int MAX_ENTRIES = 200_000;
    private static final Set<String> INTERNAL = Set.of(".git", ".gradle", ".idea", ".jsqlint", "node_modules");
    private static final Set<String> LAUNCHER_ENVIRONMENT = Set.of("CODEX_TOOL_CALL_ID", "CODEX_THREAD_ID",
            "CODEX_SESSION_ID", "CODEX_APP_TOOLS_PIPE_PATH", "MAVEN_CMD_LINE_ARGS");

    private ProjectFingerprints() {}

    static String configuration(Path root, InputLocationOptions options) throws IOException {
        return configuration(root, options, System.getenv());
    }

    static String configuration(Path root, InputLocationOptions options, Map<String, String> environment) throws IOException {
        var values = new ArrayList<String>();
        // 构建/资源迁移语义变化也需要新的配置身份，避免开发版本继续命中旧产物。
        Collections.addAll(values, "jsqlint-project-cache-2", root.toRealPath().toString(), options.inputMode().name(),
                System.getProperty("java.home", ""), System.getProperty("java.version", ""), System.getProperty("java.vendor", ""),
                version(ProjectCompiler.class), version(org.apache.maven.cli.MavenCli.class), version(org.gradle.tooling.GradleConnector.class),
                Objects.toString(options.gradleInstallation(), ""));
        options.buildArguments().stream().filter(argument -> !refreshArgument(argument)).forEach(values::add);
        values.add("classpath");options.classpath().forEach(path -> values.add(path.toString()));
        values.add("additional inputs");options.additionalBuildInputs().forEach(path -> values.add(path.toString()));
        values.add("inherited system properties");
        var properties = new TreeMap<>(MavenProjectCompiler.systemPropertiesSnapshot());
        if (options.inputMode() == org.u80dc.jsqlint.commons.location.InputMode.MAVEN_PROJECT)
            properties.put(org.apache.maven.cli.MavenCli.MULTIMODULE_PROJECT_DIRECTORY, root.toRealPath().toString());
        properties.forEach((name, value) -> {
            if (!Set.of("sun.java.command", "java.class.path", "library.jansi.path").contains(name) && !name.startsWith("surefire.")) {
                values.add(name);values.add(value);
            }
        });
        values.add("inherited environment");
        new TreeMap<>(environment).forEach((name, value) -> {
            // 宿主请求标识和 IPC 地址不应让相同项目在另一进程选择不同的缓存。
            if (!name.startsWith("JAVA_MAIN_CLASS_") && !LAUNCHER_ENVIRONMENT.contains(name)) {
                values.add(name);values.add(value);
            }
        });
        values.add(Objects.toString(options.mavenRepositories().localRepository(),
                Path.of(System.getProperty("user.home"), ".m2", "repository").toString()));
        for (var repository : options.mavenRepositories().remoteRepositories()) {
            Collections.addAll(values, repository.id(), repository.url().toString(), Boolean.toString(repository.releasesEnabled()),
                    Boolean.toString(repository.snapshotsEnabled()), Objects.toString(repository.username(), ""),
                    Objects.toString(repository.password(), ""), Objects.toString(repository.mirrorOf(), ""));
        }
        // 配置原文（含凭据）只进入摘要，不写入清单或日志。
        return digest(values);
    }

    private static String version(Class<?> type) { return Objects.toString(type.getPackage().getImplementationVersion(), "development"); }

    static boolean refreshArgument(String argument) {
        return argument.equals("-U") || argument.equals("--update-snapshots") || argument.equals("--refresh-dependencies");
    }

    static List<Path> argumentInputs(Path root, InputLocationOptions options) {
        var paths = new ArrayList<Path>();
        for (String name : List.of(".mvn/maven.config", ".mvn/jvm.config", ".mvn/extensions.xml", "gradle.properties")) paths.add(root.resolve(name));
        if (options.inputMode() == org.u80dc.jsqlint.commons.location.InputMode.MAVEN_PROJECT)
            paths.add(Path.of(System.getProperty("user.home"), ".m2", "toolchains.xml"));
        if (options.inputMode() == org.u80dc.jsqlint.commons.location.InputMode.GRADLE_PROJECT) {
            String home = System.getProperty("gradle.user.home", System.getenv("GRADLE_USER_HOME"));
            Path directory = home == null ? Path.of(System.getProperty("user.home"), ".gradle") : Path.of(home);
            var arguments = options.buildArguments();
            for (int i = 0; i < arguments.size(); i++) {
                String argument = arguments.get(i);
                if ((argument.equals("-g") || argument.equals("--gradle-user-home")) && i + 1 < arguments.size())
                    directory = resolve(root, arguments.get(++i));
                else if (argument.startsWith("--gradle-user-home=")) directory = resolve(root, argument.substring("--gradle-user-home=".length()));
            }
            paths.add(directory.toAbsolutePath().normalize().resolve("gradle.properties"));
        }
        var arguments = options.buildArguments();
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            if (Set.of("-t", "--toolchains", "--global-toolchains", "-I", "--init-script").contains(argument)
                    && i + 1 < arguments.size()) paths.add(resolve(root, arguments.get(++i)));
            for (String prefix : List.of("--toolchains=", "--global-toolchains=", "--init-script="))
                if (argument.startsWith(prefix)) paths.add(resolve(root, argument.substring(prefix.length())));
            if (argument.startsWith("-Dorg.gradle.java.home=")) paths.add(resolve(root, argument.substring("-Dorg.gradle.java.home=".length())).resolve("release"));
        }
        if (options.gradleInstallation() != null) {
            paths.add(options.gradleInstallation().resolve("lib"));
            paths.add(options.gradleInstallation().resolve("gradle.properties"));
        }
        paths.add(Path.of(System.getProperty("java.home"), "release"));
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) paths.add(Path.of(javaHome).resolve("release"));
        return List.copyOf(paths);
    }

    private static Path resolve(Path root, String value) { Path path = Path.of(value);return path.isAbsolute() ? path : root.resolve(path); }

    static Map<String, String> snapshot(Collection<Path> roots, Collection<Path> outputs, boolean initial,
                                         Runnable checkpoint) throws IOException {
        return snapshot(roots, outputs, initial, true, checkpoint);
    }

    private static Map<String, String> snapshot(Collection<Path> roots, Collection<Path> outputs, boolean initial,
                                                 boolean inputFilters, Runnable checkpoint) throws IOException {
        var values = new TreeMap<String, String>();
        var outputRoots = outputs.stream().map(path -> path.toAbsolutePath().normalize()).toList();
        for (Path value : new LinkedHashSet<>(roots)) {
            checkpoint.run();
            Path root = value.toAbsolutePath().normalize();
            // 明确指定的输入根覆盖祖先输出（例如本地仓库中的模板目录），仍排除其内部生成产物。
            var excluded = outputRoots.stream().filter(output -> !root.startsWith(output)).toList();
            if (!Files.exists(root)) {
                if (excluded.stream().noneMatch(output -> output.startsWith(root))) add(values, root.toString(), "missing");
                continue;
            }
            if (Files.isRegularFile(root)) { add(values, root.toString(), file(root, checkpoint));continue; }
            if (!Files.isDirectory(root)) throw new IOException("Unsupported build input: " + root);
            Set<Path> visited = new HashSet<>();
            Files.walkFileTree(root, EnumSet.of(FileVisitOption.FOLLOW_LINKS), 128, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                    checkpoint.run();
                    Path absolute = directory.toAbsolutePath().normalize();
                    boolean explicit = root.startsWith(absolute);
                    if (!explicit && (excluded.stream().anyMatch(absolute::startsWith)
                            || inputFilters && INTERNAL.contains(directory.getFileName().toString())
                            || initial && Set.of("target", "build").contains(directory.getFileName().toString())))
                        return FileVisitResult.SKIP_SUBTREE;
                    Path real = directory.toRealPath();
                    var link = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (link.isSymbolicLink() || link.isOther()) add(values, absolute.toString(), "linked-directory:" + real);
                    if (!visited.add(real)) return FileVisitResult.SKIP_SUBTREE;
                    // 精确输出文件首次生成时会创建父目录；这些目录标记不代表新的构建输入。
                    // 仍遍历其中的文件；整个源码根首次由生成任务创建时采用同一规则。
                    if (!link.isSymbolicLink() && !link.isOther()
                            && excluded.stream().noneMatch(output -> output.startsWith(absolute)))
                        add(values, absolute.toString(), "directory:" + real);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                    checkpoint.run();
                    if (inputFilters && path.getFileName().toString().equals(".flattened-pom.xml")) return FileVisitResult.CONTINUE;
                    if (!excluded.stream().anyMatch(path.toAbsolutePath().normalize()::startsWith))
                        add(values, path.toAbsolutePath().normalize().toString(), file(path, checkpoint));
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path path, IOException failure) throws IOException {
                    if (failure instanceof FileSystemLoopException) return FileVisitResult.CONTINUE;
                    throw failure;
                }
            });
        }
        return values;
    }

    private static void add(Map<String, String> values, String path, String hash) throws IOException {
        if (values.size() >= MAX_ENTRIES && !values.containsKey(path)) throw new IOException("Too many build inputs to fingerprint");
        values.put(path, hash);
    }

    static String fingerprint(Map<String, String> values) {
        var flattened = new ArrayList<String>(values.size() * 2);
        values.forEach((path, hash) -> { flattened.add(path);flattened.add(hash); });
        return digest(flattened);
    }

    static String artifacts(CompiledProject project, Runnable checkpoint) throws IOException {
        var roots = new LinkedHashSet<Path>();
        roots.addAll(project.classDirectories());roots.addAll(project.dependencies());roots.addAll(project.resourceRoots());
        return fingerprint(snapshot(roots, List.of(), false, false, checkpoint));
    }

    static String file(Path path, Runnable checkpoint) throws IOException {
        MessageDigest hash = sha256();
        try (var stream = Files.newInputStream(path)) {
            byte[] buffer = new byte[65_536];int read;
            while ((read = stream.read(buffer)) >= 0) { checkpoint.run();hash.update(buffer, 0, read); }
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    private static String digest(Collection<String> values) {
        MessageDigest hash = sha256();
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            hash.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));hash.update((byte) ':');hash.update(bytes);
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
