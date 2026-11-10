package org.u80dc.jsqlint.core.bytecode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.views.JavaView;

import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

import static org.junit.jupiter.api.Assertions.*;

/** class 与 JAR 读取完整 descriptor，保留可见/不可见注解且不混淆重载。 @author u80dc */
class MethodParameterMetadataTest {
    @TempDir Path temporary;
    @Test void readsNamesAnnotationsAndOverloadsFromClassAndJar() throws Exception {
        Path source = Files.writeString(temporary.resolve("Sample.java"), """
                package metadata;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
                @interface Hidden { String value(); Class<?> type(); }
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                @interface Visible { String value(); }
                public interface Sample {
                    void action(@Hidden(value="number",type=Integer.class) @Visible("integer") int number);
                    void action(@Hidden(value="text",type=String.class) String text);
                }
                """);
        Path classes = Files.createDirectory(temporary.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-parameters", "-d", classes.toString(), source.toString()));
        Path jar = temporary.resolve("sample.jar");
        try (var archive = new JarOutputStream(Files.newOutputStream(jar)); var entries = Files.walk(classes)) {
            for (var file : entries.filter(Files::isRegularFile).toList()) {
                archive.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, archive); archive.closeEntry();
            }
        }
        for (Path input : List.of(classes, jar)) try (var location = new JavaClassPathAnalysisInputLocation(input.toString(), SourceType.Application)) {
            var view = new JavaView(List.of(location));
            var clazz = view.getClass(view.getIdentifierFactory().getClassType("metadata.Sample")).orElseThrow();
            for (var method : clazz.getMethods()) {
                var metadata = MethodParameterMetadata.read(method, view).parameters().getFirst();
                boolean number = method.getParameterType(0).toString().equals("int");
                assertEquals(Optional.of(number ? "number" : "text"), metadata.name());
                var hidden = metadata.annotations().stream().filter(annotation -> annotation.type().equals("metadata.Hidden")).findFirst().orElseThrow();
                assertEquals(number ? "number" : "text", hidden.values().get("value"));
                assertEquals(number ? "java.lang.Integer" : "java.lang.String", hidden.values().get("type"));
                assertEquals(number ? 2 : 1, metadata.annotations().size());
            }
        }
        try (var input = Files.newInputStream(classes.resolve("metadata/Sample.class"))) {
            assertThrows(java.io.IOException.class, () -> MethodParameterMetadata.read(input, "action", "(J)V"));
        }
    }
}
