package org.u80dc.jsqlint.core;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/**
 * Analyzer 单次编译输出的生命周期。只管理 .jsqlint 下分配的目录，不删除父目录或项目构建缓存。
 *
 * @author u80dc
 */
final class ClassOutputDirectory implements AutoCloseable {
    private final Path path;
    private final Path realRoot;
    private final boolean cleanup;

    private ClassOutputDirectory(Path path, Path realRoot, boolean cleanup) {
        this.path = path;
        this.realRoot = realRoot;
        this.cleanup = cleanup;
    }

    static ClassOutputDirectory prepare(Path output, Path source, @Nullable Path dependencies,
                                        boolean reuse, boolean cleanup) throws IOException {
        Path root = output.getParent();
        Files.createDirectories(root);
        Path realRoot = root.getParent().toRealPath().resolve(root.getFileName());
        verifyDirectory(root, realRoot);
        Path realOutput = realRoot.resolve(output.getFileName());
        // 必须在预清理前校验输入，不能等到编译器准备目录时才发现输入已被删除。
        protectInput(source, output, realOutput);
        if (dependencies != null) protectInput(dependencies, output, realOutput);

        if (reuse) Files.createDirectories(output);
        else Files.createDirectory(output); // 随机标识如发生碰撞则失败，不能清理另一次调用的产物。
        verifyDirectory(output, realOutput);
        var directory = new ClassOutputDirectory(output, realRoot, cleanup);
        if (reuse) directory.deleteContents(false);
        return directory;
    }

    private static void protectInput(Path input, Path output, Path realOutput) throws IOException {
        if (input.startsWith(output) || input.toRealPath().startsWith(realOutput)) {
            throw new IOException("Class output directory must not contain an input or dependency path: " + output);
        }
    }

    /** 拒绝输出根目录的链接或重定向，避免清理范围越过分配的目录。 */
    private static void verifyDirectory(Path directory, Path expectedRealPath) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()
                || !directory.toRealPath().equals(expectedRealPath)) {
            throw new IOException("Class output must be a directory without redirection: " + directory);
        }
    }

    private void deleteContents(boolean removeDirectory) throws IOException {
        verifyDirectory(path.getParent(), realRoot);
        if (Files.notExists(path, NOFOLLOW_LINKS)) return;
        Path realOutput = realRoot.resolve(path.getFileName());
        verifyDirectory(path, realOutput);
        // 不跟随符号链接；链接作为目录项删除，不遍历其目标。额外校验 Windows 目录联接。
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public @NonNull FileVisitResult preVisitDirectory(@NonNull Path directory, @NonNull BasicFileAttributes attributes) throws IOException {
                verifyDirectory(directory, realOutput.resolve(path.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public @NonNull FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public @NonNull FileVisitResult postVisitDirectory(@NonNull Path directory, @Nullable IOException error) throws IOException {
                if (error != null) throw error;
                if (removeDirectory || !directory.equals(path)) Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    public void close() throws IOException {
        if (cleanup) deleteContents(true);
    }
}
