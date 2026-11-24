package org.u80dc.jsqlint.mybatis.plus;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.ReflectionKit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.views.JavaView;

import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlusEntityFieldOrderTest {
    @TempDir Path temporary;

    @Test void targetReflectionOrderAndShadowingMatchRuntimeBeforeDatabaseExclusions() throws Exception {
        Path source = Files.writeString(temporary.resolve("Fixture.java"), """
                package order;
                import com.baomidou.mybatisplus.annotation.*;
                import com.baomidou.mybatisplus.core.mapper.BaseMapper;
                class Parent { @TableId(type=IdType.INPUT) Long id; String parentValue; String ignored; }
                @TableName("ordered_users") class User extends Parent {
                    static String padding;
                    Long nodeId;
                    @TableField("display_name") String name;
                    String value;
                    @TableField(select=false) String secret;
                    @TableField(exist=false) String excluded;
                    transient String ignored;
                }
                interface UserMapper extends BaseMapper<User> {}
                public class Fixture {}
                """);
        Path classes = Files.createDirectories(temporary.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-parameters",
                "-classpath", System.getProperty("java.class.path"), "-d", classes.toString(), source.toString()));
        String core = Path.of(BaseMapper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String annotation = Path.of(TableField.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        try (var code = new JavaClassPathAnalysisInputLocation(classes.toString(), SourceType.Application);
             var library = new JavaClassPathAnalysisInputLocation(core, SourceType.Library);
             var annotations = new JavaClassPathAnalysisInputLocation(annotation, SourceType.Library);
             var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            var view = new JavaView(List.of(code, library, annotations));
            var entity = new PlusEntityMetadataReader(view, MybatisPlusOptions.defaults()).resolve("order.UserMapper").orElseThrow();
            assertTrue(entity.issues().isEmpty(), entity.issues().toString());
            var expected = ReflectionKit.getFieldList(loader.loadClass("order.User")).stream()
                    .filter(field -> field.getAnnotation(TableField.class) == null || field.getAnnotation(TableField.class).exist())
                    .map(java.lang.reflect.Field::getName).toList();
            assertEquals(expected, entity.fields().stream().map(EntityMapping.Field::property).toList());
            assertFalse(expected.contains("ignored"));
            assertTrue(expected.contains("parentValue"));
            assertFalse(expected.contains("excluded"));
        }
    }

    @Test void instanceSyntheticFieldsAreReportedUnlessExcludedByTableMapping() throws Exception {
        Path source = Files.writeString(temporary.resolve("Fixture.java"), """
                package order;
                import com.baomidou.mybatisplus.annotation.*;
                import com.baomidou.mybatisplus.core.mapper.BaseMapper;
                public class Fixture {
                    @TableName("nested_users") class User {
                        @TableId(type=IdType.INPUT) Long id; String value;
                        Object outer() { return Fixture.this; }
                    }
                    @TableName(value="excluded_users", excludeProperty="this$0")
                    class ExcludedUser {
                        @TableId(type=IdType.INPUT) Long id; String value;
                        Object outer() { return Fixture.this; }
                    }
                }
                interface UserMapper extends BaseMapper<Fixture.User> {}
                interface ExcludedMapper extends BaseMapper<Fixture.ExcludedUser> {}
                """);
        Path classes = Files.createDirectories(temporary.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-parameters",
                "-classpath", System.getProperty("java.class.path"), "-d", classes.toString(), source.toString()));
        String core = Path.of(BaseMapper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String annotation = Path.of(TableField.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        try (var code = new JavaClassPathAnalysisInputLocation(classes.toString(), SourceType.Application);
             var library = new JavaClassPathAnalysisInputLocation(core, SourceType.Library);
             var annotations = new JavaClassPathAnalysisInputLocation(annotation, SourceType.Library);
             var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            assertTrue(ReflectionKit.getFieldList(loader.loadClass("order.Fixture$User")).stream()
                    .anyMatch(field -> field.isSynthetic() && field.getName().equals("this$0")));
            var reader = new PlusEntityMetadataReader(new JavaView(List.of(code, library, annotations)), MybatisPlusOptions.defaults());
            var entity = reader.resolve("order.UserMapper").orElseThrow();
            assertTrue(entity.issues().stream().anyMatch(issue -> issue.contains("instance synthetic field") && issue.contains("this$0")),
                    entity.issues().toString());
            var excluded = reader.resolve("order.ExcludedMapper").orElseThrow();
            assertTrue(excluded.issues().isEmpty(), excluded.issues().toString());
            assertEquals(List.of("id", "value"), excluded.fields().stream().map(EntityMapping.Field::property).toList());
        }
    }
}
