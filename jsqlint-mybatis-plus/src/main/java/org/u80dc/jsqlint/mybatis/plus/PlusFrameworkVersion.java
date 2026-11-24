package org.u80dc.jsqlint.mybatis.plus;

import sootup.java.core.views.JavaView;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;

/** 读取目标框架发行元数据，不使用分析器运行时的 Plus 版本代替目标版本。 */
final class PlusFrameworkVersion {
    private PlusFrameworkVersion() {}
    static boolean is3x(String version) {
        return version != null && version.matches("3\\.\\d+(?:\\.\\d+)*(?:[-._+][A-Za-z0-9][A-Za-z0-9._+\\-]*)?");
    }
    static String read(JavaView view) {
        return read(view,PlusEntityMetadataReader.BASE_MAPPER);
    }
    static String read(JavaView view,String anchor) {
        try {
            Path source = view.getClass(view.getIdentifierFactory().getClassType(anchor))
                    .orElseThrow().getClassSource().getSourcePath();
            if (source.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) try (var zip = new ZipFile(source.toFile())) {
                for (String preferred : propertiesEntries(anchor)) {
                    var properties = zip.getEntry(preferred);
                    if (properties != null) try (var stream = zip.getInputStream(properties)) {
                        String version = propertyVersion(stream);
                        if (!version.equals("unknown")) return version;
                    }
                }
                var versions = new LinkedHashSet<String>();
                for (var property : zip.stream().filter(item -> item.getName().startsWith("META-INF/maven/com.baomidou/mybatis-plus-")
                        && item.getName().endsWith("/pom.properties")).toList()) {
                    try (var stream = zip.getInputStream(property)) {
                        String version = propertyVersion(stream);
                        if (!version.equals("unknown")) versions.add(version);
                    }
                }
                if (versions.size() == 1) return versions.iterator().next();
                if (versions.size() > 1) return "unknown";
                var entry = zip.getEntry("META-INF/MANIFEST.MF");
                if (entry != null) try (var stream = zip.getInputStream(entry)) {
                    String version = version(new Manifest(stream));
                    if (!version.equals("unknown")) return version;
                }
                return filenameVersion(source);
            }
            if (!source.getFileSystem().equals(FileSystems.getDefault())) {
                for (String preferred : propertiesEntries(anchor)) {
                    Path properties = source.getFileSystem().getPath("/" + preferred);
                    if (Files.isRegularFile(properties)) try (var stream = Files.newInputStream(properties)) {
                        String version = propertyVersion(stream);
                        if (!version.equals("unknown")) return version;
                    }
                }
                var versions = new LinkedHashSet<String>();
                Path metadata = source.getFileSystem().getPath("/META-INF/maven/com.baomidou");
                if (Files.isDirectory(metadata)) try (var entries = Files.list(metadata)) {
                    for (Path artifact : entries.filter(path -> path.getFileName().toString().startsWith("mybatis-plus-")).toList()) {
                        Path properties = artifact.resolve("pom.properties");
                        if (Files.isRegularFile(properties)) try (var stream = Files.newInputStream(properties)) {
                            String version = propertyVersion(stream);
                            if (!version.equals("unknown")) versions.add(version);
                        }
                    }
                }
                if (versions.size() == 1) return versions.iterator().next();
                if (versions.size() > 1) return "unknown";
                Path manifest = source.getFileSystem().getPath("/META-INF/MANIFEST.MF");
                if (Files.isRegularFile(manifest)) try (var stream = Files.newInputStream(manifest)) {
                    String version = version(new Manifest(stream));
                    if (!version.equals("unknown")) return version;
                }
                var uri = source.toUri();
                if (uri.getScheme().equals("jar")) {
                    String archive = uri.getRawSchemeSpecificPart();
                    int separator = archive.indexOf('!');
                    if (separator >= 0) return filenameVersion(Path.of(java.net.URI.create(archive.substring(0, separator))));
                }
            }
        } catch (IOException | RuntimeException ignored) { /* 缺失版本元数据由定义 issues 报告。 */ }
        return "unknown";
    }
    private static String version(Manifest manifest) {
        String version = manifest.getMainAttributes().getValue("Implementation-Version");
        return version == null || version.isBlank() ? "unknown" : version.strip();
    }
    private static List<String> propertiesEntries(String anchor) {
        List<String> artifacts;
        if (anchor.startsWith("com.baomidou.mybatisplus.core.")) artifacts = List.of("mybatis-plus-core");
        else if (anchor.startsWith("com.baomidou.mybatisplus.spring.")
                || anchor.equals("com.baomidou.mybatisplus.extension.service.IService")
                || anchor.equals("com.baomidou.mybatisplus.extension.service.impl.ServiceImpl"))
            artifacts = List.of("mybatis-plus-spring", "mybatis-plus-extension");
        else if (anchor.equals("com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor")
                || anchor.equals("com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor"))
            artifacts = List.of("mybatis-plus-jsqlparser", "mybatis-plus-jsqlparser-4.9", "mybatis-plus-extension");
        else artifacts = List.of("mybatis-plus-extension");
        return artifacts.stream().map(artifact -> "META-INF/maven/com.baomidou/" + artifact + "/pom.properties").toList();
    }
    private static String propertyVersion(java.io.InputStream stream) throws IOException {
        var properties = new Properties();
        properties.load(stream);
        String version = properties.getProperty("version");
        return version == null || version.isBlank() ? "unknown" : version.strip();
    }
    private static String filenameVersion(Path source) {
        String name = source.getFileName().toString();
        var matcher = java.util.regex.Pattern.compile("mybatis-plus-(?:core|extension|spring|jsqlparser(?:-\\d+\\.\\d+)?)-(3\\..+)\\.jar").matcher(name);
        return matcher.matches() && is3x(matcher.group(1)) ? matcher.group(1) : "unknown";
    }
}
