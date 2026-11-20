package org.u80dc.jsqlint.mybatis.plus;

import sootup.java.core.views.JavaView;
import java.io.IOException;
import java.nio.file.*;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;

/** 读取目标框架发行元数据，不使用分析器运行时的 Plus 版本代替目标版本。 */
final class PlusFrameworkVersion {
    private PlusFrameworkVersion() {}
    static String read(JavaView view) {
        return read(view,PlusEntityMetadataReader.BASE_MAPPER);
    }
    static String read(JavaView view,String anchor) {
        try {
            Path source = view.getClass(view.getIdentifierFactory().getClassType(anchor))
                    .orElseThrow().getClassSource().getSourcePath();
            if (source.toString().endsWith(".jar")) try (var zip = new ZipFile(source.toFile())) {
                var entry = zip.getEntry("META-INF/MANIFEST.MF");
                if (entry == null) return "unknown";
                try (var stream = zip.getInputStream(entry)) { return version(new Manifest(stream)); }
            }
            if (!source.getFileSystem().equals(FileSystems.getDefault())) {
                try (var stream = Files.newInputStream(source.getFileSystem().getPath("/META-INF/MANIFEST.MF"))) {
                    return version(new Manifest(stream));
                }
            }
        } catch (IOException | RuntimeException ignored) { /* 缺失版本元数据由定义 issues 报告。 */ }
        return "unknown";
    }
    private static String version(Manifest manifest) {
        String version = manifest.getMainAttributes().getValue("Implementation-Version");
        return version == null ? "unknown" : version;
    }
}
