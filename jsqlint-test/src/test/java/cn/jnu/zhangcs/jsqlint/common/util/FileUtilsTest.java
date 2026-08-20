package cn.jnu.zhangcs.jsqlint.common.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FileUtils} 的行为测试。
 *
 * <p>全部基于 JUnit 临时目录，不影响真实的 .jsqlint 工作目录。</p>
 */
class FileUtilsTest {

    @TempDir
    Path tempDir;

    @Test
    void workDirLivesUnderTmpJsqlint() {
        Path workDir = FileUtils.getWorkDir();
        assertEquals(Path.of(System.getProperty("java.io.tmpdir"), ".jsqlint"), workDir);
    }

    @Test
    void ensureWorkDirCreatesDirectory() {
        Path dir = FileUtils.ensureWorkDir();
        assertTrue(Files.isDirectory(dir));
    }

    @Test
    void createSubDirCreatesNestedDirectoryUnderWorkDir() {
        String subName = "test-sub-" + System.nanoTime();
        Path subDir = FileUtils.createSubDir(subName);
        try {
            assertTrue(Files.isDirectory(subDir));
            assertEquals(FileUtils.getWorkDir(), subDir.getParent());
        } finally {
            FileUtils.deleteSubDir(subName);
        }
    }

    @Test
    void createSubDirIsIdempotent() {
        String subName = "test-idem-" + System.nanoTime();
        Path first = FileUtils.createSubDir(subName);
        try {
            Path second = FileUtils.createSubDir(subName);
            assertEquals(first, second);
        } finally {
            FileUtils.deleteSubDir(subName);
        }
    }

    @Test
    void deleteDirectoryRecursiveRemovesNestedContent() throws IOException {
        Path dir = tempDir.resolve("to-delete");
        Files.createDirectories(dir.resolve("a/b"));
        Files.writeString(dir.resolve("a/b/file.txt"), "x");
        Files.writeString(dir.resolve("top.txt"), "y");

        FileUtils.deleteDirectoryRecursive(dir);

        assertFalse(Files.exists(dir));
    }

    @Test
    void deleteDirectoryRecursiveToleratesMissingDirectory() {
        FileUtils.deleteDirectoryRecursive(tempDir.resolve("never-existed"));
        assertFalse(Files.exists(tempDir.resolve("never-existed")));
    }

    @Test
    void deleteSubDirRemovesOnlyThatSubDir() throws IOException {
        String subName = "test-del-" + System.nanoTime();
        Path subDir = FileUtils.createSubDir(subName);
        Files.writeString(subDir.resolve("marker.txt"), "m");
        Path sibling = FileUtils.createSubDir("test-sibling-" + System.nanoTime());
        try {
            FileUtils.deleteSubDir(subName);
            assertFalse(Files.exists(subDir));
            assertTrue(Files.isDirectory(sibling));
        } finally {
            FileUtils.deleteSubDir(sibling.getFileName().toString());
        }
    }

    @Test
    void copyFileCopiesWithCustomName() throws IOException {
        Path source = tempDir.resolve("orig.txt");
        Files.writeString(source, "content");
        Path targetDir = tempDir.resolve("out");

        Path copied = FileUtils.copyFile(source, targetDir, "renamed.txt");

        assertEquals("content", Files.readString(copied));
        assertEquals("renamed.txt", copied.getFileName().toString());
        assertTrue(Files.isDirectory(targetDir));
    }

    @Test
    void copyFileKeepsOriginalNameWhenTargetNameNull() throws IOException {
        Path source = tempDir.resolve("orig2.txt");
        Files.writeString(source, "content2");

        Path copied = FileUtils.copyFile(source, tempDir.resolve("out2"), null);

        assertEquals("orig2.txt", copied.getFileName().toString());
        assertEquals("content2", Files.readString(copied));
    }

    @Test
    void copyFileOverwritesExistingTarget() throws IOException {
        Path source = tempDir.resolve("v1.txt");
        Files.writeString(source, "new-content");
        Path targetDir = tempDir.resolve("out3");
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("v1.txt"), "old-content");

        FileUtils.copyFile(source, targetDir, null);

        assertEquals("new-content", Files.readString(targetDir.resolve("v1.txt")));
    }

    @Test
    void copyFileThrowsOnMissingSource() {
        assertThrows(RuntimeException.class,
                () -> FileUtils.copyFile(tempDir.resolve("ghost.txt"), tempDir, null));
    }

    @Test
    void isDirectoryNotEmptyDistinguishesStates() throws IOException {
        Path empty = tempDir.resolve("empty");
        Path filled = tempDir.resolve("filled");
        Files.createDirectories(empty);
        Files.createDirectories(filled);
        Files.writeString(filled.resolve("a.txt"), "a");

        assertFalse(FileUtils.isDirectoryNotEmpty(tempDir.resolve("missing")));
        assertFalse(FileUtils.isDirectoryNotEmpty(empty));
        assertTrue(FileUtils.isDirectoryNotEmpty(filled));
    }

    @Test
    void isDirectoryNotEmptyReturnsFalseForRegularFile() throws IOException {
        Path file = tempDir.resolve("plain.txt");
        Files.writeString(file, "x");
        assertFalse(FileUtils.isDirectoryNotEmpty(file));
    }

    @Test
    void copyDirectoryCopiesNestedStructure() throws IOException {
        Path source = tempDir.resolve("src");
        Files.createDirectories(source.resolve("a/b"));
        Files.writeString(source.resolve("a/b/deep.txt"), "deep");
        Files.writeString(source.resolve("top.txt"), "top");
        Path target = tempDir.resolve("dst");

        FileUtils.copyDirectory(source, target);

        assertEquals("deep", Files.readString(target.resolve("a/b/deep.txt")));
        assertEquals("top", Files.readString(target.resolve("top.txt")));
    }

    @Test
    void copyDirectorySilentlySkipsMissingSource() {
        FileUtils.copyDirectory(tempDir.resolve("no-such-src"), tempDir.resolve("no-such-dst"));
        assertFalse(Files.exists(tempDir.resolve("no-such-dst")));
    }

    @Test
    void copyDirectoryOverwritesExistingTargetFiles() throws IOException {
        Path source = tempDir.resolve("src2");
        Path target = tempDir.resolve("dst2");
        Files.createDirectories(source);
        Files.createDirectories(target);
        Files.writeString(source.resolve("f.txt"), "fresh");
        Files.writeString(target.resolve("f.txt"), "stale");

        FileUtils.copyDirectory(source, target);

        assertEquals("fresh", Files.readString(target.resolve("f.txt")));
    }

    @Test
    void deleteDirectoryRemovesFileDirectly() throws IOException {
        Path file = tempDir.resolve("single.txt");
        Files.writeString(file, "x");

        FileUtils.deleteDirectory(file);

        assertFalse(Files.exists(file));
    }

    @Test
    void deleteDirectoryRemovesNestedTree() throws IOException {
        Path dir = tempDir.resolve("tree");
        Files.createDirectories(dir.resolve("x/y"));
        Files.writeString(dir.resolve("x/y/z.txt"), "z");

        FileUtils.deleteDirectory(dir);

        assertFalse(Files.exists(dir));
    }

    @Test
    void deleteDirectoryToleratesMissingPath() {
        FileUtils.deleteDirectory(tempDir.resolve("not-there"));
        assertFalse(Files.exists(tempDir.resolve("not-there")));
    }
}
