package org.u80dc.jsqlint.commons.location;

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
 * 收集一次性交给 OTF 编译的源码文件，不自行调用编译器。
 *
 * @author u80dc
 */
final class JavaSourceFiles {
    private JavaSourceFiles() {}

    static List<Path> collect(Path root) throws IOException {
        Path sourceRoot = Files.isDirectory(root.resolve("src/main/java")) ? root.resolve("src/main/java")
                : Files.isDirectory(root.resolve("src")) ? root.resolve("src") : root;
        List<Path> sources = new ArrayList<>();
        Set<String> excluded = Set.of(".git", ".gradle", ".idea", ".jsqlint");
        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                // An explicitly supplied source directory can contain packages named build, out or test.
                if (!dir.equals(sourceRoot) && (excluded.contains(dir.getFileName().toString())
                        || (!sourceRoot.equals(root) && dir.equals(root.resolve("src/test"))))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile() && file.toString().endsWith(".java")) sources.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        return sources.stream().sorted().toList();
    }
}
