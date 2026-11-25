package org.u80dc.jsqlint.commons.compiler;

import org.u80dc.jsqlint.commons.location.InputMode;

import java.io.*;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 版本化的 UTF-8 Properties 清单；只保存项目输入描述，读取时不编译或校验产物存在性。 */
public final class CompiledProjectManifest {
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_BYTES = 1_048_576;
    private static final int MAX_PATHS = 8192;
    private static final int MAX_VALUE_LENGTH = 32_768;
    private static final Set<String> HEADER = Set.of("schema.version", "project.directory", "project.mode",
            "classes.count", "dependencies.count", "resources.count");

    private CompiledProjectManifest() {}

    /** 规范化为绝对路径，同目录临时写入成功后原子替换，失败不覆盖旧清单。 */
    public static void write(Path manifest, CompiledProject project) throws IOException {
        Objects.requireNonNull(manifest, "manifest");Objects.requireNonNull(project, "project");
        Path target = manifest.toAbsolutePath().normalize();
        if (target.getFileName() == null || Files.isDirectory(target)) throw invalid(target, "Manifest must name a file");
        long paths = (long) project.classDirectories().size() + project.dependencies().size() + project.resourceRoots().size();
        if (paths > MAX_PATHS) throw invalid(target, "Too many input paths; maximum is " + MAX_PATHS);
        var properties = new Properties();
        properties.setProperty("schema.version", Integer.toString(SCHEMA_VERSION));
        properties.setProperty("project.directory", absolute(project.directory(), target));
        if (project.inputMode() == null) throw invalid(target, "Missing project input mode");
        properties.setProperty("project.mode", project.inputMode().name());
        putPaths(properties, "classes", project.classDirectories(), target);
        putPaths(properties, "dependencies", project.dependencies(), target);
        putPaths(properties, "resources", project.resourceRoots(), target);

        Path parent = target.getParent();Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".jsqlint-manifest-", ".tmp");
        try {
            try (var writer = new BufferedWriter(new OutputStreamWriter(new LimitedOutput(Files.newOutputStream(temporary)), StandardCharsets.UTF_8))) {
                properties.store(writer, "jsqlint compiled project inputs; schema version " + SCHEMA_VERSION);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** 只解析描述；原源码目录和产物不存在时仍可读取，打开输入时再验证 I/O。 */
    public static CompiledProject read(Path manifest) throws IOException {
        Objects.requireNonNull(manifest, "manifest");
        Path source = manifest.toAbsolutePath().normalize();
        byte[] bytes;
        try (var stream = Files.newInputStream(source)) { bytes = stream.readNBytes(MAX_BYTES + 1); }
        if (bytes.length > MAX_BYTES) throw invalid(source, "Manifest exceeds maximum size " + MAX_BYTES);
        var properties = new StrictProperties();
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (var reader = new InputStreamReader(new ByteArrayInputStream(bytes), decoder)) {
            properties.load(reader);
        } catch (IllegalArgumentException error) {
            throw invalid(source, error.getMessage(), error);
        } catch (IOException error) {
            throw invalid(source, "Cannot decode UTF-8 Properties", error);
        }
        if (!Integer.toString(SCHEMA_VERSION).equals(required(properties, "schema.version", source)))
            throw invalid(source, "Unsupported schema.version; expected " + SCHEMA_VERSION);
        int classes = count(properties, "classes.count", source);
        int dependencies = count(properties, "dependencies.count", source);
        int resources = count(properties, "resources.count", source);
        if ((long) classes + dependencies + resources > MAX_PATHS)
            throw invalid(source, "Too many input paths; maximum is " + MAX_PATHS);
        var known = new HashSet<>(HEADER);
        Path directory = path(required(properties, "project.directory", source), source);
        InputMode mode;
        try { mode = InputMode.valueOf(required(properties, "project.mode", source)); }
        catch (IllegalArgumentException error) { throw invalid(source, "Unknown project.mode", error); }
        List<Path> classDirectories = paths(properties, "classes", classes, known, source);
        List<Path> libraries = paths(properties, "dependencies", dependencies, known, source);
        List<Path> roots = paths(properties, "resources", resources, known, source);
        for (Object key : properties.keySet()) if (!known.contains(key)) throw invalid(source, "Unknown field: " + key);
        return new CompiledProject(directory, mode, classDirectories, libraries, roots);
    }

    private static void putPaths(Properties properties, String prefix, List<Path> paths, Path manifest) throws IOException {
        properties.setProperty(prefix + ".count", Integer.toString(paths.size()));
        for (int i = 0; i < paths.size(); i++) properties.setProperty(prefix + "." + i, absolute(paths.get(i), manifest));
    }
    private static String absolute(Path path, Path manifest) throws IOException {
        if (path == null) throw invalid(manifest, "Missing path");
        String value = path.toAbsolutePath().normalize().toString();
        if (value.length() > MAX_VALUE_LENGTH) throw invalid(manifest, "Path exceeds maximum length");
        return value;
    }
    private static String required(Properties properties, String key, Path manifest) throws IOException {
        String value = properties.getProperty(key);
        if (value == null) throw invalid(manifest, "Missing field: " + key);
        return value;
    }
    private static int count(Properties properties, String key, Path manifest) throws IOException {
        String value = required(properties, key, manifest);
        if (value.length() > 5 || !value.matches("0|[1-9][0-9]*")) throw invalid(manifest, "Invalid path count: " + key);
        int result = Integer.parseInt(value);
        if (result > MAX_PATHS) throw invalid(manifest, "Too many input paths: " + key);
        return result;
    }
    private static List<Path> paths(Properties properties, String prefix, int count, Set<String> known, Path manifest) throws IOException {
        var result = new ArrayList<Path>(count);
        for (int i = 0; i < count; i++) {
            String key = prefix + "." + i;known.add(key);
            result.add(path(required(properties, key, manifest), manifest));
        }
        return List.copyOf(result);
    }
    private static Path path(String value, Path manifest) throws IOException {
        try {
            Path path = Path.of(value);
            if (value.isBlank() || !path.isAbsolute()) throw invalid(manifest, "Internal paths must be absolute");
            return path.normalize();
        } catch (InvalidPathException error) { throw invalid(manifest, "Invalid internal path", error); }
    }
    private static IOException invalid(Path manifest, String message) { return new IOException("Invalid compiled project manifest " + manifest + ": " + message); }
    private static IOException invalid(Path manifest, String message, Throwable cause) { return new IOException("Invalid compiled project manifest " + manifest + ": " + message, cause); }

    private static final class StrictProperties extends Properties {
        @Override public synchronized Object put(Object key, Object value) {
            if (!(key instanceof String name) || !(value instanceof String text)) throw new IllegalArgumentException("Manifest fields must be strings");
            if (name.length() > 64 || text.length() > MAX_VALUE_LENGTH) throw new IllegalArgumentException("Manifest field exceeds maximum length");
            if (containsKey(key)) throw new IllegalArgumentException("Duplicate field: " + name);
            if (size() >= MAX_PATHS + HEADER.size()) throw new IllegalArgumentException("Too many manifest fields");
            return super.put(key, value);
        }
    }
    private static final class LimitedOutput extends FilterOutputStream {
        private int written;
        private LimitedOutput(OutputStream output) { super(output); }
        @Override public void write(int value) throws IOException { limit(1);out.write(value); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException { limit(length);out.write(bytes, offset, length); }
        private void limit(int length) throws IOException {
            if (length > MAX_BYTES - written) throw new IOException("Compiled project manifest exceeds maximum size " + MAX_BYTES);
            written += length;
        }
    }
}
