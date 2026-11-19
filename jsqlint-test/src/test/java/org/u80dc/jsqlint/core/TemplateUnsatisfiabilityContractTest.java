package org.u80dc.jsqlint.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.u80dc.jsqlint.core.node.SqlNode;
import org.u80dc.jsqlint.core.node.SqlNodeBuilder;
import org.u80dc.jsqlint.core.parameter.JointSqlInferenceResult;
import org.u80dc.jsqlint.core.spi.*;

import javax.tools.ToolProvider;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.parameter.JointSqlInferenceResult.Status.*;

/** 模板后处理可以增加未知信息，不能使已证明不可达的 Java 调用重新产生样例。 */
class TemplateUnsatisfiabilityContractTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void contradictoryJavaPathHasNoModelsForEitherTemplateKind(boolean conditional) throws Exception {
        var extraction = analyze("if (x > 0) { if (x < 0) query(x); }", template(conditional));
        var joint = joint(extraction);
        assertEquals(UNSATISFIABLE, joint.status(), joint.issues().toString());
        assertTrue(joint.coverageComplete());
        assertTrue(joint.combinations().isEmpty());
        assertTrue(extraction.samples().isEmpty(), "Java 已证明不可达时不能产生模板样例");
        assertTrue(extraction.statements().getFirst().templateInference().orElseThrow().models().isEmpty());
    }

    @Test void reachableConditionalTemplateStillReportsUnsolvedTemplateCoverage() throws Exception {
        var extraction = analyze("if (x > 0) query(x);", template(true));
        var joint = joint(extraction);
        assertEquals(UNKNOWN, joint.status());
        assertFalse(joint.coverageComplete());
        assertFalse(extraction.samples().isEmpty());
        assertTrue(extraction.samples().stream().allMatch(sample -> sample.feasibility() == SqlSample.Feasibility.UNKNOWN));
        assertTrue(joint.issues().stream().anyMatch(issue -> issue.contains("Template control conditions")));
    }

    @Test void explanatoryIssuesDoNotUndoACompleteUnsatisfiabilityProofAfterRendering() {
        var proof = new JointSqlInferenceResult(UNSATISFIABLE, List.of(), List.of("Contradictory supported Java conditions"), true);
        var completed = proof.withCombinations(List.of());
        assertEquals(UNSATISFIABLE, completed.status());
        assertTrue(completed.coverageComplete());
        assertEquals(proof.issues(), completed.issues());
        assertTrue(completed.combinations().isEmpty());
    }

    @Test void incompleteCoverageNeverBecomesAnUnsatisfiabilityProof() {
        var incomplete = new JointSqlInferenceResult(UNSATISFIABLE, List.of(), List.of("Path limit"), false);
        assertEquals(UNKNOWN, incomplete.withCombinations(List.of()).status());
        assertFalse(incomplete.withCombinations(List.of()).coverageComplete());
    }

    private static SqlNode template(boolean conditional) {
        return conditional ? SqlNodeBuilder.buildScript("SELECT <if test='x &gt; 0'>#{x}</if>")
                : SqlNodeBuilder.templateText("SELECT #{x}");
    }

    private SqlExtraction analyze(String body, SqlNode node) throws Exception {
        Path root = Files.createTempDirectory(temporary, "template-unsat-");
        Path classes = Files.createDirectory(root.resolve("classes"));
        Path source = Files.writeString(root.resolve("Sample.java"), "public class Sample {"
                + "public static void run(int x) { " + body + " } static void query(int x) {} }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-g", "-d", classes.toString(), source.toString()));
        var definition = new SqlDefinition("query", new SourceLocation(URI.create("memory:/query.sql"), "query"), node);
        var module = new SqlAnalysisModule() {
            public String id() { return "template-unsat"; }
            public void register(DiscoveryRegistry registry) {
                registry.extraction(site -> site.invocation().getMethodSignature().getName().equals("query"),
                        (point, flow) -> {
                            var argument = point.statement().asInvokableStmt().getInvokeExpr().orElseThrow().getArg(0);
                            var input = new TemplateSqlInput(definition,
                                    Map.of("x", new TemplateSqlInput.Parameter(point.value(argument), "int")), Optional.empty());
                            return List.of(SqlTraceSource.template(input, List.of()));
                        });
            }
        };
        var result = new Analyzer(List.of(module), List.of()).analyze(classes);
        assertNotEquals(AnalysisResult.Status.FAILED, result.status(), result.diagnostics().toString());
        assertEquals(1, result.extractions().size(), result.diagnostics().toString());
        return result.extractions().getFirst();
    }

    private static JointSqlInferenceResult joint(SqlExtraction extraction) {
        var results = extraction.statements().stream().flatMap(statement -> statement.jointInferences().stream()).toList();
        assertEquals(1, results.size());
        return results.getFirst();
    }
}
