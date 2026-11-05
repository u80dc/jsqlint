package org.u80dc.jsqlint.commons.compiler;

import org.u80dc.jsqlint.commons.location.AnalysisInputException;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.interceptor.BodyInterceptor;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.OTFCompileAnalysisInputLocation;

import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;

/**
 * 普通 Java 源码编译：默认使用 OTF，调用方指定输出目录时使用 JDK 编译器。
 * 编译 classpath 和参数与 OTF 默认行为一致，额外分析依赖不参与编译。
 *
 * @author u80dc
 */
public final class JavaSourceCompiler {
    private JavaSourceCompiler() {}

    /** OTF 不开放输出目录配置，通过 JDK 编译器写入已准备好的指定目录。 */
    public static void compile(List<Path> sources, Path output) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new AnalysisInputException("Source compilation requires a JDK with JavaCompiler");
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            manager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));
            var diagnostics = new StringWriter();
            var units = manager.getJavaFileObjectsFromPaths(sources);
            if (!compiler.getTask(diagnostics, manager, null, null, null, units).call()) {
                throw new AnalysisInputException("Java compilation failed for " + sources + "\n" + diagnostics);
            }
        }
    }

    /** 使用 OTF 的默认输出目录，并保留目标代码类型及方法体拦截器。 */
    public static AnalysisInputLocation compileOnTheFly(List<Path> paths, SourceType sourceType,
                                                        List<BodyInterceptor> interceptors) {
        if (ToolProvider.getSystemJavaCompiler() == null) {
            throw new AnalysisInputException("OTF compilation requires a JDK with JavaCompiler");
        }
        try {
            return new OTFCompileAnalysisInputLocation(paths, sourceType, interceptors);
        } catch (RuntimeException e) {
            throw new AnalysisInputException("OTF compilation failed for " + paths + "\n" + e.getMessage(), e);
        }
    }
}
