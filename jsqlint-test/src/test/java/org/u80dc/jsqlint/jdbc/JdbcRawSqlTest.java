package org.u80dc.jsqlint.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.SqlNodeRenderer;

import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** 从真实字节码验证传播链恢复出的 SQL 文本与变量槽位，不执行数据库操作。 @author u80dc */
class JdbcRawSqlTest {
    @TempDir Path temporary;

    @Test
    void concatenatedVariablesKeepNamesOrderAndRepeatedOccurrences() throws Exception {
        var raw = raw(analyze("""
                public class Sample {
                    public static void main(String[] args) throws Exception { run(null, 7, "Ada"); }
                    static void run(java.sql.Statement s, int id, String name) throws Exception {
                        String sql = "SELECT * FROM users WHERE id=" + id + " OR parent=" + id + " AND name='" + name + "'";
                        s.execute(sql);
                    }
                }
                """), "execute");
        assertTrue(raw.complete(), raw.issues().toString());
        var sql = only(raw.variants());
        assertEquals("SELECT * FROM users WHERE id=${id} OR parent=${id} AND name='${name}'", sql.text());
        assertEquals(List.of("id", "id", "name"), names(sql));
        assertEquals("java.lang.String", sql.placeholders().getFirst().source().variable().type());
        assertEquals("int", sql.placeholders().getFirst().source().inputs().getFirst().variable().type(), "原始类型仍在转换链中保留");
        assertEquals("SELECT * FROM users WHERE id=42 OR parent=42 AND name='Bob'",
                SqlNodeRenderer.render(sql.template(), Map.of("id", 42, "name", "Bob")).sql().orElseThrow());
        assertSlots(sql);
    }

    @Test
    void literalsRetainWhitespaceEscapesAndPlaceholderLikeText() throws Exception {
        var raw = raw(analyze("""
                public class Sample {
                    static void run(java.sql.Statement s, int id) throws Exception {
                        s.execute("SELECT '${literal}', 'C:\\\\tmp'\\n\\tFROM users  WHERE id=" + id);
                    }
                }
                """), "execute");
        var sql = only(raw.variants());
        assertEquals("SELECT '${literal}', 'C:\\tmp'\n\tFROM users  WHERE id=${id}", sql.text());
        assertEquals(List.of("id"), names(sql));
        assertEquals("SELECT '${literal}', 'C:\\tmp'\n\tFROM users  WHERE id=5",
                SqlNodeRenderer.render(sql.template(), Map.of("id", 5, "literal", "must not replace")).sql().orElseThrow());
        assertSlots(sql);
    }

    @Test
    void helperReturnsAndStringConcatRetainSqlFragmentsAndBusinessNames() throws Exception {
        var raw = raw(analyze("""
                public class Sample {
                    static void run(java.sql.Statement s, String email, String keyword) throws Exception {
                        String sql = "SELECT * FROM users".concat(" WHERE email=" + quote(email))
                            .concat(" AND name LIKE " + quote("%" + keyword + "%"));
                        s.execute(sql);
                    }
                    static String quote(String value) {
                        return "'" + java.util.Objects.requireNonNull(value).replace("'", "''") + "'";
                    }
                }
                """), "execute");
        assertTrue(raw.complete(), raw.issues().toString());
        var sql = only(raw.variants());
        assertEquals("SELECT * FROM users WHERE email='${email}' AND name LIKE '%${keyword}%'", sql.text());
        assertEquals(ValueTrace.Operation.REPLACE, sql.placeholders().getFirst().source().computation().operation());
        assertSlots(sql);
    }

    @Test
    void sqlForwardedThroughParametersRecoversLiteralAndStructuralArguments() throws Exception {
        var result = analyze("""
                public class Sample {
                    static void run(java.sql.Statement s, int id) throws Exception {
                        execute(s, "SELECT fixed");
                        execute(s, "SELECT * FROM users WHERE id=" + id);
                    }
                    static void execute(java.sql.Statement s, String sql) throws Exception { s.execute(sql); }
                }
                """);
        var statements = statements(result, "execute");
        assertEquals(Set.of("SELECT fixed", "SELECT * FROM users WHERE id=${id}"), statements.stream()
                .flatMap(s -> s.rawSql().variants().stream()).map(RawSql.Variant::text).collect(Collectors.toSet()));
        assertTrue(statements.stream().allMatch(s -> s.rawSql().complete()));
    }

    @Test
    void alternativesAndArrayWritesAreRecoveredWithoutPickingOnlyOne() throws Exception {
        var raw = raw(analyze("""
                public class Sample {
                    static void run(java.sql.Statement s, boolean flag, int id) throws Exception {
                        String[] sql = new String[1];
                        sql[0] = flag ? "SELECT first WHERE id=" + id : "SELECT second WHERE id=" + id;
                        s.execute(sql[0]);
                    }
                }
                """), "execute");
        assertTrue(raw.complete(), raw.issues().toString());
        assertEquals(Set.of("SELECT first WHERE id=${id}", "SELECT second WHERE id=${id}"), texts(raw));
    }

    @Test
    void preparedMarkersRemainSeparateFromConcatenatedVariables() throws Exception {
        var statement = only(statements(analyze("""
                public class Sample {
                    static void run(java.sql.Connection c, String table, int id) throws Exception {
                        var p = c.prepareStatement("SELECT * FROM " + table + " WHERE id=?");
                        p.setInt(1, id); p.executeQuery();
                    }
                }
                """), "executeQuery"));
        var sql = only(statement.rawSql().variants());
        assertEquals("SELECT * FROM ${table} WHERE id=?", sql.text());
        assertEquals(List.of("table"), names(sql));
        assertEquals("1", only(statement.bindings()).key());
    }

    @Test
    void unknownOperationsKeepNamedSlotAndDiagnostic() throws Exception {
        var result = analyze("""
                public class Sample {
                    static void run(java.sql.Statement s, String input) throws Exception {
                        String normalized = input.toUpperCase();
                        s.execute("SELECT * FROM " + normalized);
                    }
                }
                """);
        var raw = raw(result, "execute");
        assertFalse(raw.complete());
        assertEquals("SELECT * FROM ${normalized}", only(raw.variants()).text());
        assertEquals("SELECT * FROM ${normalized}", only(result.rawSqls()).text(), "顶层结果不能丢弃不完整 SQL");
        assertFalse(only(result.rawSqls()).placeholders().isEmpty());
        assertFalse(only(raw.variants()).placeholders().getFirst().source().inputs().isEmpty());
        assertFalse(raw.issues().isEmpty());
    }

    @Test
    void distinctSourcesWithSameVariableNameDoNotShareSampleValue() throws Exception {
        var raw = raw(analyze("""
                public class Sample {
                    static void run(java.sql.Statement s) throws Exception { s.execute("SELECT " + left(1) + "," + right(2)); }
                    static String left(int id) { return "left=" + id; }
                    static String right(int id) { return "right=" + id; }
                }
                """), "execute");
        assertEquals("SELECT left=${id},right=${id_2}", only(raw.variants()).text());
        assertEquals(2, only(raw.variants()).placeholders().stream().map(p -> p.source().variable().method()).distinct().count());
    }

    private AnalysisResult analyze(String source) throws Exception {
        return analyze(source, "-g");
    }

    @Test
    void nullActualArgumentDoesNotBecomeAVariableName() throws Exception {
        var raw = raw(analyze("""
                public class Sample {
                    public static void main(String[] args) throws Exception { run(null, null); }
                    static void run(java.sql.Statement s, String keyword) throws Exception {
                        s.execute("SELECT * FROM users WHERE name='" + keyword + "'");
                    }
                }
                """), "execute");
        assertEquals("SELECT * FROM users WHERE name='${keyword}'", only(raw.variants()).text());
    }

    @Test
    void transformedAndOriginalValuesUseIndependentSlots() throws Exception {
        var raw = raw(analyze("""
                public class Sample {
                    static void run(java.sql.Statement s, String name) throws Exception {
                        s.execute("SELECT '" + name + "','" + name.replace("'", "''") + "'");
                    }
                }
                """), "execute");
        var sql = only(raw.variants());
        assertEquals("SELECT '${name}','${name_2}'", sql.text());
        assertEquals(ValueTrace.Operation.REPLACE, sql.placeholders().getLast().source().computation().operation());
        assertEquals("SELECT 'O'Brien','O''Brien'", SqlNodeRenderer.render(sql.template(),
                Map.of("name", "O'Brien", "name_2", "O''Brien")).sql().orElseThrow());
    }

    @Test
    void javaNullAndPrimitiveConversionsArePreserved() throws Exception {
        var raw = raw(analyze("""
                public class Sample {
                    static void run(java.sql.Statement s, int id) throws Exception {
                        char initial = 'A'; boolean active = false; String absent = null;
                        s.execute("SELECT " + id + "," + initial + "," + active + "," + absent);
                    }
                }
                """), "execute");
        assertTrue(raw.complete(), raw.issues().toString());
        assertEquals("SELECT ${id},A,false,null", only(raw.variants()).text());
    }

    @Test
    void missingDebugNamesStillProduceStableParameterSlots() throws Exception {
        var raw = raw(analyze("""
                public class Sample {
                    static void run(java.sql.Statement s, int id) throws Exception { s.execute("SELECT " + id + "," + id); }
                }
                """, "-g:none"), "execute");
        assertTrue(raw.complete(), raw.issues().toString());
        assertEquals("SELECT ${parameter1},${parameter1}", only(raw.variants()).text());
    }

    @Test
    void branchExplosionRetainsBoundedCandidatesAndReportsTruncation() throws Exception {
        var raw = raw(analyze("""
                public class Sample {
                    static void run(java.sql.Statement s, boolean a, boolean b, boolean c, boolean d,
                                    boolean e, boolean f, boolean g, boolean h) throws Exception {
                        String sql = "SELECT " + (a ? "a" : "A") + (b ? "b" : "B") + (c ? "c" : "C")
                            + (d ? "d" : "D") + (e ? "e" : "E") + (f ? "f" : "F")
                            + (g ? "g" : "G") + (h ? "h" : "H");
                        s.execute(sql);
                    }
                }
                """), "execute");
        assertFalse(raw.complete());
        assertEquals(128, raw.variants().size());
        assertTrue(raw.variants().stream().allMatch(v -> v.text().startsWith("SELECT ") && v.text().length() == 15));
        assertTrue(raw.issues().stream().anyMatch(issue -> issue.contains("variant limit")), raw.issues().toString());
    }

    @Test
    void expandingReplacementReportsLengthLimitInsteadOfAllocatingWholeResult() throws Exception {
        String source = """
                public class Sample {
                    static void run(java.sql.Statement s) throws Exception { s.execute("SELECT " + "%s".replace("x", "%s")); }
                }
                """.formatted("x".repeat(2000), "y".repeat(1000));
        var raw = raw(analyze(source), "execute");
        assertFalse(raw.complete());
        assertTrue(raw.issues().stream().anyMatch(issue -> issue.contains("length limit")), raw.issues().toString());
        assertFalse(only(raw.variants()).placeholders().isEmpty());
    }

    @Test
    void externalArrayInputDoesNotHidePossibleWritesAndDiagnostics() throws Exception {
        var result = analyze("""
                public class Sample {
                    static void run(java.sql.Statement s, String[] sql, int index) throws Exception {
                        sql[index] = "SELECT written"; s.execute(sql[0]);
                    }
                }
                """);
        var raw = raw(result, "execute");
        assertFalse(raw.complete());
        assertEquals(AnalysisResult.Status.PARTIAL, result.status());
        assertTrue(texts(raw).contains("SELECT written"));
        assertTrue(raw.variants().stream().anyMatch(v -> !v.placeholders().isEmpty()));
        assertFalse(raw.issues().isEmpty());
    }

    @Test
    void classConstantsAreNotMistakenForTheirJimplePrintedText() throws Exception {
        var raw = raw(analyze("""
                public class Sample {
                    static void run(java.sql.Statement s) throws Exception { s.execute("SELECT '" + Sample.class + "'"); }
                }
                """), "execute");
        assertFalse(raw.complete());
        assertFalse(only(raw.variants()).placeholders().isEmpty());
    }

    @Test
    void differentBranchCallsKeepTheirOwnSqlPropagation() throws Exception {
        var raw = raw(analyze("""
                public class Sample {
                    static void run(java.sql.Statement s, boolean flag, int id) throws Exception {
                        String sql = flag ? users(id) : roles(id); s.execute(sql);
                    }
                    static String users(int id) { return "SELECT * FROM users WHERE id=" + id; }
                    static String roles(int id) { return "SELECT * FROM roles WHERE id=" + id; }
                }
                """), "execute");
        assertTrue(raw.complete(), raw.issues().toString());
        assertEquals(2, raw.variants().size());
        for (var variant : raw.variants()) {
            String selected = variant.text().contains("FROM users") ? "users" : "roles";
            String excluded = selected.equals("users") ? "roles" : "users";
            assertTrue(traceMethods(variant).contains(selected));
            assertFalse(traceMethods(variant).contains(excluded), "单条 raw SQL 的传播树不能混入另一个分支调用");
            assertTrue(nodes(variant.trace().orElseThrow().root())
                    .filter(n -> n.computation().operation() == ValueTrace.Operation.ALTERNATIVES)
                    .allMatch(n -> n.inputs().size() == 1));
        }
    }

    @Test
    void identicalSqlFromDifferentMethodsKeepsBothPropagationChains() throws Exception {
        var raw = raw(analyze("""
                public class Sample {
                    static void run(java.sql.Statement s, boolean flag) throws Exception {
                        String sql = flag ? left() : right(); s.execute(sql);
                    }
                    static String left() { return "SELECT same"; }
                    static String right() { return "SELECT same"; }
                }
                """), "execute");
        assertEquals(2, raw.variants().size(), "无变量的相同 SQL 也不能丢失各自的调用分支");
        assertEquals(Set.of("SELECT same"), texts(raw));
        assertEquals(Set.of(Set.of("run", "left"), Set.of("run", "right")), raw.variants().stream()
                .map(JdbcRawSqlTest::traceMethods).collect(Collectors.toSet()));
    }

    @Test
    void identicalSqlAtDifferentReturnsOfSameMethodKeepsBothBranches() throws Exception {
        var raw = raw(analyze("""
                public class Sample {
                    static void run(java.sql.Statement s, boolean flag) throws Exception { s.execute(query(flag)); }
                    static String query(boolean flag) { if (flag) return "SELECT same"; return "SELECT same"; }
                }
                """), "execute");
        assertEquals(2, raw.variants().size());
        assertEquals(2, raw.variants().stream().flatMap(v -> nodes(v.trace().orElseThrow().root()))
                .filter(n -> n.kind() == ValueTrace.Kind.CONSTANT && n.variable().method().getName().equals("query"))
                .map(n -> n.variable().statementIndex()).distinct().count());
    }

    @Test
    void concatenatedBranchCallsPreserveEachCombinationAndItsSources() throws Exception {
        var raw = raw(analyze("""
                public class Sample {
                    static void run(java.sql.Statement s, boolean a, boolean b) throws Exception {
                        s.execute((a ? users() : roles()) + (b ? active() : allRows()));
                    }
                    static String users() { return "SELECT * FROM users"; }
                    static String roles() { return "SELECT * FROM roles"; }
                    static String active() { return " WHERE active=1"; }
                    static String allRows() { return " WHERE 1=1"; }
                }
                """), "execute");
        assertEquals(Set.of("SELECT * FROM users WHERE active=1", "SELECT * FROM users WHERE 1=1",
                "SELECT * FROM roles WHERE active=1", "SELECT * FROM roles WHERE 1=1"), texts(raw));
        assertEquals(4, raw.variants().size());
        for (var variant : raw.variants()) {
            assertEquals(Set.of("run", variant.text().contains("users") ? "users" : "roles",
                    variant.text().contains("active=1") ? "active" : "allRows"), traceMethods(variant));
        }
    }

    @Test
    void sharedExecutionMethodRetainsDistinctCallerChainsOnEachRawSql() throws Exception {
        var result = analyze("""
                public class Sample {
                    static void run(java.sql.Statement s, boolean flag) throws Exception { if (flag) left(s); else right(s); }
                    static void left(java.sql.Statement s) throws Exception { execute(s, "SELECT same"); }
                    static void right(java.sql.Statement s) throws Exception { execute(s, "SELECT same"); }
                    static void execute(java.sql.Statement s, String sql) throws Exception { s.execute(sql); }
                }
                """);
        var statements = statements(result, "execute");
        assertEquals(2, statements.size());
        assertEquals(2, result.rawSqls().size(), "顶层结果应保留文本相同但调用路径不同的候选");
        assertEquals(Set.of(List.of("left", "execute"), List.of("right", "execute")), result.rawSqls().stream()
                .map(sql -> sql.trace().orElseThrow().callChain().stream().map(c -> c.target().getName()).toList())
                .collect(Collectors.toSet()));
        Set<List<String>> paths = new HashSet<>();
        for (var statement : statements) {
            var variant = only(statement.rawSql().variants());
            assertEquals("SELECT same", variant.text());
            assertEquals(statement.sql().orElseThrow().callChain(), variant.trace().orElseThrow().callChain());
            paths.add(variant.trace().orElseThrow().callChain().stream().map(c -> c.target().getName()).toList());
        }
        assertEquals(Set.of(List.of("left", "execute"), List.of("right", "execute")), paths);
    }

    private static Set<String> traceMethods(RawSql.Variant variant) {
        return nodes(variant.trace().orElseThrow().root()).map(n -> n.variable().method().getName()).collect(Collectors.toSet());
    }

    private static Stream<ValueTrace.Node> nodes(ValueTrace.Node node) {
        return Stream.concat(Stream.of(node), node.inputs().stream().flatMap(JdbcRawSqlTest::nodes));
    }

    private AnalysisResult analyze(String source, String debug) throws Exception {
        Path java = Files.writeString(temporary.resolve("Sample.java"), source);
        Path classes = Files.createDirectory(temporary.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-proc:none", debug, "-d", classes.toString(), java.toString()));
        var result = new Analyzer(AnalyzerConfig.defaults().withMaxTraceDepth(64), List.of(new JdbcAnalysisModule()), List.of())
                .analyze(classes);
        assertNotEquals(AnalysisResult.Status.FAILED, result.status());
        return result;
    }

    private static List<SqlExtraction.Statement> statements(AnalysisResult result, String target) {
        return result.calls().stream().filter(e -> e.callSite().target().getName().equals(target))
                .flatMap(e -> e.statements().stream()).toList();
    }
    private static RawSql raw(AnalysisResult result, String target) { return only(statements(result, target)).rawSql(); }
    private static <T> T only(List<T> values) { assertEquals(1, values.size(), values.toString()); return values.getFirst(); }
    private static Set<String> texts(RawSql raw) { return raw.variants().stream().map(RawSql.Variant::text).collect(Collectors.toSet()); }
    private static List<String> names(RawSql.Variant variant) { return variant.placeholders().stream().map(RawSql.Placeholder::name).toList(); }
    private static void assertSlots(RawSql.Variant sql) {
        for (var slot : sql.placeholders()) assertEquals("${" + slot.name() + "}", sql.text().substring(slot.start(), slot.end()));
    }
}
