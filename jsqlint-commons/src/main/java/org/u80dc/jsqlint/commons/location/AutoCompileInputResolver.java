package org.u80dc.jsqlint.commons.location;

import org.u80dc.jsqlint.core.resource.PathResourceSource;
import org.u80dc.jsqlint.core.spi.AnalysisInputResolver;
import org.u80dc.jsqlint.core.spi.PreparedInput;

import java.nio.file.*;
import java.util.List;
import java.util.Objects;

/**
 * 为 core 的 Path 入口按需接入源码及项目编译。
 *
 * @author u80dc
 */
public final class AutoCompileInputResolver implements AnalysisInputResolver {
    private final InputLocationOptions options;

    public AutoCompileInputResolver() { this(InputLocationOptions.defaults()); }

    public AutoCompileInputResolver(InputLocationOptions options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    /** 编译扩展优先于 core 的字节码解析器，避免含源码的项目使用旧产物。 */
    @Override
    public int priority() { return 100; }

    @Override
    public boolean supports(Path source) throws Exception {
        if (Files.isRegularFile(source)) return source.toString().endsWith(".java");
        if (!Files.isDirectory(source)) return false;
        for (String marker : List.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"))
            if (Files.exists(source.resolve(marker))) return true;
        return !JavaSourceFiles.collect(source).isEmpty();
    }

    @Override
    public PreparedInput resolve(Path source, Runnable checkpoint) {
        checkpoint.run();
        // 构造输入位置时完成必要编译，后续类和方法读取才按需转换为 Jimple。
        var location = new AutoCompileAnalysisInputLocation(source, options);
        // 创建后立即转交所有权；统一入口再次检查取消，并负责关闭已经创建的输入。
        Path root = Files.isDirectory(source) ? source : source.getParent();
        return new PreparedInput(List.of(location), List.of(new PathResourceSource(root, path -> {
            // 这里只限定默认资源范围，具体文件类型由框架模块判断；特殊目录可显式传入。
            Path relative = root.relativize(path);
            for (Path part : relative)
                if (List.of(".git", ".gradle", ".idea", "target", "build").contains(part.toString())) return false;
            for (int i = 0; i + 1 < relative.getNameCount(); i++)
                if (relative.getName(i).toString().equals("src") && relative.getName(i + 1).toString().equals("test"))
                    return false;
            return true;
        })));
    }
}
