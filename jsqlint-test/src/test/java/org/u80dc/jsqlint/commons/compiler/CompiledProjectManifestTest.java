package org.u80dc.jsqlint.commons.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.commons.location.InputMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/** 清单是可保留的输入描述，格式失败和替换失败不能修改编译产物。 */
class CompiledProjectManifestTest {
    @TempDir Path temporary;

    @Test
    void roundTripPreservesOrderAndNormalizesPathsWithoutOpeningProducts() throws Exception {
        var project = descriptor();
        Path file = temporary.resolve("inputs.properties");
        CompiledProjectManifest.write(file, project);
        var restored = CompiledProjectManifest.read(file);
        assertEquals(normalized(project), restored);
        assertFalse(Files.exists(project.directory()));
        assertTrue(restored.classDirectories().stream().allMatch(Path::isAbsolute));
        assertEquals(List.of(temporary.resolve("second classes"), temporary.resolve("first-类目录#1")), restored.classDirectories());
        assertEquals(List.of(temporary.resolve("second dependency.jar"), temporary.resolve("first.jar")), restored.dependencies());
        assertEquals(List.of(temporary.resolve("resources"), temporary.resolve("application.properties")), restored.resourceRoots());
        assertThrows(UnsupportedOperationException.class, () -> restored.resourceRoots().add(temporary));
        var relative = new CompiledProject(Path.of("uncreated-manifest-source-" + UUID.randomUUID()), InputMode.AUTO,
                List.of(Path.of("relative-classes")), List.of(Path.of("relative-library.jar")), List.of(Path.of("relative-resources")));
        CompiledProjectManifest.write(file, relative);
        assertEquals(normalized(relative), CompiledProjectManifest.read(file));
        assertFalse(Files.exists(relative.directory()));
    }

    @Test
    void supportsRelativeManifestNamesAndAtomicallyReplacesTheOldDescription() throws Exception {
        Path parent = Path.of("target", "manifest-" + UUID.randomUUID());
        Path file = parent.resolve("inputs.properties");
        try {
            var first = descriptor();
            CompiledProjectManifest.write(file, first);
            assertEquals(normalized(first), CompiledProjectManifest.read(file));
            var second = new CompiledProject(first.directory(), InputMode.GRADLE_PROJECT,
                    List.of(first.classDirectories().getLast()), first.dependencies(), List.of(first.resourceRoots().getLast()));
            CompiledProjectManifest.write(file, second);
            assertEquals(normalized(second), CompiledProjectManifest.read(file));
            try (var children = Files.list(parent)) { assertEquals(List.of(file.getFileName()), children.map(Path::getFileName).toList()); }
        } finally {
            Files.deleteIfExists(file);Files.deleteIfExists(parent);
        }
    }

    @Test
    void rejectsDuplicateUnknownMissingVersionTypeAndRelativePathFields() throws Exception {
        Path file = temporary.resolve("invalid.properties");
        CompiledProjectManifest.write(file, descriptor());
        String valid = Files.readString(file);
        List<UnaryOperator<String>> invalid = List.of(
                text -> text.replace("schema.version=1", "schema.version=2"),
                text -> text.replace("schema.version=1", "schema.version=01"),
                text -> text + "\nschema.version=1\n",
                text -> text + "\nschema\\u002eversion=1\n",
                text -> text + "\nunknown.field=value\n",
                text -> text.replace("project.mode=MAVEN_PROJECT", "project.mode=UNKNOWN"),
                text -> text.replace("classes.count=2", "classes.count=-1"),
                text -> text.replace("classes.count=2", "classes.count=true"),
                text -> text.replace("classes.count=2", "classes.count=1.5"),
                text -> text.replace("classes.count=2", "classes.count=8193"),
                text -> text.replaceAll("(?m)^resources\\.count=.*\\R?", ""),
                text -> text.replaceAll("(?m)^dependencies\\.0=.*\\R?", ""),
                text -> text.replaceAll("(?m)^resources\\.0=.*$", "resources.0=relative/path.xml"),
                text -> text + "\nclasses.02=relative\n");
        for (var mutation : invalid) {
            Files.writeString(file, mutation.apply(valid));
            assertThrows(IOException.class, () -> CompiledProjectManifest.read(file), Files.readString(file));
        }
    }

    @Test
    void limitsFileFieldAndPathCountsAndKeepsExistingManifestAndArtifacts() throws Exception {
        Path classes = Files.createDirectories(temporary.resolve("classes"));
        Path artifact = Files.write(classes.resolve("Untouched.class"), new byte[]{1, 2, 3});
        Path resource = Files.writeString(temporary.resolve("mapper.xml"), "<mapper/>");
        var project = new CompiledProject(temporary.resolve("missing-source"), InputMode.MAVEN_PROJECT,
                List.of(classes), List.of(), List.of(resource));
        Path file = temporary.resolve("inputs.properties");
        CompiledProjectManifest.write(file, project);
        byte[] original = Files.readAllBytes(file);
        var excessive = new CompiledProject(project.directory(), project.inputMode(),
                Collections.nCopies(8193, classes), List.of(), List.of(resource));
        assertThrows(IOException.class, () -> CompiledProjectManifest.write(file, excessive));
        assertArrayEquals(original, Files.readAllBytes(file));
        var excessiveText = new CompiledProject(project.directory(), project.inputMode(), project.classDirectories(), List.of(),
                Collections.nCopies(700, temporary.resolve("long-path-" + "x".repeat(1800))));
        assertThrows(IOException.class, () -> CompiledProjectManifest.write(file, excessiveText));
        assertArrayEquals(original, Files.readAllBytes(file));

        Files.write(file, new byte[1_048_577]);
        assertThrows(IOException.class, () -> CompiledProjectManifest.read(file));
        Files.writeString(file, "schema.version=1\nproject.directory=" + "x".repeat(32_769));
        assertThrows(IOException.class, () -> CompiledProjectManifest.read(file));
        var fields = new StringBuilder("schema.version=1\n");
        for (int i = 0; i < 8199; i++) fields.append("unknown.").append(i).append("=x\n");
        Files.writeString(file, fields);
        assertThrows(IOException.class, () -> CompiledProjectManifest.read(file));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(artifact));
        assertEquals("<mapper/>", Files.readString(resource));
        try (var paths = Files.list(temporary)) { assertTrue(paths.noneMatch(path -> path.getFileName().toString().startsWith(".jsqlint-manifest-"))); }
    }

    @Test
    void rejectsMalformedUtf8AndDoesNotCreateTheMissingSourceDirectory() throws Exception {
        Path file = temporary.resolve("inputs.properties");
        Files.write(file, new byte[]{(byte) 0xc3, 0x28});
        assertThrows(IOException.class, () -> CompiledProjectManifest.read(file));
        assertFalse(Files.exists(descriptor().directory()));
    }

    private CompiledProject descriptor() {
        return new CompiledProject(temporary.resolve("missing-source"), InputMode.MAVEN_PROJECT,
                List.of(temporary.resolve("second classes"), temporary.resolve("first-类目录#1")),
                List.of(temporary.resolve("second dependency.jar"), temporary.resolve("first.jar")),
                List.of(temporary.resolve("resources"), temporary.resolve("application.properties")));
    }
    private static CompiledProject normalized(CompiledProject project) {
        return new CompiledProject(project.directory().toAbsolutePath().normalize(), project.inputMode(),
                project.classDirectories().stream().map(path -> path.toAbsolutePath().normalize()).toList(),
                project.dependencies().stream().map(path -> path.toAbsolutePath().normalize()).toList(),
                project.resourceRoots().stream().map(path -> path.toAbsolutePath().normalize()).toList());
    }
}
