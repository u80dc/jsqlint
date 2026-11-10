package org.u80dc.jsqlint.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.jdbc.JdbcAnalysisModule;

import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.InferenceOptions.Mode.*;

/** 使用实际 SQL 调用验证阶段跳过、策略注入、可复现性及单次覆盖。 @author u80dc */
class InferenceOptionsTest {
    @TempDir Path temporary;

    @Test
    void extractionOnlySkipsGeneratorAndJointWorkWithoutMarkingPartial() throws Exception {
        Path classes = compile("""
                public void query(java.sql.Connection c, int n) throws Exception {
                    var p = c.prepareStatement("SELECT " + n + " WHERE id=?"); p.setInt(1, n); p.executeQuery();
                }
                """);
        AtomicInteger created = new AtomicInteger();
        var inference = InferenceOptions.defaults().withGeneratorFactory(() -> {
            created.incrementAndGet(); return (type, name) -> new SqlValue.Known(100);
        });
        var analyzer = analyzer(AnalyzerConfig.defaults().withInferenceOptions(inference));
        var result = analyzer.analyze(classes, AnalysisOptions.defaults().withInferenceOptions(inference.withMode(EXTRACT)));
        assertEquals(AnalysisResult.Status.SUCCESS, result.status(), result.diagnostics().toString());
        assertEquals(0, created.get());
        assertTrue(result.rawSqls().stream().allMatch(sql -> sql.jointInference().isEmpty()));
        var statement = execution(result).statements().getFirst();
        assertTrue(statement.sourcesComplete());
        assertTrue(statement.rawSql().complete());
        assertTrue(statement.bindings().getFirst().inference().possibleValues().isEmpty());
        var sample = only(execution(result).samples());
        assertTrue(sample.sql().isEmpty());
        assertTrue(sample.assessment().textComplete(), "SQL 结构完整不要求动态槽位已有取值");
        assertEquals(SqlSample.Feasibility.UNVERIFIED, sample.feasibility());
        assertTrue(sample.assessment().pathCoverageComplete().isEmpty());
        assertEquals(AnalysisResult.Status.SUCCESS, analyzer.analyze(classes).status());
        assertTrue(created.get() > 0, "本次关闭推断不改变 Analyzer 默认策略");
    }

    @Test
    void independentParameterInferenceDoesNotCreateUnverifiedJointCombinations() throws Exception {
        Path classes = compile("""
                public void query(java.sql.Connection c, boolean flag, String first, String second) throws Exception {
                    String n = flag ? first : second;
                    var p = c.prepareStatement("SELECT " + n + " WHERE id=?"); p.setString(1, n); p.executeQuery();
                }
                """);
        var config = AnalyzerConfig.defaults().withJointBudgets(JointBudgets.defaults().withCombinations(1))
                .withInferenceOptions(InferenceOptions.defaults().withMode(PARAMETERS));
        var result = analyzer(config).analyze(classes);
        assertEquals(AnalysisResult.Status.SUCCESS, result.status());
        var extraction = execution(result);
        assertTrue(extraction.rawSqls().stream().allMatch(sql -> sql.jointInference().isEmpty()));
        assertTrue(extraction.statements().getFirst().inferenceComplete());
        assertTrue(extraction.rawSqls().stream().allMatch(sql -> !sql.parameters().isEmpty()));
        assertTrue(extraction.samples().stream().allMatch(sample -> sample.sql().isEmpty()
                && sample.feasibility() == SqlSample.Feasibility.UNVERIFIED && sample.combination().isEmpty()));
    }

    @Test
    void seededAnalysisReproducesSamplesAndSharesInputsBetweenSqlAndBindings() throws Exception {
        Path classes = compile("""
                public void query(java.sql.Connection c, int n) throws Exception {
                    var p = c.prepareStatement("SELECT " + n + " WHERE id=?"); p.setInt(1, n); p.executeQuery();
                }
                """);
        var analyzer = analyzer(AnalyzerConfig.defaults().withInferenceOptions(InferenceOptions.defaults().withRandomSeed(42)));
        var first = only(execution(analyzer.analyze(classes)).samples());
        var second = only(execution(analyzer.analyze(classes)).samples());
        assertEquals(first.sql(), second.sql());
        assertEquals(first.bindings(), second.bindings());
        Object n = ((SqlValue.Known) first.bindings().getFirst().value()).value();
        assertEquals("SELECT " + n + " WHERE id=?", first.sql().orElseThrow());
        assertEquals(SqlSample.Feasibility.SATISFIABLE, first.feasibility());
    }

    @Test
    void customGeneratorIsUsedByBothIndependentInferenceAndJointReplay() throws Exception {
        Path classes = compile("""
                public void query(java.sql.Connection c, String table) throws Exception {
                    var p = c.prepareStatement("SELECT * FROM " + table + " WHERE id=?");
                    p.setString(1, table); p.executeQuery();
                }
                """);
        var config = AnalyzerConfig.defaults().withInferenceOptions(InferenceOptions.defaults()
                .withGeneratorFactory(() -> (type, name) -> new SqlValue.Known("customers")));
        var extraction = execution(analyzer(config).analyze(classes));
        var sample = only(extraction.samples());
        assertEquals("SELECT * FROM customers WHERE id=?", sample.sql().orElseThrow());
        assertEquals(new SqlValue.Known("customers"), sample.bindings().getFirst().value());
        assertEquals(new SqlValue.Known("customers"), extraction.rawSqls().getFirst().parameters().getFirst().possibleValues().getFirst().value());
    }

    @Test
    void jointReplayFailureRetainsAnUnknownSampleWithoutAnySolvedCombination() throws Exception {
        Path classes = compile("""
                public void query(java.sql.Connection c, String value) throws Exception {
                    var p = c.prepareStatement("SELECT ?"); p.setString(1, value); p.executeQuery();
                }
                """);
        var config = AnalyzerConfig.defaults().withInferenceOptions(InferenceOptions.defaults()
                .withGeneratorFactory(() -> (type, name) -> { throw new IllegalStateException("Sample unavailable"); }));
        var result = analyzer(config).analyze(classes);
        assertEquals(AnalysisResult.Status.PARTIAL, result.status());
        var extraction = execution(result);
        assertTrue(extraction.rawSqls().getFirst().jointInference().orElseThrow().combinations().isEmpty());
        var sample = only(extraction.samples());
        assertEquals("SELECT ?", sample.sql().orElseThrow());
        assertEquals(SqlSample.Feasibility.UNKNOWN, sample.feasibility());
        assertInstanceOf(SqlValue.Unknown.class, sample.bindings().getFirst().value());
        assertEquals(Optional.of(false), sample.assessment().pathCoverageComplete());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("JOINT_PATH_INFERENCE")));
    }

    @Test
    void generatorFactoryFailureRetainsRecoveredSqlAndReportsItsCause() throws Exception {
        Path classes = compile("""
                public void query(java.sql.Connection c, String value) throws Exception {
                    var p = c.prepareStatement("SELECT ?"); p.setString(1, value); p.executeQuery();
                }
                """);
        var config = AnalyzerConfig.defaults().withInferenceOptions(InferenceOptions.defaults().withGeneratorFactory(() -> {
            throw new IllegalStateException("Generator unavailable");
        }));
        var result = analyzer(config).analyze(classes);
        assertEquals(AnalysisResult.Status.PARTIAL, result.status());
        assertEquals("SELECT ?", only(execution(result).samples()).sql().orElseThrow());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("EXTERNAL_VALUE_GENERATOR")));
    }

    private Analyzer analyzer(AnalyzerConfig config) { return new Analyzer(config, List.of(new JdbcAnalysisModule()), List.of()); }
    private static SqlExtraction execution(AnalysisResult result) {
        assertNotEquals(AnalysisResult.Status.FAILED, result.status(), result.diagnostics().toString());
        return only(result.extractions().stream().filter(value -> value.callSite().target().getName().equals("executeQuery")).toList());
    }
    private Path compile(String methods) throws Exception {
        Path root = Files.createDirectory(temporary.resolve(UUID.randomUUID().toString()));
        Path source = Files.writeString(root.resolve("Sample.java"), "public class Sample {" + methods + "}");
        Path classes = Files.createDirectory(root.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-g", "-d", classes.toString(), source.toString()));
        return classes;
    }
    private static <T> T only(List<T> values) { assertEquals(1, values.size(), values.toString()); return values.getFirst(); }
}
