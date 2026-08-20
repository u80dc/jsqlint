package cn.jnu.zhangcs.jsqlint.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 构造用于 Maven 编译测试的最小项目。
 *
 * <p>项目不携带任何依赖，编译插件版本固定为本机缓存中已有的版本，
 * 配合本地缓存仓库可在离线环境下完成真实编译。</p>
 */
public final class TinyMavenProject {

    private TinyMavenProject() {
    }

    /**
     * 返回本机默认的 Maven 本地缓存仓库路径。
     */
    public static Path cachedLocalRepo() {
        return Path.of(System.getProperty("user.home"), ".m2", "repository");
    }

    /**
     * 创建可正常编译的最小 Maven 项目。
     */
    public static Path create(Path projectDir) throws IOException {
        createSkeleton(projectDir);
        Files.writeString(projectDir.resolve("src/main/java/demo/Tiny.java"), """
                package demo;

                public class Tiny {
                    public static String greet() {
                        return "tiny";
                    }
                }
                """);
        return projectDir;
    }

    /**
     * 创建源码存在语法错误、必然编译失败的最小 Maven 项目。
     */
    public static Path createBroken(Path projectDir) throws IOException {
        createSkeleton(projectDir);
        Files.writeString(projectDir.resolve("src/main/java/demo/Tiny.java"), """
                package demo;

                public class Tiny {
                    public static String greet() {
                        return "missing semicolon"
                    }
                }
                """);
        return projectDir;
    }

    private static void createSkeleton(Path projectDir) throws IOException {
        Files.createDirectories(projectDir.resolve("src/main/java/demo"));
        Files.writeString(projectDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>

                    <groupId>demo</groupId>
                    <artifactId>tiny-project</artifactId>
                    <version>1.0.0</version>
                    <packaging>jar</packaging>

                    <properties>
                        <java.version>21</java.version>
                        <maven.compiler.release>21</maven.compiler.release>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    </properties>

                    <build>
                        <pluginManagement>
                            <plugins>
                                <plugin>
                                    <groupId>org.apache.maven.plugins</groupId>
                                    <artifactId>maven-compiler-plugin</artifactId>
                                    <version>3.15.0</version>
                                </plugin>
                            </plugins>
                        </pluginManagement>
                    </build>
                </project>
                """);
    }
}
