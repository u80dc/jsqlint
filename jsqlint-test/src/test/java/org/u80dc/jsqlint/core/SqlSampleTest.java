package org.u80dc.jsqlint.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.parameter.JointSqlInferenceResult;
import org.u80dc.jsqlint.core.spi.SourceLocation;
import org.u80dc.jsqlint.jdbc.JdbcAnalysisModule;

import javax.tools.ToolProvider;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.SqlSample.Feasibility.*;

/** 验证统一样例的路径关联、类型、未知状态和关闭输入后的独立渲染。 @author u80dc */
class SqlSampleTest {
    @TempDir Path temporary;

    @Test
    void jointSampleKeepsSqlAndTypedBindingsInTheSameModel() throws Exception {
        var result = analyze("""
                public void query(java.sql.Connection c, int n) throws Exception {
                    if (n != 100) return;
                    var p = c.prepareStatement("SELECT " + n + " WHERE a=? AND b=?");
                    p.setInt(1, n); p.setInt(2, n + 1); p.executeQuery();
                }
                """, AnalyzerConfig.defaults());
        var sample = only(result.samples().stream().filter(value -> value.bindings().size() == 2).toList());
        assertEquals("SELECT 100 WHERE a=? AND b=?", sample.sql().orElseThrow());
        assertEquals(SATISFIABLE, sample.feasibility(), sample.issues().toString());
        assertEquals(Map.of(new SqlValue.Known(1), new SqlValue.Known(100), new SqlValue.Known(2), new SqlValue.Known(101)),
                sample.bindings().stream().collect(java.util.stream.Collectors.toMap(SqlSample.Binding::keyValue, SqlSample.Binding::value)));
        assertEquals(List.of("int", "int"), sample.bindings().stream().map(SqlSample.Binding::type).toList());
        assertEquals(new SqlAssessment(true, true, true, Optional.of(true)), sample.assessment());
        assertSame(sample.rawSql().orElseThrow(), ((SqlExtraction) sample.source()).rawSqls().getFirst());
        assertTrue(sample.combination().isPresent());
        assertEquals(sample.bindings(), sample.rawSql().orElseThrow().bindingSnapshots().getFirst().bindings());
        assertEquals(sample.combination(), sample.rawSql().orElseThrow().bindingSnapshots().getFirst().combination());
        assertThrows(UnsupportedOperationException.class, () -> sample.bindings().clear());
    }

    @Test void identicalSqlKeepsEachJointBindingModelAsASeparateSnapshot() throws Exception {
        var result = analyze("""
                public void query(java.sql.Connection c, boolean flag) throws Exception {
                    var p = c.prepareStatement("SELECT ?");
                    if (flag) p.setInt(1,11); else p.setInt(1,22);
                    p.executeQuery();
                }
                """, AnalyzerConfig.defaults());
        var variants = result.calls().stream().filter(value -> value.callSite().target().getName().equals("executeQuery"))
                .flatMap(value -> value.rawSqls().stream()).toList();
        var snapshots = variants.stream().flatMap(raw -> raw.bindingSnapshots().stream()).toList();
        assertEquals(Set.of("SELECT ?"), variants.stream().map(RawSql.Variant::text).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(new SqlValue.Known(11),new SqlValue.Known(22)), snapshots.stream()
                .map(snapshot -> snapshot.bindings().getFirst().value()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(2, snapshots.size());
        var samples = result.samples().stream().filter(value -> value.rawSql().filter(variants::contains).isPresent()).toList();
        assertEquals(2, samples.size());
        for (var sample : samples) {
            var raw = sample.rawSql().orElseThrow();
            var saved = raw.bindingSnapshots().stream().filter(snapshot -> snapshot.combination().equals(sample.combination())).findFirst().orElseThrow();
            assertEquals(sample.bindings(), saved.bindings());
        }
    }

    @Test
    void rejectedVariantsStayInRawResultsButProduceNoSamples() throws Exception {
        var result = analyze("""
                public static void main(String[] args) throws Exception { query(null, "age"); }
                static void query(java.sql.Connection c, String sort) throws Exception {
                    String sql = "age".equals(sort) ? "SELECT age WHERE sort=?" : "SELECT id WHERE sort=?";
                    var p = c.prepareStatement(sql); p.setString(1, sort); p.executeQuery();
                }
                """, AnalyzerConfig.defaults());
        assertTrue(result.rawSqls().stream().anyMatch(value -> value.jointInference().orElseThrow().status()
                == JointSqlInferenceResult.Status.UNSATISFIABLE));
        assertFalse(result.samples().isEmpty());
        assertTrue(result.samples().stream().allMatch(value -> value.sql().orElseThrow().startsWith("SELECT age")));
        assertThrows(UnsupportedOperationException.class, () -> result.samples().clear());
    }

    @Test
    void unknownBindingDoesNotHideAvailableSqlOrInventNull() throws Exception {
        var result = analyze("""
                public void query(java.sql.Connection c, Object value) throws Exception {
                    var p = c.prepareStatement("SELECT ?"); p.setObject(1, value); p.executeQuery();
                }
                """, AnalyzerConfig.defaults());
        var sample = only(result.samples().stream().filter(value -> !value.bindings().isEmpty()).toList());
        assertEquals("SELECT ?", sample.sql().orElseThrow());
        assertEquals(UNKNOWN, sample.feasibility());
        assertInstanceOf(SqlValue.Unknown.class, sample.bindings().getFirst().value());
        assertTrue(sample.assessment().sourcesComplete());
        assertTrue(sample.assessment().textComplete());
        assertFalse(sample.assessment().valuesComplete());
        assertFalse(sample.issues().isEmpty());
        assertEquals(AnalysisResult.Status.SUCCESS, result.status(), "普通未知参数不表示遗漏来源或路径");
    }

    @Test
    void templateCanBeRenderedExplicitlyWithoutClaimingAValidatedPath() {
        var node = SqlNodeBuilder.buildScript("SELECT * FROM t <where><if test='enabled'>id=#{id}</if></where>");
        var source = new SqlTemplateResult("mybatis", new SourceLocation(URI.create("memory:/mapper.xml"), "Mapper.query"),
                node, Optional.of(SqlNodeRenderer.render(node, Map.of())), List.of());
        var unresolved = only(source.samples());
        assertTrue(unresolved.sql().isEmpty());
        assertEquals(UNVERIFIED, unresolved.feasibility());
        assertFalse(unresolved.issues().isEmpty());
        var sample = source.sample(Map.of("enabled", true, "id", 7));
        assertEquals("SELECT * FROM t WHERE id=7", sample.sql().orElseThrow());
        assertEquals(UNVERIFIED, sample.feasibility());
        assertTrue(sample.assessment().valuesComplete());
        assertTrue(sample.assessment().pathCoverageComplete().isEmpty());
        assertTrue(source.rendered().orElseThrow().sql().isEmpty(), "渲染样例不修改原始模板结果");
    }

    @Test
    void completeSingleCombinationAtTheBudgetBoundaryRemainsSatisfiable() throws Exception {
        for (var budget : List.of(JointBudgets.defaults().withCombinations(1), JointBudgets.defaults().withAttempts(1))) {
            var result = analyze("""
                    public void query(java.sql.Connection c) throws Exception {
                        var p = c.prepareStatement("SELECT ?"); p.setInt(1, 7); p.executeQuery();
                    }
                    """, AnalyzerConfig.defaults().withJointBudgets(budget));
            assertEquals(AnalysisResult.Status.SUCCESS, result.status(), result.diagnostics().toString());
            var extraction = only(result.calls().stream()
                    .filter(value -> value.callSite().target().getName().equals("executeQuery")).toList());
            var joint = only(extraction.rawSqls()).jointInference().orElseThrow();
            assertEquals(JointSqlInferenceResult.Status.SATISFIABLE, joint.status(), joint.issues().toString());
            assertTrue(joint.coverageComplete());
            assertEquals(1, joint.combinations().size());
            var sample = only(extraction.samples());
            assertEquals(SATISFIABLE, sample.feasibility());
            assertEquals(Optional.of(true), sample.assessment().pathCoverageComplete());
            assertEquals(new SqlValue.Known(7), only(sample.bindings()).value());
        }
    }

    @Test
    void limitedPathCoverageIsVisibleOnOtherwiseConcreteSamples() throws Exception {
        var result = analyze("""
                public void query(java.sql.Connection c, boolean flag) throws Exception {
                    int n = flag ? 1 : 2;
                    var p = c.prepareStatement("SELECT ?"); p.setInt(1, n); p.executeQuery();
                }
                """, AnalyzerConfig.defaults().withJointBudgets(JointBudgets.defaults().withCombinations(1)));
        assertEquals(AnalysisResult.Status.PARTIAL, result.status());
        var sample = only(result.samples().stream().filter(value -> !value.bindings().isEmpty()).toList());
        assertEquals("SELECT ?", sample.sql().orElseThrow());
        assertEquals(Optional.of(false), sample.assessment().pathCoverageComplete());
        assertFalse(sample.issues().isEmpty());
    }

    @Test
    void nullSqlRemainsUnresolvedInsteadOfBecomingTheTextNull() throws Exception {
        var result = analyze("""
                public void query(java.sql.Connection c) throws Exception { c.prepareStatement(null); }
                """, AnalyzerConfig.defaults());
        var sample = only(result.samples());
        assertTrue(sample.sql().isEmpty());
        assertFalse(sample.assessment().textComplete());
        assertEquals(UNKNOWN, sample.feasibility());
        assertFalse(sample.issues().isEmpty());
    }

    @Test
    void sampleSubstitutionPreservesIdenticalPlaceholderTextInsideSqlLiterals() throws Exception {
        var result = analyze("""
                public void query(java.sql.Connection c) throws Exception {
                    String n = "100";
                    c.prepareStatement("SELECT '${n}', " + n);
                }
                """, AnalyzerConfig.defaults());
        assertEquals("SELECT '${n}', 100", only(result.samples()).sql().orElseThrow());
    }

    @Test
    void batchSamplesRetainTheirOwnBindingSnapshotAndSource() throws Exception {
        var result = analyze("""
                public void query(java.sql.Connection c) throws Exception {
                    var p = c.prepareStatement("INSERT INTO t VALUES(?)");
                    p.setInt(1, 1); p.addBatch();
                    p.setInt(1, 2); p.addBatch(); p.executeBatch();
                }
                """, AnalyzerConfig.defaults());
        var samples = result.samples().stream().filter(value -> value.source() instanceof SqlExtraction extraction
                && extraction.callSite().target().getName().equals("executeBatch")).toList();
        assertEquals(2, samples.size());
        assertEquals(List.of(new SqlValue.Known(1), new SqlValue.Known(2)), samples.stream().map(value -> value.bindings().getFirst().value()).toList());
        assertTrue(samples.stream().allMatch(value -> value.batchEntry().isPresent()));
        assertEquals(2, samples.stream().map(SqlSample::batchEntry).distinct().count());
    }

    @Test
    void genericTemplateRenderLimitMakesTheSessionPartialAndKeepsItsSource() throws Exception {
        var location = new SourceLocation(URI.create("memory:/generic.sql"), "generic.query");
        var definition = new SqlDefinition("generic.query", location,
                new MixedSqlNode(Collections.nCopies(10001, ValueSqlNode.parameter("p"))));
        var module = new org.u80dc.jsqlint.core.spi.SqlAnalysisModule() {
            public String id() { return "generic-template"; }
            public void register(org.u80dc.jsqlint.core.spi.DiscoveryRegistry registry) {
                registry.extraction(site -> site.invocation().getMethodSignature().getName().equals("execute"),
                        (point, flow) -> List.of(org.u80dc.jsqlint.core.spi.SqlTraceSource.template(
                                new org.u80dc.jsqlint.core.spi.TemplateSqlInput(definition, Map.of(), Optional.empty(), Optional.empty(), Map.of("p", 7)), List.of())));
            }
        };
        var result = new Analyzer(List.of(module), List.of()).analyze(compile("public void run() { execute(); } void execute() {}"));
        assertEquals(AnalysisResult.Status.PARTIAL, result.status());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("SQL_RENDER_LIMIT")));
        var sample = only(result.samples());
        assertSame(definition, sample.definition().orElseThrow()); assertTrue(sample.sql().isEmpty());
        assertEquals(Optional.of(false), sample.assessment().pathCoverageComplete());
        assertTrue(sample.assessment().sourcesComplete());
    }

    private AnalysisResult analyze(String methods, AnalyzerConfig config) throws Exception {
        var result = new Analyzer(config, List.of(new JdbcAnalysisModule()), List.of()).analyze(compile(methods));
        assertNotEquals(AnalysisResult.Status.FAILED, result.status(), result.diagnostics().toString());
        return result;
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
