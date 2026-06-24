package cn.jnu.zhangcs.jsqlint.analysis.util;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 文件工具类
 *
 * @author zhangcs
 */
@Slf4j
public class FileUtils {

    /**
     * 解压 JAR 文件到指定目录
     *
     * @param jarPath   JAR文件
     * @param directory 解压目标路径
     */
    public static void extractJar(Path jarPath, Path directory) throws IOException {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            jarFile.stream()
                    .forEach(entry -> {
                        try {
                            Path targetPath = directory.resolve(entry.getName());

                            // 如果是目录，直接创建
                            if (entry.isDirectory()) {
                                Files.createDirectories(targetPath);
                            } else {
                                // 如果是文件，先确保父目录存在，然后复制流
                                Files.createDirectories(targetPath.getParent());
                                Files.copy(jarFile.getInputStream(entry), targetPath, StandardCopyOption.REPLACE_EXISTING);
                            }
                        } catch (IOException e) {
                            log.warn(e.getMessage());
                        }
                    });
        }
    }

    /**
     * 递归删除目录及其内容
     *
     * @param directory 待删除的路径
     */
    public static void deleteDirectoryRecursive(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }

        try (Stream<Path> stream = Files.walk(directory)) {
            // 确保先访问深层文件，再访问目录本身
            stream.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (Exception e) {
                            log.warn("删除`{}`文件失败: {}", path.toAbsolutePath(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn(e.getMessage());
        }
    }

    /**
     * 在指定路径下查找JAR文件
     *
     * @param path 路径
     * @return 返回JAR文件具体路径集合
     */
    @NonNull
    public static List<Path> findJars(Path path) {
        return findFiles(path, ".jar");
    }

    /**
     * 在指定路径下查找Class文件
     *
     * @param path 路径
     * @return 返回JAR文件具体路径集合
     */
    @NonNull
    public static List<Path> findClasses(Path path) {
        return findFiles(path, ".class");
    }

    /**
     * 在指定路径下查找XML文件，包含多层路径时遍历查找
     *
     * @param path 路径
     * @return 返回所有XML文件路径集合
     */
    @NonNull
    public static List<Path> findXmlFiles(Path path) {
        return findFiles(path, ".xml");
    }

    /**
     * 在指定路径下查找指定类型后缀的文件，包含多层路径时遍历查找
     *
     * @param path   路径
     * @param suffix 指定文件后缀
     * @return 返回所有XML文件路径集合
     */
    public static List<Path> findFiles(Path path, String suffix) {
        if (!suffix.startsWith(".")) {
            suffix = "." + suffix;
        }
        final String temp = suffix;
        try (Stream<Path> walk = Files.walk(path)) {
            return walk
                    // 确保是文件而不是目录
                    .filter(Files::isRegularFile)
                    // 过滤 .jar 后缀
                    .filter(p -> p.toString().endsWith(temp))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("扫描目录出错: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

}
