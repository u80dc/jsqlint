package cn.jnu.zhangcs.jsqlint.analysis.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.Optional;

/**
 * JDK相关工具类
 *
 * @author zhangcs
 */
@Slf4j
public class JdkUtils {

    /**
     * 获取 rt.jar 文件
     *
     * @return 返回rt.jar
     */
    public static Optional<File> getRtJar() {
        Optional<String> rtJarByBootClassPath = getRtJarByBootClassPath();
        if (rtJarByBootClassPath.isPresent()) {
            Path path = Paths.get(rtJarByBootClassPath.get());
            if (Files.exists(path)) {
                return Optional.of(path.toFile());
            }
        }

        Optional<String> rtJarByCoreClassCodeSource = getRtJarByCoreClassCodeSource();
        if (rtJarByCoreClassCodeSource.isPresent()) {
            Path path = Paths.get(rtJarByCoreClassCodeSource.get());
            return Optional.of(path.toFile());
        }
        return Optional.empty();
    }

    /**
     * 从JVM启动类路径获取
     *
     * @return 返回rt.jar文件路径
     */
    private static Optional<String> getRtJarByBootClassPath() {
        try {
            String property = System.getProperty("sun.boot.class.path");
            if (StringUtils.isNoneBlank(property)) {
                return Arrays.stream(property.split(File.pathSeparator))
                        .filter(path -> path.endsWith("rt.jar"))
                        .findFirst();
            }
        } catch (Exception e) {
            log.warn(e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * 通过核心类的 CodeSource 获取（兼容自定义类加载器）
     */
    private static Optional<String> getRtJarByCoreClassCodeSource() {
        try {
            // 获取 java.lang.Object 类（核心类，必然在rt.jar中）
            Class<?> objectClass = Class.forName("java.lang.Object");

            // 获取类的保护域和代码源
            ProtectionDomain domain = objectClass.getProtectionDomain();
            if (domain == null) {
                return Optional.empty();
            }
            CodeSource codeSource = domain.getCodeSource();
            if (codeSource == null) {
                return Optional.empty();
            }

            // 提取代码源路径（rt.jar的绝对路径）
            URL location = codeSource.getLocation();
            if (location == null || !"file".equals(location.getProtocol())) {
                return Optional.empty();
            }

            // 处理URL编码（如路径中的空格、中文）
            String rtJarPath = java.net.URLDecoder.decode(location.getPath(), StandardCharsets.UTF_8);
            return rtJarPath.endsWith("rt.jar") ? Optional.of(rtJarPath) : Optional.empty();
        } catch (Exception e) {
            log.warn(e.getMessage());
            return Optional.empty();
        }
    }

}
