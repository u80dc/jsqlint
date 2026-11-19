package org.u80dc.jsqlint.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.core.parameter.*;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.parameter.JointSqlInferenceResult.Status.*;

class JdbcNormalCompletionTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"abort();", "wrapper();"})
    void applicationCallsWithoutNormalReturnCannotReachSql(String call) throws Exception {
        assertNoSatisfiableSql(analyze("""
                static void abort() { throw new IllegalStateException(); }
                static void wrapper() { abort(); }
                static void run(java.sql.Connection c) throws Exception {
                    var p=c.prepareStatement("SELECT ?");p.setInt(1,7);%s p.executeQuery();
                }
                """.formatted(call)));
    }

    @Test void possibleReturningVirtualTargetIsPreserved() throws Exception {
        var variants = analyze("""
                interface Helper { void apply(); }
                static class Halt implements Helper { public void apply() { throw new IllegalStateException(); } }
                static class Continue implements Helper { public void apply() {} }
                static void run(java.sql.Connection c, Helper helper) throws Exception {
                    var p=c.prepareStatement("SELECT ?");p.setInt(1,7);helper.apply();p.executeQuery();
                }
                """);
        assertTrue(variants.stream().flatMap(sql -> sql.jointInference().orElseThrow().combinations().stream())
                .anyMatch(combination -> combination.status() == SATISFIABLE));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "int[] a=new int[0];p.setInt(1,a[0]);",
            "int[] a=new int[1];p.setInt(1,a[2]);",
            "int[] a=new int[1];a[2]=7;a[0]=8;p.setInt(1,a[0]);",
            "int[] a=new int[1];write(a);p.setInt(1,a[0]);",
            "int[] a=new int[-1];p.setInt(1,7);",
            "int[][] a=new int[1][-1];p.setInt(1,7);",
            "int[] a=new int[1];int ignored=a[2];p.setInt(1,7);"
    })
    void failedArrayAllocationReadOrWriteCannotReachSql(String statements) throws Exception {
        assertNoSatisfiableSql(analyze("""
                static void write(int[] values) { values[2]=7; }
                static void run(java.sql.Connection c) throws Exception {
                    var p=c.prepareStatement("SELECT ?");%s p.executeQuery();
                }
                """.formatted(statements)));
    }

    @Test void boundedLocalReadReplaysTheAllocationAndIndexTogether() throws Exception {
        var variants = analyze("""
                static void run(java.sql.Connection c,int length,int index) throws Exception {
                    int[] values=new int[length];
                    var p=c.prepareStatement("SELECT ?");p.setInt(1,values[index]);p.executeQuery();
                }
                """);
        assertFalse(variants.isEmpty());
        ValueTrace trace = variants.getFirst().bindings().getFirst().value();
        List<ValueTrace.Variable> inputs = inputs(trace);
        ValueTrace.Variable length = inputs.stream().filter(value -> value.expression().contains("@parameter1")).findFirst().orElseThrow();
        ValueTrace.Variable index = inputs.stream().filter(value -> value.expression().contains("@parameter2")).findFirst().orElseThrow();
        var valid = replay(trace, Map.of(length, new SqlValue.Known(2), index, new SqlValue.Known(1)));
        assertTrue(valid.possibleValues().stream().anyMatch(value -> value.value().equals(new SqlValue.Known(0))));
        for (var model : List.of(Map.of(length, new SqlValue.Known(0), index, new SqlValue.Known(0)),
                Map.of(length, new SqlValue.Known(-1), index, new SqlValue.Known(0)),
                Map.of(length, new SqlValue.Known(1), index, new SqlValue.Known(2)))) {
            var invalid = replay(trace, model);
            assertTrue(invalid.possibleValues().stream().noneMatch(value -> value.value() instanceof SqlValue.Known), invalid.toString());
        }
    }

    @Test void knownValidDefaultArrayElementRemainsCompleteAndSatisfiable() throws Exception {
        var variants = analyze("""
                static void run(java.sql.Connection c) throws Exception {
                    int[] a=new int[1];var p=c.prepareStatement("SELECT ?");p.setInt(1,a[0]);p.executeQuery();
                }
                """);
        assertFalse(variants.isEmpty());
        var joint = variants.getFirst().jointInference().orElseThrow();
        assertEquals(SATISFIABLE, joint.status(), joint.issues().toString());
        assertEquals(new SqlValue.Known(0), joint.combinations().getFirst().bindings().getFirst().value().value());
    }

    private static ParameterValueInferenceResult replay(ValueTrace trace, Map<ValueTrace.Variable, ? extends SqlValue> model) {
        return new ParameterValueInferencer((type, name) -> new SqlValue.Known(0), () -> {}, new HashMap<>(model)).infer(trace);
    }
    private static List<ValueTrace.Variable> inputs(ValueTrace trace) {
        Set<ValueTrace.Variable> result = new LinkedHashSet<>();
        Deque<ValueTrace.Node> pending = new ArrayDeque<>(); pending.add(trace.root());
        while (!pending.isEmpty()) {
            var node = pending.removeFirst();
            if (node.kind() == ValueTrace.Kind.ENTRY || node.kind() == ValueTrace.Kind.NO_CALLER) result.add(node.variable());
            pending.addAll(node.inputs());
        }
        return List.copyOf(result);
    }
    private static void assertNoSatisfiableSql(List<RawSql.Variant> variants) {
        assertTrue(variants.stream().flatMap(sql -> sql.jointInference().orElseThrow().combinations().stream())
                .noneMatch(combination -> combination.status() == SATISFIABLE));
    }
    private List<RawSql.Variant> analyze(String methods) throws Exception {
        Path root = Files.createTempDirectory(temporary, "completion-");
        Path source = Files.writeString(root.resolve("Sample.java"), "public class Sample {" + methods + "}");
        Path classes = Files.createDirectory(root.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-g", "-d", classes.toString(), source.toString()));
        var result = new Analyzer(AnalyzerConfig.defaults().withMaxTraceDepth(64), List.of(new JdbcAnalysisModule()), List.of()).analyze(classes);
        assertNotEquals(AnalysisResult.Status.FAILED, result.status(), result.diagnostics().toString());
        return result.extractions().stream().filter(extraction -> extraction.callSite().target().getName().equals("executeQuery"))
                .flatMap(extraction -> extraction.statements().stream()).flatMap(statement -> statement.rawSql().variants().stream()).toList();
    }
}
