package org.u80dc.jsqlint.core.input;

import org.u80dc.jsqlint.core.resource.PathResourceSource;
import org.u80dc.jsqlint.core.spi.AnalysisInputResolver;
import org.u80dc.jsqlint.core.spi.PreparedInput;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.ClassFileBasedAnalysisInputLocation;
import org.objectweb.asm.ClassReader;
import sootup.core.types.ClassType;
import sootup.core.views.View;
import sootup.java.core.JavaSootClassSource;

import java.nio.file.*;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * core 自带的字节码输入支持，不依赖任何编译器扩展。
 *
 * @author u80dc
 */
public final class BytecodeInputResolver implements AnalysisInputResolver {
    @Override
    public boolean supports(Path source) throws Exception {
        if (Files.isRegularFile(source)) {
            String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
            return name.endsWith(".jar") || name.endsWith(".class");
        }
        if (!Files.isDirectory(source)) return false;
        // 构建项目交由可选编译扩展处理，避免将项目目录误当成已有字节码根目录。
        for (String marker : List.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"))
            if (Files.exists(source.resolve(marker))) return false;
        boolean classes = false;
        try (var files = Files.walk(source)) {
            var iterator = files.filter(Files::isRegularFile).iterator();
            while (iterator.hasNext()) {
                String name = iterator.next().getFileName().toString();
                if (name.endsWith(".java")) return false; // 存在源码时不静默使用可能过期的编译结果。
                if (name.endsWith(".class")) classes = true;
            }
        }
        return classes;
    }

    @Override
    public PreparedInput resolve(Path source, Runnable checkpoint) throws Exception {
        checkpoint.run();
        var location = source.toString().toLowerCase(Locale.ROOT).endsWith(".class")
                ? singleClass(source)
                : new JavaClassPathAnalysisInputLocation(source.toString(), SourceType.Application);
        return new PreparedInput(List.of(location), List.of(new PathResourceSource(source)));
    }

    /** 从 class 内容读取真实类名，不要求单文件输入所在目录与包路径一致。 */
    private static ClassFileBasedAnalysisInputLocation singleClass(Path source) throws Exception {
        String name;
        try (var stream = Files.newInputStream(source)) {
            name = new ClassReader(stream).getClassName().replace('/', '.');
        }
        String packageName = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : "";
        return new ClassFileBasedAnalysisInputLocation(source, packageName, SourceType.Application) {
            @Override
            protected String fromPath(Path base, Path path) { return name; }

            @Override
            public Optional<JavaSootClassSource> getClassSource(ClassType type, View view) {
                // 单文件输入只响应自身类名，防止同包中的其他类被错误解析为当前文件。
                return type.toString().equals(name) ? super.getClassSource(type, view) : Optional.empty();
            }
        };
    }
}
