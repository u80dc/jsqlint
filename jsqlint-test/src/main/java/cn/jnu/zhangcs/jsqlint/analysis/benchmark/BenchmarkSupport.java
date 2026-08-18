package cn.jnu.zhangcs.jsqlint.analysis.benchmark;

import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.model.SourceType;
import sootup.core.signatures.MethodSignature;
import sootup.java.bytecode.frontend.inputlocation.JrtFileSystemAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.PathBasedAnalysisInputLocation;
import sootup.java.core.JavaSootUtils;
import sootup.java.core.views.JavaView;
import sootup.java.core.views.MutableJavaView;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class BenchmarkSupport {

    private BenchmarkSupport() {
    }

    static Path targetClasses() {
        Path moduleClasses = Path.of("jsqlint-test", "target", "classes");
        if (Files.isDirectory(moduleClasses)) {
            return moduleClasses.toAbsolutePath().normalize();
        }
        return Path.of("target", "classes").toAbsolutePath().normalize();
    }

    static JavaView javaView() {
        List<AnalysisInputLocation> locations = new ArrayList<>();
        locations.add(new JrtFileSystemAnalysisInputLocation());
        locations.add(PathBasedAnalysisInputLocation.create(targetClasses(), SourceType.Application));
        return new MutableJavaView(locations);
    }

    static MethodSignature method(String className, String methodName, String returnType, String... parameterTypes) {
        return JavaSootUtils.getMethodSignature(className, methodName, returnType, parameterTypes);
    }

}
