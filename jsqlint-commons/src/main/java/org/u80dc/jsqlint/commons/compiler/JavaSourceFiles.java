package org.u80dc.jsqlint.commons.compiler;

import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 收集一次性交给 Java 源码编译器的文件，不自行执行编译。
 *
 * @author u80dc
 */
public final class JavaSourceFiles {
    private JavaSourceFiles() {}

    /** 从项目目录或源码根目录收集主代码，保留显式源码根下的合法包名。 */
    public static List<Path> collect(Path root) throws IOException {
        Path sourceRoot = Files.isDirectory(root.resolve("src/main/java")) ? root.resolve("src/main/java")
                : Files.isDirectory(root.resolve("src")) ? root.resolve("src") : root;
        List<Path> sources = new ArrayList<>();
        Set<String> excluded = Set.of(".git", ".gradle", ".idea", ".jsqlint");
        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
            @Override
            public @NonNull FileVisitResult preVisitDirectory(@NonNull Path dir, @NonNull BasicFileAttributes attributes) {
                // An explicitly supplied source directory can contain packages named build, out or test.
                if (!dir.equals(sourceRoot) && (excluded.contains(dir.getFileName().toString())
                        || (!sourceRoot.equals(root) && dir.equals(root.resolve("src/test"))))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public @NonNull FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attributes) {
                if (attributes.isRegularFile() && file.toString().endsWith(".java")) sources.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        return sources.stream().sorted().toList();
    }
}
