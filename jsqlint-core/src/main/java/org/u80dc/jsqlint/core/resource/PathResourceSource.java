package org.u80dc.jsqlint.core.resource;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * 单个文件、目录或 JAR 中的资源。目录不跟随符号链接，不隐式展开其中的依赖 JAR。
 * include 筛选目录内文件及单个普通文件；JAR 条目由模块资源规则筛选。
 * @author u80dc
 */
public record PathResourceSource(Path path, Predicate<Path> include) implements AnalysisResourceSource {
    public PathResourceSource {
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        Objects.requireNonNull(include, "include");
    }

    public PathResourceSource(Path path) {
        this(path, file -> true);
    }

    @Override
    public Stream<? extends AnalysisResource> resources() throws IOException {
        if (Files.isDirectory(path))
            return Files.walk(path).filter(Files::isRegularFile).filter(include).map(FileResource::new);
        if (!Files.isRegularFile(path)) throw new FileNotFoundException(path.toString());
        if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            // 枚举流持有 JAR 句柄，必须在流关闭时释放，不能在返回流前关闭。
            JarFile jar = new JarFile(path.toFile());
            return jar.stream().filter(entry -> !entry.isDirectory())
                    .map(entry -> new JarResource(path, entry.getName()))
                    .onClose(() -> {
                        try { jar.close(); } catch (IOException e) { throw new UncheckedIOException(e); }
                    });
        }
        return include.test(path) ? Stream.of(new FileResource(path)) : Stream.empty();
    }

    private record FileResource(Path path) implements AnalysisResource {
        public URI uri() { return path.toUri(); }
        public String name() { return path.getFileName().toString(); }
        public InputStream openStream() throws IOException { return Files.newInputStream(path); }
    }

    private record JarResource(Path jar, String name) implements AnalysisResource {
        public URI uri() {
            try {
                // 编码空格、井号等条目名字符，避免被 URI 解释成分隔符或片段标识。
                String entry = new URI(null, null, "/" + name, null).toASCIIString().substring(1);
                return URI.create("jar:" + jar.toUri().toASCIIString() + "!/" + entry);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("Invalid JAR entry: " + name, e);
            }
        }

        public InputStream openStream() throws IOException {
            // 资源可在枚举结束后读取，也可能被多个模块读取，因此每次打开独立 JAR 句柄。
            JarFile file = new JarFile(jar.toFile());
            try {
                var entry = file.getJarEntry(name);
                if (entry == null) throw new FileNotFoundException(uri().toString());
                return new FilterInputStream(file.getInputStream(entry)) {
                    @Override
                    public void close() throws IOException {
                        // 关闭内容流时同时释放本次读取持有的 JAR，两个动作都需要尝试。
                        try { super.close(); } finally { file.close(); }
                    }
                };
            } catch (IOException | RuntimeException e) {
                try { file.close(); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
                throw e;
            }
        }
    }
}
