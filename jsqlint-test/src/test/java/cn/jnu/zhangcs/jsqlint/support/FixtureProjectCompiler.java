package cn.jnu.zhangcs.jsqlint.support;

import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public final class FixtureProjectCompiler {

    private final Path tempDir;

    public FixtureProjectCompiler(@TempDir Path tempDir) {
        this.tempDir = tempDir;
    }

    public Path compile(String fixtureName) throws IOException {
        Path fixtureRoot = fixtureRoot(fixtureName);
        Path output = tempDir.resolve(fixtureName).resolve("classes");
        Files.createDirectories(output);

        List<Path> sources = javaSources(fixtureRoot.resolve("src/main/java"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Fixture compilation requires a JDK");
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(sources);
            List<String> options = List.of(
                    "-d", output.toString(),
                    "-parameters",
                    "-classpath", System.getProperty("java.class.path")
            );
            Boolean compiled = compiler.getTask(null, fileManager, null, options, null, units).call();
            assertEquals(Boolean.TRUE, compiled, "Failed to compile fixture: " + fixtureName);
        }

        copyDirectory(fixtureRoot.resolve("src/main/resources"), output);
        return output;
    }

    private Path fixtureRoot(String fixtureName) {
        return Path.of("../shared-test-resources", fixtureName);
    }

    private List<Path> javaSources(Path sourceRoot) throws IOException {
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            return stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) {
            return;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(source)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        }
        for (Path file : files) {
            Path relative = source.relativize(file);
            Path destination = target.resolve(relative);
            Files.createDirectories(destination.getParent());
            Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
