package org.u80dc.jsqlint.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.parameter.JointSqlInferenceResult;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JdbcReferenceComparisonTest {
    @TempDir Path temporary;

    @Test void unknownBoxIdentityKeepsBothSqlBranchesWithoutProvingEither() throws Exception {
        var result = analyze("""
                Integer a=Integer.valueOf(1000),b=Integer.valueOf(1000);
                if(a==b)c.createStatement().execute("SELECT equal");
                else c.createStatement().execute("SELECT different");
                """, "");
        assertEquals(Set.of("SELECT equal", "SELECT different"), new HashSet<>(result.samples().stream()
                .map(sample -> sample.sql().orElseThrow()).toList()));
        assertTrue(result.samples().stream().allMatch(sample -> sample.feasibility() == SqlSample.Feasibility.UNKNOWN));
        assertTrue(result.calls().stream().filter(call -> call.callSite().target().getName().equals("execute"))
                .flatMap(call -> call.statements().stream()).flatMap(statement -> statement.jointInferences().stream())
                .allMatch(joint -> joint.status() == JointSqlInferenceResult.Status.UNKNOWN));
    }

    @Test void theSameNullableReferenceCanStillBeProvedEqual() throws Exception {
        var result = analyze("""
                if(a==a)c.createStatement().execute("SELECT self");
                else c.createStatement().execute("SELECT other");
                """, ",Integer a");
        assertEquals(List.of("SELECT self"), result.samples().stream().map(sample -> sample.sql().orElseThrow()).toList());
        assertEquals(SqlSample.Feasibility.SATISFIABLE, result.samples().getFirst().feasibility());
    }

    private AnalysisResult analyze(String body, String parameters) throws Exception {
        Path source = Files.writeString(temporary.resolve("Sample.java"),
                "public class Sample { public void run(java.sql.Connection c" + parameters + ") throws Exception {" + body + "} }");
        Path classes = Files.createTempDirectory(temporary, "classes-");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-g", "-d", classes.toString(), source.toString()));
        var result = new Analyzer(List.of(new JdbcAnalysisModule()), List.of()).analyze(classes);
        assertNotEquals(AnalysisResult.Status.FAILED, result.status(), result.diagnostics().toString());
        return result;
    }
}
