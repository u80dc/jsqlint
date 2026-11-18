package org.u80dc.jsqlint.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.jdbc.JdbcAnalysisModule;

import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.InferenceOptions.Mode.*;

/** 通过真实 SQL 来源验证独立阶段预算确实贯通分析管线。 */
class AnalysisBudgetsTest {
    @TempDir Path temporary;

    @Test
    void replayDepthCanBeRaisedWithoutChangingSourceOrJointBudgets() throws Exception {
        Path classes = compile("public void query(java.sql.Connection c, int n) throws Exception {"
                + "n += 1;".repeat(140)
                + "var p = c.prepareStatement(\"SELECT ?\"); p.setInt(1, n); p.executeQuery(); }");
        var config = AnalyzerConfig.defaults().withMaxTraceDepth(1024).withMaxTraceNodes(50000)
                .withInferenceOptions(InferenceOptions.defaults().withMode(PARAMETERS).withRandomSeed(42));
        var limited = analyze(classes, config);
        assertEquals(AnalysisResult.Status.PARTIAL, limited.status(), limited.diagnostics().toString());
        assertTrue(binding(limited).inference().truncated());

        var expanded = analyze(classes, config.withReplayBudgets(ReplayBudgets.defaults().withDepth(1024)));
        assertEquals(AnalysisResult.Status.SUCCESS, expanded.status(), expanded.diagnostics().toString());
        assertTrue(binding(expanded).inference().complete());
        assertTrue(expanded.diagnostics().isEmpty());
        assertTrue(expanded.rawSqls().stream().allMatch(sql -> sql.jointInference().isEmpty()));
    }

    @Test
    void replayLengthLimitPreservesSourcesAndReportsOnlyTheRequestedStage() throws Exception {
        Path classes = compile("""
                public void query(java.sql.Connection c) throws Exception {
                    var p = c.prepareStatement("SELECT ?"); p.setString(1, "abcdef"); p.executeQuery();
                }
                """);
        var config = AnalyzerConfig.defaults().withInferenceOptions(InferenceOptions.defaults().withMode(PARAMETERS))
                .withReplayBudgets(ReplayBudgets.defaults().withLength(3));
        var limited = analyze(classes, config);
        assertEquals(AnalysisResult.Status.PARTIAL, limited.status(), limited.diagnostics().toString());
        assertTrue(binding(limited).complete());
        assertTrue(binding(limited).inference().truncated());
        assertTrue(limited.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("PARAMETER_INFERENCE_LIMIT")));
        assertEquals(AnalysisResult.Status.SUCCESS,
                analyze(classes, config.withInferenceOptions(InferenceOptions.defaults().withMode(EXTRACT))).status());
    }

    @Test
    void generatedExternalStringsAlsoRespectReplayLength() throws Exception {
        Path classes = compile("""
                public void query(java.sql.Connection c, String text) throws Exception {
                    var p = c.prepareStatement("SELECT ?"); p.setString(1, text); p.executeQuery();
                }
                """);
        var config = AnalyzerConfig.defaults().withInferenceOptions(InferenceOptions.defaults().withMode(PARAMETERS)
                .withGeneratorFactory(() -> (type, name) -> new org.u80dc.jsqlint.core.node.SqlValue.Known("abcdef")));
        var limited = analyze(classes, config.withReplayBudgets(ReplayBudgets.defaults().withLength(3)));
        assertEquals(AnalysisResult.Status.PARTIAL, limited.status(), limited.diagnostics().toString());
        assertTrue(binding(limited).inference().truncated());
        assertEquals(AnalysisResult.Status.SUCCESS,
                analyze(classes, config.withReplayBudgets(ReplayBudgets.defaults().withLength(6))).status());
    }

    @Test
    void jointDimensionBudgetCountsBindingKeysAndValues() throws Exception {
        Path classes = compile("""
                public void query(java.sql.Connection c) throws Exception {
                    var p = c.prepareStatement("SELECT ?, ?"); p.setInt(1, 7); p.setInt(2, 8); p.executeQuery();
                }
                """);
        var config = AnalyzerConfig.defaults();
        var limited = analyze(classes, config.withJointBudgets(JointBudgets.defaults().withDimensions(3)));
        assertEquals(AnalysisResult.Status.PARTIAL, limited.status(), limited.diagnostics().toString());
        assertTrue(limited.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("JOINT_ANALYSIS_INCOMPLETE")));
        assertEquals(AnalysisResult.Status.SUCCESS,
                analyze(classes, config.withJointBudgets(JointBudgets.defaults().withDimensions(4))).status());
    }

    @Test
    void shallowJointControlFlowBudgetReportsIncompleteCoverage() throws Exception {
        Path classes = compile("""
                public void query(java.sql.Connection c) throws Exception {
                    var p = c.prepareStatement("SELECT ?"); p.setInt(1, 7); p.executeQuery();
                }
                """);
        var result = analyze(classes, AnalyzerConfig.defaults()
                .withJointBudgets(JointBudgets.defaults().withControlFlowDepth(1)));
        assertEquals(AnalysisResult.Status.PARTIAL, result.status(), result.diagnostics().toString());
        var sample = result.samples().stream().filter(value -> !value.bindings().isEmpty()).findFirst().orElseThrow();
        assertEquals(Optional.of(false), sample.assessment().pathCoverageComplete());
    }

    @Test
    void recoveryVariantLimitDistinguishesExactCapacityFromAnOmittedBranch() throws Exception {
        Path classes = compile("""
                public void query(java.sql.Connection c, boolean flag) throws Exception {
                    String sql = flag ? "SELECT 1" : "SELECT 2";
                    c.prepareStatement(sql);
                }
                """);
        var config = AnalyzerConfig.defaults().withInferenceOptions(InferenceOptions.defaults().withMode(EXTRACT));
        var limited = analyze(classes, config.withSqlRecoveryBudgets(SqlRecoveryBudgets.defaults().withVariants(1)));
        assertEquals(AnalysisResult.Status.PARTIAL, limited.status(), limited.diagnostics().toString());
        assertEquals(1, limited.rawSqls().size());
        assertTrue(limited.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("UNSUPPORTED_VALUE")));

        var exact = analyze(classes, config.withSqlRecoveryBudgets(SqlRecoveryBudgets.defaults().withVariants(2)));
        assertEquals(AnalysisResult.Status.SUCCESS, exact.status(), exact.diagnostics().toString());
        assertEquals(Set.of("SELECT 1", "SELECT 2"), new HashSet<>(exact.rawSqls().stream().map(RawSql.Variant::text).toList()));
    }

    @Test
    void recoveryLengthLimitRetainsTheSourceWithAnExplicitRecoveryIssue() throws Exception {
        Path classes = compile("public void query(java.sql.Connection c) throws Exception { c.prepareStatement(\"SELECT 123\"); }");
        var config = AnalyzerConfig.defaults().withInferenceOptions(InferenceOptions.defaults().withMode(EXTRACT));
        var limited = analyze(classes, config.withSqlRecoveryBudgets(SqlRecoveryBudgets.defaults().withLength(5)));
        assertEquals(AnalysisResult.Status.PARTIAL, limited.status(), limited.diagnostics().toString());
        var statement = limited.calls().getFirst().statements().getFirst();
        assertTrue(statement.sourcesComplete());
        assertFalse(statement.rawSql().complete());
        assertTrue(statement.rawSql().issues().contains("Raw SQL length limit exceeded"));
        assertEquals(AnalysisResult.Status.SUCCESS,
                analyze(classes, config.withSqlRecoveryBudgets(SqlRecoveryBudgets.defaults().withLength(10))).status());
    }

    private AnalysisResult analyze(Path classes, AnalyzerConfig config) {
        return new Analyzer(config, List.of(new JdbcAnalysisModule()), List.of()).analyze(classes);
    }
    private static SqlExtraction.Binding binding(AnalysisResult result) {
        return result.calls().stream().filter(call -> call.callSite().target().getName().equals("executeQuery"))
                .findFirst().orElseThrow().statements().getFirst().bindings().getFirst();
    }
    private Path compile(String methods) throws Exception {
        Path root = Files.createDirectory(temporary.resolve(UUID.randomUUID().toString()));
        Path source = Files.writeString(root.resolve("Sample.java"), "public class Sample {" + methods + "}");
        Path classes = Files.createDirectory(root.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-proc:none", "-g", "-d", classes.toString(), source.toString()));
        return classes;
    }
}
