package cn.jnu.zhangcs.jsqlint.common.util;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 文件操作工具类
 *
 * @author zhangcs
 */
@Slf4j
public class FileUtils {

    private static final String JSQLINT_WORK_DIR = ".jsqlint";

    /**
     * 获取 JSQLint 工作目录路径
     *
     * @return .jsqlint 工作目录路径
     */
    public static Path getWorkDir() {
        return Path.of(System.getProperty("java.io.tmpdir"), JSQLINT_WORK_DIR);
    }

    /**
     * 确保工作目录存在，不存在则创建
     *
     * @return .jsqlint 工作目录路径
     */
    public static Path ensureWorkDir() {
        Path dir = getWorkDir();
        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
                log.info("创建工作目录: {}", dir);
            } catch (IOException e) {
                log.error("创建工作目录失败: {}", e.getMessage());
                throw new RuntimeException("无法创建工作目录", e);
            }
        }
        return dir;
    }

    /**
     * 在工作目录下创建子目录
     *
     * @param subDirName 子目录名称
     * @return 子目录路径
     */
    public static Path createSubDir(String subDirName) {
        Path dir = ensureWorkDir();
        Path subDir = dir.resolve(subDirName);
        if (!Files.exists(subDir)) {
            try {
                Files.createDirectories(subDir);
                log.info("创建子目录: {}", subDir);
            } catch (IOException e) {
                log.error("创建子目录失败: {}", e.getMessage());
                throw new RuntimeException("无法创建子目录", e);
            }
        }
        return subDir;
    }

    /**
     * 递归删除目录及其内容（带重试机制）
     *
     * @param directory 待删除的目录路径
     */
    public static void deleteDirectoryRecursive(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }

        // 触发垃圾回收，帮助释放文件句柄
        System.gc();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                deleteDirectoryInternal(directory);
                log.info("删除目录成功: {}", directory);
                return;
            } catch (Exception e) {
                if (attempt < maxRetries) {
                    log.warn("删除目录失败（第{}次尝试）: {}, 原因: {}, 等待后重试...", attempt, directory, e.getMessage());
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    log.error("删除目录失败（已达最大重试次数）: {}, 原因: {}", directory, e.getMessage());
                }
            }
        }
    }

    /**
     * 内部删除方法
     */
    private static void deleteDirectoryInternal(Path directory) throws IOException {
        try (Stream<Path> stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            // 在Windows上，某些文件可能被锁定，尝试设置只读属性后重试
                            try {
                                java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx");
                                if (path.toFile().exists() && path.toFile().canWrite() == false) {
                                    path.toFile().setWritable(true);
                                    Files.deleteIfExists(path);
                                }
                            } catch (Exception ex) {
                                log.debug("删除文件失败: {}, 原因: {}", path, e.getMessage());
                            }
                            // 不再抛出异常，让日志记录即可
                            log.debug("无法删除文件: {}, 原因: {}", path, e.getMessage());
                        }
                    });
        }
    }

    /**
     * 删除工作目录下的指定子目录
     *
     * @param subDirName 子目录名称
     */
    public static void deleteSubDir(String subDirName) {
        Path subDir = getWorkDir().resolve(subDirName);
        deleteDirectoryRecursive(subDir);
    }

    /**
     * 复制文件到目标目录
     *
     * @param source     源文件路径
     * @param targetDir  目标目录路径
     * @param targetName 目标文件名（可选，为null则使用原文件名）
     * @return 目标文件路径
     */
    public static Path copyFile(Path source, Path targetDir, String targetName) {
        try {
            Files.createDirectories(targetDir);
            String fileName = targetName != null ? targetName : source.getFileName().toString();
            Path targetFile = targetDir.resolve(fileName);
            Files.copy(source, targetFile, StandardCopyOption.REPLACE_EXISTING);
            log.info("复制文件: {} -> {}", source, targetFile);
            return targetFile;
        } catch (IOException e) {
            log.error("复制文件失败: {}, 原因: {}", source, e.getMessage());
            throw new RuntimeException("无法复制文件", e);
        }
    }

    /**
     * 检查目录是否存在且非空
     *
     * @param directory 目录路径
     * @return true 表示存在且非空
     */
    public static boolean isDirectoryNotEmpty(Path directory) {
        if (!Files.exists(directory) || !Files.isDirectory(directory)) {
            return false;
        }
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.findFirst().isPresent();
        } catch (IOException e) {
            log.warn("检查目录失败: {}, 原因: {}", directory, e.getMessage());
            return false;
        }
    }

    /**
     * 复制目录及其内容
     *
     * @param source 源目录
     * @param target 目标目录
     */
    public static void copyDirectory(Path source, Path target) {
        if (!Files.exists(source)) {
            log.warn("源目录不存在，跳过复制: {}", source);
            return;
        }

        try {
            Files.walk(source).forEach(sourcePath -> {
                try {
                    Path targetPath = target.resolve(source.relativize(sourcePath));
                    if (Files.isDirectory(sourcePath)) {
                        Files.createDirectories(targetPath);
                    } else {
                        Files.createDirectories(targetPath.getParent());
                        Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    log.error("复制文件失败: {} -> {}", sourcePath, e.getMessage());
                }
            });
            log.info("目录复制成功: {} -> {}", source, target);
        } catch (IOException e) {
            log.error("遍历目录失败: {}, 原因: {}", source, e.getMessage());
            throw new RuntimeException("复制目录失败", e);
        }
    }

    /**
     * 删除指定目录
     *
     * @param path 目录路径
     */
    public static void deleteDirectory(Path path) {
        if (!Files.exists(path)) {
            return;
        }

        if (!Files.isDirectory(path)) {
            try {
                File file = new File(path.toUri());
                //noinspection ResultOfMethodCallIgnored
                file.setWritable(true);
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            } catch (Exception e) {
                log.warn("删除文件失败: {}", e.getMessage());
            }
            return;
        }

        log.info("删除目录: {}", path);
        try (Stream<Path> list = Files.list(path)) {
            list.forEach(sub -> {
                try {
                    deleteDirectory(sub);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            Files.delete(path);
            log.info("删除目录成功");
        } catch (Exception e) {
            log.warn("删除目录失败: {}", e.getMessage());
        }
    }

}