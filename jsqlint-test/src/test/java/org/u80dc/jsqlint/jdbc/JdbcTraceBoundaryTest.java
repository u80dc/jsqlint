package org.u80dc.jsqlint.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.SqlValue;

import javax.tools.ToolProvider;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.parameter.JointSqlInferenceResult.Status.*;

/** 用真实字节码验证正常终点、截断状态和独立溯源预算。 @author u80dc */
class JdbcTraceBoundaryTest {
    @TempDir Path temporary;

    @Test
    void truncatedCallerKeepsKnownSqlButCannotClaimSatisfiable() throws Exception {
        var result = analyze(compile("""
                static void top(java.sql.Statement s) throws Exception { middle(s); }
                static void middle(java.sql.Statement s) throws Exception { query(s); }
                static void query(java.sql.Statement s) throws Exception { s.execute("SELECT 1"); }
                """), AnalyzerConfig.defaults().withMaxCallDepth(1));
        assertEquals(AnalysisResult.Status.PARTIAL, result.status());
        var extraction = only(result.extractions());
        assertTrue(extraction.issues().stream().anyMatch(issue -> issue.contains("LIMIT")));
        assertUnknown(only(sqls(result)), "LIMIT");
    }

    @Test
    void recursiveCallerCannotMakeUnreachableSqlSatisfiable() throws Exception {
        var result = analyze(compile("""
                static void query(java.sql.Statement s) throws Exception {
                    query(s); s.execute("SELECT 1");
                }
                """), AnalyzerConfig.defaults());
        assertEquals(AnalysisResult.Status.PARTIAL, result.status());
        assertUnknown(only(sqls(result)), "CYCLE");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void terminalCallerAtExactDepthBoundaryIsComplete(boolean main) throws Exception {
        String entry = main
                ? "public static void main(String[] args) throws Exception { query(null); }"
                : "static void entry(java.sql.Statement s) throws Exception { query(s); }";
        var result = analyze(compile(entry + """
                static void query(java.sql.Statement s) throws Exception { s.execute("SELECT 1"); }
                """), AnalyzerConfig.defaults().withMaxCallDepth(1));
        assertEquals(AnalysisResult.Status.SUCCESS, result.status());
        assertTrue(only(result.extractions()).issues().isEmpty());
        assertEquals(SATISFIABLE, only(sqls(result)).jointInference().orElseThrow().status());
    }

    @Test
    void truncatedContextDoesNotContaminateACompleteSiblingContext() throws Exception {
        var result = analyze(compile("""
                public static void main(String[] args) throws Exception { query(null); first(null); }
                static void first(java.sql.Statement s) throws Exception { second(s); }
                static void second(java.sql.Statement s) throws Exception { query(s); }
                static void query(java.sql.Statement s) throws Exception { s.execute("SELECT 1"); }
                """), AnalyzerConfig.defaults().withMaxCallDepth(1));
        var values = sqls(result);
        assertEquals(2, values.size());
        assertEquals(Set.of(SATISFIABLE, UNKNOWN), values.stream()
                .map(sql -> sql.jointInference().orElseThrow().status()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void longLocalValueChainDoesNotConsumeCallDepth() throws Exception {
        Path classes = compile(longValueChain());
        var config = AnalyzerConfig.defaults().withMaxCallDepth(1);
        var result = analyze(classes, config);
        assertEquals(AnalysisResult.Status.SUCCESS, result.status());
        var joint = only(sqls(result)).jointInference().orElseThrow();
        assertEquals(SATISFIABLE, joint.status(), joint.issues().toString());
        var binding = only(only(joint.combinations()).bindings());
        assertEquals(new SqlValue.Known(21), binding.value().value());

        var limited = analyze(classes, config.withMaxTraceDepth(8));
        assertEquals(UNKNOWN, only(sqls(limited)).jointInference().orElseThrow().status());
        assertTrue(nodes(only(execution(limited).statements()).bindings().getFirst().value().root())
                .anyMatch(node -> node.kind() == ValueTrace.Kind.LIMIT));
        assertTrue(execution(limited).issues().isEmpty(), "数据依赖截断不能冒充调用链截断");
    }

    @Test
    void traceNodeBudgetIsIndependentOfBothDepths() throws Exception {
        var result = analyze(compile(longValueChain()), AnalyzerConfig.defaults()
                .withMaxCallDepth(1).withMaxTraceDepth(256).withMaxTraceNodes(8));
        assertEquals(UNKNOWN, only(sqls(result)).jointInference().orElseThrow().status());
        assertTrue(nodes(only(execution(result).statements()).bindings().getFirst().value().root())
                .anyMatch(node -> node.kind() == ValueTrace.Kind.LIMIT && node.detail().contains("node")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void valueTerminalAtExactDepthBoundaryDoesNotNeedExpansion(boolean literal) throws Exception {
        var classes = compile("""
                static int first() { return 7; }
                static void query(java.sql.Connection c,int n) throws Exception {
                    var p=c.prepareStatement("SELECT ?"); p.setInt(1,%s); p.executeQuery();
                }
                """.formatted(literal ? "first()" : "n"));
        var result = analyze(classes, AnalyzerConfig.defaults().withMaxTraceDepth(literal ? 2 : 1));
        var joint = only(sqls(result)).jointInference().orElseThrow();
        assertEquals(SATISFIABLE, joint.status(), joint.issues().toString());
        assertTrue(only(execution(result).statements()).bindings().getFirst().value().complete());
    }

    @Test
    void returnValueExpansionRespectsCallDepthEvenWithLargeDataBudget() throws Exception {
        Path classes = compile("""
                static int first() { return second(); }
                static int second() { return 7; }
                static void query(java.sql.Statement s) throws Exception {
                    s.execute("SELECT " + first());
                }
                """);
        var config = AnalyzerConfig.defaults().withMaxCallDepth(1);
        assertEquals(UNKNOWN, only(sqls(analyze(classes, config))).jointInference().orElseThrow().status());
        var complete = only(sqls(analyze(classes, config.withMaxCallDepth(2))));
        assertEquals(SATISFIABLE, complete.jointInference().orElseThrow().status());
        assertEquals("SELECT 7", complete.text(), "确定的返回常量应恢复到 SQL 文本中");
    }

    private String longValueChain() {
        StringBuilder text = new StringBuilder("static void query(java.sql.Connection c,int n) throws Exception { if(n!=1)return;int value=n;");
        for (int i=0; i<20; i++) text.append("value=value+1;");
        return text.append("var p=c.prepareStatement(\"SELECT ?\");p.setInt(1,value);p.executeQuery();}").toString();
    }

    private Path compile(String methods) throws Exception {
        Path directory = Files.createTempDirectory(temporary, "case-");
        Path source = Files.writeString(directory.resolve("Sample.java"), "public class Sample {" + methods + "}");
        Path classes = Files.createDirectory(directory.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-proc:none", "-g", "-d", classes.toString(), source.toString()));
        return classes;
    }

    private AnalysisResult analyze(Path classes, AnalyzerConfig config) {
        // 覆盖单次选项时必须保留实例上的三项预算。
        var result = new Analyzer(config, List.of(new JdbcAnalysisModule()), List.of())
                .analyze(classes, AnalysisOptions.defaults().withTimeout(Duration.ZERO));
        assertNotEquals(AnalysisResult.Status.FAILED, result.status());
        return result;
    }

    private List<RawSql.Variant> sqls(AnalysisResult result) {
        return result.extractions().stream().filter(e -> Set.of("execute", "executeQuery").contains(e.callSite().target().getName()))
                .flatMap(e -> e.statements().stream()).flatMap(s -> s.rawSql().variants().stream()).toList();
    }

    private SqlExtraction execution(AnalysisResult result) {
        return only(result.extractions().stream().filter(e -> e.callSite().target().getName().equals("executeQuery")).toList());
    }

    private void assertUnknown(RawSql.Variant sql, String reason) {
        assertEquals("SELECT 1", sql.text(), "截断不应丢失已确定的 SQL 文本");
        var joint = sql.jointInference().orElseThrow();
        assertEquals(UNKNOWN, joint.status());
        assertTrue(joint.issues().stream().anyMatch(issue -> issue.contains(reason)));
        assertFalse(joint.combinations().isEmpty());
        assertTrue(joint.combinations().stream().allMatch(c -> c.status() == UNKNOWN
                && c.issues().stream().anyMatch(issue -> issue.contains(reason))));
    }

    private static Stream<ValueTrace.Node> nodes(ValueTrace.Node node) {
        return Stream.concat(Stream.of(node), node.inputs().stream().flatMap(JdbcTraceBoundaryTest::nodes));
    }
    private static <T> T only(List<T> values) { assertEquals(1, values.size()); return values.getFirst(); }
}
