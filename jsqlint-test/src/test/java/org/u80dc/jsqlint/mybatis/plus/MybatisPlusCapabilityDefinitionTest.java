package org.u80dc.jsqlint.mybatis.plus;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.mybatis.MybatisAnalysisModule;
import org.u80dc.jsqlint.mybatis.MybatisOptions;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.DefaultRuntimeAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** 控制依赖中可见的能力元数据，检验不相关能力缺失不会阻塞已有SQL规则；不作为旧版框架oracle。 */
class MybatisPlusCapabilityDefinitionTest {
    private static final String TABLE_INFO = "com/baomidou/mybatisplus/core/metadata/TableInfo";
    @TempDir Path temporary;

    @Test void unknownAutoMetadataDoesNotBlockInputNoneOrNoPrimaryKeyInsert() throws Exception {
        Path classes = compile();
        Path core = coreWithOpaqueCapabilities(true, false);
        var input = analyze(classes, core, "InputMapper", MybatisPlusOptions.defaults());
        assertSuccessfulInsert(input, Map.of("id", "one", "name", "Alice", "deleted", 0),
                "INSERT INTO input_users(id,name,deleted) VALUES(?,?,?)", List.of(new SqlValue.Known("one"), new SqlValue.Known("Alice"), new SqlValue.Known(0)));
        assertEquals("unknown", input.definitions().getFirst().definition().orElseThrow().metadata().get("conditionalAutoIdInsert"));

        var noneParameters = new HashMap<String,Object>();
        noneParameters.put("id", null);noneParameters.put("name", "Alice");
        assertSuccessfulInsert(analyze(classes, core, "NoneMapper", MybatisPlusOptions.defaults().withIdType("NONE")), noneParameters,
                "INSERT INTO none_users(id,name) VALUES(?,?)", List.of(new SqlValue.Known(null), new SqlValue.Known("Alice")));
        assertSuccessfulInsert(analyze(classes, core, "NoKeyMapper", MybatisPlusOptions.defaults()), Map.of("name", "Alice", "deleted", 0),
                "INSERT INTO no_key_users(name,deleted) VALUES(?,?)", List.of(new SqlValue.Known("Alice"), new SqlValue.Known(0)));
    }

    @Test void autoInsertStillRequiresItsOwnTargetCapability() throws Exception {
        var result = analyze(compile(), coreWithOpaqueCapabilities(true, false), "AutoMapper", MybatisPlusOptions.defaults());
        assertEquals(AnalysisResult.Status.PARTIAL, result.status(), result.diagnostics().toString());
        var definition = result.definitions().getFirst().definition().orElseThrow();
        assertTrue(definition.issues().contains("MyBatis-Plus AUTO ID insert capabilities are unavailable in target framework"), definition.issues().toString());
        assertTrue(result.samples().stream().allMatch(sample -> sample.sql().isEmpty()));
    }

    @Test void insertDoesNotNeedLogicalDeleteSqlCapabilities() throws Exception {
        var result = analyze(compile(), coreWithOpaqueCapabilities(false, true), "NoKeyMapper", MybatisPlusOptions.defaults());
        assertSuccessfulInsert(result, Map.of("name", "Alice", "deleted", 0),
                "INSERT INTO no_key_users(name,deleted) VALUES(?,?)", List.of(new SqlValue.Known("Alice"), new SqlValue.Known(0)));
        var metadata = result.definitions().getFirst().definition().orElseThrow().metadata();
        assertEquals("deleted", metadata.get("logicDeleteColumn"));
        assertFalse(metadata.containsKey("defaultLogicDelete"));
    }

    private static void assertSuccessfulInsert(AnalysisResult result, Map<String,?> parameters, String sql, List<SqlValue.Known> values) {
        assertEquals(AnalysisResult.Status.SUCCESS, result.status(), result.diagnostics().toString());
        assertEquals(1, result.definitions().size());
        var definition = result.definitions().getFirst();
        assertTrue(definition.definition().orElseThrow().complete(), definition.definition().orElseThrow().issues().toString());
        var sample = definition.sample(parameters);
        assertEquals(normalize(sql), normalize(sample.sql().orElseThrow()), sample.issues().toString());
        assertEquals(values, sample.bindings().stream().map(SqlSample.Binding::value).toList());
    }

    private Path compile() throws Exception {
        Path source = Files.writeString(temporary.resolve("Fixture.java"), """
                package sample;
                import com.baomidou.mybatisplus.core.mapper.BaseMapper;
                import com.baomidou.mybatisplus.annotation.*;
                @TableName("input_users") class InputUser {
                    @TableId(type=IdType.INPUT) String id; String name; @TableLogic Integer deleted;
                }
                @TableName("none_users") class NoneUser { @TableId Long id; String name; }
                @TableName("auto_users") class AutoUser { @TableId(type=IdType.AUTO) Long id; String name; }
                @TableName("no_key_users") class NoKeyUser { String name; @TableLogic Integer deleted; }
                interface InputMapper extends BaseMapper<InputUser> {}
                interface NoneMapper extends BaseMapper<NoneUser> {}
                interface AutoMapper extends BaseMapper<AutoUser> {}
                interface NoKeyMapper extends BaseMapper<NoKeyUser> {}
                public class Fixture {}
                """);
        Path classes = Files.createDirectories(temporary.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-parameters",
                "-classpath", System.getProperty("java.class.path"), "-d", classes.toString(), source.toString()));
        return classes;
    }

    private Path coreWithOpaqueCapabilities(boolean auto, boolean logical) throws Exception {
        Path runtime = Path.of(BaseMapper.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path target = temporary.resolve("core-" + auto + "-" + logical + ".jar");
        try (var source = new ZipFile(runtime.toFile());
             var manifestInput = source.getInputStream(source.getEntry("META-INF/MANIFEST.MF"));
             var output = new JarOutputStream(Files.newOutputStream(target), new Manifest(manifestInput))) {
            for (var entry : source.stream().toList()) {
                if (entry.isDirectory() || entry.getName().equalsIgnoreCase("META-INF/MANIFEST.MF")) continue;
                if (logical && entry.getName().equals("com/baomidou/mybatisplus/core/injector/methods/DeleteById.class")) continue;
                byte[] bytes;
                try (var input = source.getInputStream(entry)) { bytes = input.readAllBytes(); }
                if (auto && entry.getName().endsWith(".class")) bytes = opaqueAutoMethod(bytes);
                output.putNextEntry(new JarEntry(entry.getName()));output.write(bytes);output.closeEntry();
            }
        }
        return target;
    }

    private static byte[] opaqueAutoMethod(byte[] source) {
        var reader = new ClassReader(source);var writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            private String owner;
            @Override public void visit(int version, int access, String name, String signature, String parent, String[] interfaces) {
                owner = name;super.visit(version, access, name, signature, parent, interfaces);
            }
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                String renamed = owner.equals(TABLE_INFO) && name.equals("getKeyInsertSqlProperty") ? "opaqueKeyInsertSqlProperty" : name;
                var method = super.visitMethod(access, renamed, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, method) {
                    @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                        super.visitMethodInsn(opcode, owner, owner.equals(TABLE_INFO) && name.equals("getKeyInsertSqlProperty")
                                ? "opaqueKeyInsertSqlProperty" : name, descriptor, isInterface);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static AnalysisResult analyze(Path classes, Path library, String mapper, MybatisPlusOptions options) throws Exception {
        try (var code = new JavaClassPathAnalysisInputLocation(classes.toString(), SourceType.Application);
             var dependency = new JavaClassPathAnalysisInputLocation(library.toString(), SourceType.Library);
             var runtime = new DefaultRuntimeAnalysisInputLocation()) {
            var analyzer = new Analyzer(List.of(new MybatisAnalysisModule(MybatisOptions.defaults(), List.of(new MybatisPlusExtension(options)))), List.of());
            return analyzer.analyze(new AnalysisRequest(List.of(code, dependency, runtime), List.of()),
                    "<sample." + mapper + ": int insert(java.lang.Object)>");
        }
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").replaceAll("\\s*([(),=])\\s*", "$1").strip();
    }
}
