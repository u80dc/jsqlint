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

class JdbcMethodEffectsTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {
            "java.util.Objects.requireNonNull(values);",
            "java.util.Objects.requireNonNull(values, \"SQL values required\");"
    })
    void readOnlyNonNullCheckPreservesTheArraySqlSource(String check) throws Exception {
        var result = analyze(check);
        assertEquals(AnalysisResult.Status.SUCCESS, result.status(), result.diagnostics().toString());
        var executions = executions(result);
        assertFalse(executions.isEmpty());
        assertTrue(executions.stream().allMatch(SqlExtraction::complete));
        assertEquals(Set.of("SELECT 1"), executions.stream().flatMap(value -> value.rawSqls().stream())
                .map(RawSql.Variant::text).collect(java.util.stream.Collectors.toSet()));
    }

    @Test void unknownArrayWriterRemainsAConservativeConsumer() throws Exception {
        var result = analyze("java.util.Arrays.fill(values, \"SELECT 2\");");
        assertEquals(AnalysisResult.Status.PARTIAL, result.status());
        assertTrue(executions(result).stream().anyMatch(value -> !value.complete()));
    }

    @Test void supplierOverloadIsNotMistakenForTheReadOnlySignature() throws Exception {
        var result = analyze("java.util.Objects.requireNonNull(values, () -> { values[0]=\"SELECT 2\"; return \"missing\"; });");
        assertEquals(AnalysisResult.Status.PARTIAL, result.status());
        assertTrue(executions(result).stream().anyMatch(value -> !value.complete()));
    }

    private static List<SqlExtraction> executions(AnalysisResult result) {
        return result.calls().stream().filter(value -> value.callSite().target().getName().equals("executeQuery")).toList();
    }
    private AnalysisResult analyze(String call) throws Exception {
        Path root = Files.createTempDirectory(temporary, "method-effects-");
        Path source = Files.writeString(root.resolve("Sample.java"), """
                public class Sample {
                    public static void run(java.sql.Connection c) throws Exception {
                        String[] values={"SELECT 1"}; %s
                        var p=c.prepareStatement(values[0]); p.executeQuery();
                    }
                }
                """.formatted(call));
        Path classes = Files.createDirectory(root.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-g", "-d", classes.toString(), source.toString()));
        var result = new Analyzer(List.of(new JdbcAnalysisModule()), List.of()).analyze(classes);
        assertNotEquals(AnalysisResult.Status.FAILED, result.status(), result.diagnostics().toString());
        return result;
    }
}
