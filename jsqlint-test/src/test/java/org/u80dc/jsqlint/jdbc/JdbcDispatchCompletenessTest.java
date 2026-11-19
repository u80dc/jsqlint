package org.u80dc.jsqlint.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.u80dc.jsqlint.core.*;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.parameter.JointSqlInferenceResult.Status.*;

class JdbcDispatchCompletenessTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"gate.check();", "outer(gate);"})
    void libraryOverrideCanReturnWhenTheApplicationDeclarationAlwaysThrows(String call) throws Exception {
        var result = analyze("""
                public static abstract class Gate { public void check() { throw new IllegalStateException(); } }
                static void outer(Gate gate) { gate.check(); }
                public static void run(java.sql.Connection c, Gate gate) throws Exception {
                    var p=c.prepareStatement("SELECT 1"); %s p.executeQuery();
                }
                """.formatted(call), "public class LibraryGate extends Sample.Gate { public void check() {} }");
        var variants = executions(result);
        assertFalse(variants.isEmpty(), result.diagnostics().toString());
        assertTrue(variants.stream().allMatch(value -> value.jointInference().orElseThrow().status() == UNKNOWN));
        assertTrue(variants.stream().flatMap(value -> value.jointInference().orElseThrow().issues().stream())
                .anyMatch(issue -> issue.toLowerCase(Locale.ROOT).contains("dispatch")));
        assertFalse(executionSamples(result).isEmpty());
        assertTrue(executionSamples(result).stream().allMatch(sample -> sample.feasibility() == SqlSample.Feasibility.UNKNOWN));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "public static abstract class Gate { public final void check() { throw new IllegalStateException(); } }",
            "public static final class Gate { public void check() { throw new IllegalStateException(); } }"
    })
    void finalMethodOrClassStillProvesTheCallCannotReturn(String declaration) throws Exception {
        assertUnreachable(analyze(declaration + """
                public static void run(java.sql.Connection c, Gate gate) throws Exception {
                    var p=c.prepareStatement("SELECT 1"); gate.check(); p.executeQuery();
                }
                """, null));
    }

    @Test void localAllocationGivesAnExactReceiverEvenWhenItsClassCanBeExtended() throws Exception {
        assertUnreachable(analyze("""
                public static class Gate { public void check() { throw new IllegalStateException(); } }
                public static void run(java.sql.Connection c) throws Exception {
                    Gate gate=new Gate(); var p=c.prepareStatement("SELECT 1"); gate.check(); p.executeQuery();
                }
                """, null));
    }

    @Test void staticCallStillProvesTheCallCannotReturn() throws Exception {
        assertUnreachable(analyze("""
                static void abort() { throw new IllegalStateException(); }
                public static void run(java.sql.Connection c) throws Exception {
                    var p=c.prepareStatement("SELECT 1"); abort(); p.executeQuery();
                }
                """, null));
    }

    @Test void knownReturningCandidateStillWitnessesAFeasibleExecution() throws Exception {
        var result = analyze("""
                interface Gate { void check(); }
                public static class Abort implements Gate { public void check() { throw new IllegalStateException(); } }
                public static class Continue implements Gate { public void check() {} }
                public static void run(java.sql.Connection c, Gate gate) throws Exception {
                    var p=c.prepareStatement("SELECT 1"); gate.check(); p.executeQuery();
                }
                """, null);
        assertTrue(executions(result).stream().flatMap(value -> value.jointInference().orElseThrow().combinations().stream())
                .anyMatch(value -> value.status() == SATISFIABLE));
    }

    private static void assertUnreachable(AnalysisResult result) {
        assertTrue(executions(result).stream().flatMap(value -> value.jointInference().orElseThrow().combinations().stream())
                .noneMatch(value -> value.status() == SATISFIABLE));
        assertTrue(executionSamples(result).isEmpty());
    }
    private static List<RawSql.Variant> executions(AnalysisResult result) {
        return result.extractions().stream().filter(value -> value.callSite().target().getName().equals("executeQuery"))
                .flatMap(value -> value.rawSqls().stream()).toList();
    }
    private static List<SqlSample> executionSamples(AnalysisResult result) {
        return result.samples().stream().filter(value -> value.source() instanceof SqlExtraction extraction
                && extraction.callSite().target().getName().equals("executeQuery")).toList();
    }
    private AnalysisResult analyze(String methods, String librarySource) throws Exception {
        Path root = Files.createTempDirectory(temporary, "dispatch-completeness-");
        Path classes = Files.createDirectory(root.resolve("classes"));
        Path source = Files.writeString(root.resolve("Sample.java"), "public class Sample {" + methods + "}");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-g", "-d", classes.toString(), source.toString()));
        Path libraries = null;
        if (librarySource != null) {
            libraries = Files.createDirectory(root.resolve("libraries"));
            Path file = Files.writeString(root.resolve("LibraryGate.java"), librarySource);
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-cp", classes.toString(),
                    "-d", libraries.toString(), file.toString()));
        }
        var result = new Analyzer(AnalyzerConfig.defaults(), List.of(new JdbcAnalysisModule()), List.of())
                .analyze(classes, libraries, AnalysisOptions.defaults());
        assertNotEquals(AnalysisResult.Status.FAILED, result.status(), result.diagnostics().toString());
        return result;
    }
}
