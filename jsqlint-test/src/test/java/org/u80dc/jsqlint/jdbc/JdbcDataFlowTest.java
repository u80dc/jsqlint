package org.u80dc.jsqlint.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.*;

import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.AnalysisResult.Status.*;

/** 从真实字节码验证 SQL 与绑定参数的过程间溯源，不连接数据库、不运行被分析程序。 @author u80dc */
class JdbcDataFlowTest {
    @TempDir Path temporary;

    @Test
    void sqlReturnAndParametersReachMainWithoutMixingCallers() throws Exception {
        AnalysisResult result = analyze("""
                public class Sample {
                    public static void main(String[] args) throws Exception {
                        run(null, "users", args[0]); run(null, "roles", args[1]);
                    }
                    public static void run(java.sql.Connection c, String table, String id) throws Exception {
                        String sql = query(table, id); c.createStatement().execute(sql);
                    }
                    static String query(String table, String id) { return "SELECT * FROM " + table + " WHERE id=" + id; }
                }
                """);
        // 外部数组元素保留为符号槽位；模板视图与数据流共用结果，不再误报恢复失败。
        assertEquals(SUCCESS, result.status());
        var calls = calls(result, "execute");
        assertEquals(2, calls.size());
        Set<Set<String>> tables = new HashSet<>();
        for (var call : calls) {
            var statement = only(call.statements());
            var trace = statement.sql().orElseThrow();
            assertTrue(trace.complete());
            assertTrue(nodes(trace.root()).anyMatch(node -> node.kind() == ValueTrace.Kind.RETURN));
            assertTrue(nodes(trace.root()).anyMatch(node -> node.kind() == ValueTrace.Kind.ENTRY));
            assertEquals("main", only(call.callChain()).caller().orElseThrow().getName());
            Set<String> constants = constants(trace);
            constants.retainAll(Set.of("\"users\"", "\"roles\""));
            tables.add(constants);
        }
        assertEquals(Set.of(Set.of("\"users\""), Set.of("\"roles\"")), tables);
    }

    @Test
    void factoryReturnAliasesAndBinderHelperAreTrackedThroughVirtualCallerToMain() throws Exception {
        var result = analyze("""
                interface Runner { void run(java.sql.Connection c, String id) throws Exception; }
                public class Sample implements Runner {
                    public static void main(String[] args) throws Exception {
                        Runner runner = new Sample(); runner.run(null, args[0]);
                    }
                    public void run(java.sql.Connection c, String id) throws Exception {
                        var p = make(c, "SELECT * FROM users WHERE id=?");
                        java.sql.PreparedStatement alias = p;
                        bind(alias, id); execute(p);
                    }
                    static java.sql.PreparedStatement make(java.sql.Connection c, String sql) throws Exception { return c.prepareStatement(sql); }
                    static void bind(java.sql.PreparedStatement p, String id) throws Exception { p.setString(1, id); }
                    static void execute(java.sql.PreparedStatement p) throws Exception { p.executeQuery(); }
                }
                """);
        assertEquals(SUCCESS, result.status());
        var execution = only(calls(result, "executeQuery"));
        var statement = only(execution.statements());
        assertTrue(execution.complete());
        assertEquals("SELECT * FROM users WHERE id=?", SqlNodeRenderer.render(statement.template(), Map.of()).sql().orElseThrow());
        var binding = only(statement.bindings());
        assertEquals("1", binding.key());
        assertTrue(nodes(binding.value().root()).anyMatch(node -> node.kind() == ValueTrace.Kind.ENTRY));
        assertEquals(List.of("run", "execute"), execution.callChain().stream().map(call -> call.target().getName()).toList());
        assertEquals(List.of("run", "bind"), binding.value().callChain().stream().map(call -> call.target().getName()).toList());
        assertEquals(List.of("run", "make"), statement.sql().orElseThrow().callChain().stream().map(call -> call.target().getName()).toList());
    }

    @Test
    void differentFactoryInvocationsKeepObjectIdentityAndExecutionOrder() throws Exception {
        var result = analyze("""
                public class Sample {
                    static java.sql.PreparedStatement make(java.sql.Connection c, String sql) throws Exception { return c.prepareStatement(sql); }
                    public static void entry(java.sql.Connection c) throws Exception {
                        var first = make(c, "SELECT first WHERE id=?");
                        var second = make(c, "SELECT second WHERE id=?");
                        first.setInt(1, 11); second.setInt(1, 22);
                        first.executeQuery(); second.executeQuery();
                        first.setInt(1, 99);
                    }
                }
                """);
        assertEquals(SUCCESS, result.status());
        var executions = calls(result, "executeQuery");
        assertEquals(2, executions.size());
        Map<String, Set<String>> values = new HashMap<>();
        for (var execution : executions) {
            var statement = only(execution.statements());
            values.put(SqlNodeRenderer.render(statement.template(), Map.of()).sql().orElseThrow(), constants(only(statement.bindings()).value()));
        }
        assertEquals(Map.of("SELECT first WHERE id=?", Set.of("11"), "SELECT second WHERE id=?", Set.of("22")), values);
    }

    @Test
    void overwriteClearAndBranchesKeepReachingBindings() throws Exception {
        var result = analyze("""
                public class Sample {
                    public void entry(java.sql.Connection c, boolean flag) throws Exception {
                        var p = c.prepareStatement("UPDATE t SET a=?");
                        p.setString(1, "old"); p.setInt(2, 5); p.clearParameters();
                        if (flag) p.setString(1, "left"); else p.setString(1, "right");
                        p.executeUpdate(); p.setString(1, "too late");
                    }
                }
                """);
        assertEquals(SUCCESS, result.status());
        var execution = only(calls(result, "executeUpdate"));
        assertEquals(2, execution.statements().size());
        Set<Set<String>> values = new HashSet<>();
        for (var statement : execution.statements()) {
            var binding = only(statement.bindings()); assertEquals("1", binding.key()); values.add(constants(binding.value()));
        }
        assertEquals(Set.of(Set.of("\"left\""), Set.of("\"right\"")), values);
    }

    @Test
    void mayAliasSetterKeepsEarlierBindingAsCandidate() throws Exception {
        var result = analyze("""
                public class Sample {
                    public void entry(java.sql.Connection c, boolean flag) throws Exception {
                        var p = c.prepareStatement("SELECT ?"); var other = c.prepareStatement("SELECT other");
                        p.setInt(1, 11);
                        java.sql.PreparedStatement alias = flag ? p : other;
                        alias.setInt(1, 22); p.executeQuery();
                    }
                }
                """);
        assertEquals(PARTIAL, result.status());
        var execution = only(calls(result, "executeQuery"));
        Set<String> values = execution.statements().stream().flatMap(stmt -> stmt.bindings().stream())
                .flatMap(binding -> constants(binding.value()).stream()).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("11", "22"), values);
        assertTrue(execution.statements().stream().allMatch(stmt -> !stmt.issues().isEmpty()));
    }

    @Test
    void preparedBatchUsesAddBatchSnapshotsAndClearBatch() throws Exception {
        var result = analyze("""
                public class Sample {
                    public void entry(java.sql.Connection c) throws Exception {
                        var p = c.prepareStatement("INSERT INTO t VALUES (?)");
                        p.setInt(1, 1); p.addBatch(); p.clearBatch();
                        p.setInt(1, 11); p.addBatch(); p.setInt(1, 22); p.addBatch();
                        p.setInt(1, 33); p.executeBatch();
                    }
                }
                """);
        assertEquals(SUCCESS, result.status());
        var batch = only(calls(result, "executeBatch"));
        assertEquals(2, batch.statements().size());
        assertEquals(List.of(Set.of("11"), Set.of("22")), batch.statements().stream().map(stmt -> constants(only(stmt.bindings()).value())).toList());
        assertTrue(batch.statements().stream().allMatch(stmt -> stmt.batchEntry().isPresent()));
    }

    @Test
    void plainStatementBatchRetainsEverySqlAndClearBatchRemovesEarlierEntries() throws Exception {
        var result = analyze("""
                public class Sample {
                    public void entry(java.sql.Connection c) throws Exception {
                        var s = c.createStatement();
                        s.addBatch("SELECT removed"); s.clearBatch();
                        s.addBatch("SELECT 1"); s.addBatch("SELECT 2"); s.executeLargeBatch();
                    }
                }
                """);
        assertEquals(SUCCESS, result.status());
        var batch = only(calls(result, "executeLargeBatch"));
        assertEquals(List.of("SELECT 1", "SELECT 2"), batch.statements().stream()
                .map(stmt -> SqlNodeRenderer.render(stmt.template(), Map.of()).sql().orElseThrow()).toList());
    }

    @Test
    void callableNamedBindingsSetNullAndDynamicIndexRetainCorrectValues() throws Exception {
        var result = analyze("""
                public class Sample {
                    public void entry(java.sql.Connection c, String name, int index) throws Exception {
                        var p = c.prepareCall("{call p(?,?,?)}");
                        p.setString("name", name); p.setNull(2, java.sql.Types.INTEGER);
                        p.registerOutParameter(3, java.sql.Types.INTEGER); p.setInt(index, 44); p.execute();
                    }
                }
                """);
        assertEquals(PARTIAL, result.status(), "动态参数下标存在歧义，不能声称关联完全准确");
        var statement = only(only(calls(result, "execute")).statements());
        assertEquals(3, statement.bindings().size());
        var bindings = statement.bindings().stream().collect(java.util.stream.Collectors.toMap(SqlExtraction.Binding::key, value -> value));
        assertEquals(Set.of("null"), constants(bindings.get("2").value()));
        assertTrue(nodes(bindings.get(":name").value().root()).anyMatch(node -> node.kind() == ValueTrace.Kind.NO_CALLER));
        assertTrue(bindings.keySet().stream().anyMatch(key -> key.startsWith("unknown:")));
        assertFalse(bindings.containsKey("3"));
    }

    @Test
    void unknownReceiverKeepsBindingsAndMarksUnresolvedSql() throws Exception {
        var result = analyze("""
                public class Sample {
                    public void entry(java.sql.PreparedStatement p, String value) throws Exception {
                        p.setString(1, value); p.executeQuery();
                    }
                }
                """);
        assertEquals(PARTIAL, result.status());
        var statement = only(only(calls(result, "executeQuery")).statements());
        assertTrue(statement.sql().isEmpty());
        assertFalse(statement.issues().isEmpty());
        assertEquals("1", only(statement.bindings()).key());
    }

    @Test
    void statementStoredInFieldStillExposesCandidateFactoryAndBindings() throws Exception {
        var result = analyze("""
                public class Sample {
                    private java.sql.PreparedStatement statement;
                    public static void main(String[] args) throws Exception {
                        var repository = new Sample(); repository.init(null); repository.query(args[0]);
                    }
                    void init(java.sql.Connection c) throws Exception { statement = c.prepareStatement("SELECT ?"); }
                    void query(String value) throws Exception { statement.setString(1, value); statement.executeQuery(); }
                }
                """);
        assertEquals(PARTIAL, result.status(), "字段候选来源尚不具备精确堆别名保证");
        var execution = only(calls(result, "executeQuery"));
        assertTrue(execution.statements().stream().anyMatch(stmt -> stmt.sql().isPresent()
                && constants(stmt.sql().get()).contains("\"SELECT ?\"")));
        assertTrue(execution.statements().stream().anyMatch(stmt -> stmt.bindings().stream().anyMatch(binding -> binding.key().equals("1"))));
        assertTrue(execution.statements().stream().allMatch(stmt -> !stmt.issues().isEmpty()));
    }

    @Test
    void recursionAndFieldWritesAreRetainedAsExplicitIncompleteTraces() throws Exception {
        var result = analyze("""
                public class Sample {
                    static String value;
                    public static void main(String[] args) throws Exception { value = args[0]; recurse(null, 3); }
                    static void recurse(java.sql.Connection c, int n) throws Exception {
                        if (n > 0) recurse(c, n - 1);
                        var p = c.prepareStatement("SELECT ?"); p.setString(1, value); p.execute();
                    }
                }
                """);
        assertEquals(PARTIAL, result.status());
        var calls = calls(result, "execute");
        assertFalse(calls.isEmpty());
        assertTrue(calls.stream().anyMatch(call -> !call.issues().isEmpty()));
        assertTrue(calls.stream().flatMap(call -> call.statements().stream()).flatMap(stmt -> stmt.bindings().stream())
                .flatMap(binding -> nodes(binding.value().root())).anyMatch(node -> node.kind() == ValueTrace.Kind.FIELD));
        assertTrue(calls.stream().flatMap(call -> call.statements().stream()).flatMap(stmt -> stmt.bindings().stream())
                .flatMap(binding -> nodes(binding.value().root())).anyMatch(node -> node.kind() == ValueTrace.Kind.ENTRY));
    }

    @Test
    void arrayElementBindingReachesStoredInput() throws Exception {
        var result = analyze("""
                public class Sample {
                    public void entry(java.sql.Connection c, String input) throws Exception {
                        String[] values = new String[1]; values[0] = input;
                        var p = c.prepareStatement("SELECT ?");
                        p.setString(1, values[0]); p.executeQuery();
                    }
                }
                """);
        var value = only(only(only(calls(result, "executeQuery")).statements()).bindings()).value();
        assertTrue(nodes(value.root()).anyMatch(node -> node.kind() == ValueTrace.Kind.NO_CALLER
                && node.variable().expression().contains("@parameter1")), "数组读取必须追踪到写入的入口参数");
        assertTrue(value.complete());
        assertEquals(SUCCESS, result.status());
    }

    @Test
    void arrayAliasWriteInHelperReplacesOnlyMatchingElementBeforeRead() throws Exception {
        var result = analyze("""
                public class Sample {
                    public void entry(java.sql.Connection c, String input) throws Exception {
                        String[] values = new String[2]; values[0] = "old"; values[1] = "other element";
                        write(values, input);
                        var p = c.prepareStatement("SELECT ?");
                        p.setString(1, values[0]); p.executeQuery(); values[0] = "too late";
                    }
                    static void write(String[] array, String input) { String[] alias = array; alias[0] = input; }
                }
                """);
        var value = only(only(only(calls(result, "executeQuery")).statements()).bindings()).value();
        assertTrue(nodes(value.root()).anyMatch(node -> node.kind() == ValueTrace.Kind.NO_CALLER
                && node.variable().expression().contains("@parameter1")));
        assertTrue(nodes(value.root()).anyMatch(node -> node.variable().method().getName().equals("write")));
        assertTrue(Collections.disjoint(constants(value), Set.of("\"old\"", "\"other element\"", "\"too late\"")));
        assertTrue(value.complete());
        assertEquals(SUCCESS, result.status());
    }

    @Test
    void uncertainArrayWriteRetainsEarlierValueForBothAliasAndIndex() throws Exception {
        for (String write : List.of("String[] alias = flag ? values : other; alias[0] = input;", "values[index] = input;")) {
            var result = analyze("""
                    public class Sample {
                        public void entry(java.sql.Connection c, String input, boolean flag, int index) throws Exception {
                            String[] values = new String[2]; String[] other = new String[2]; values[0] = "earlier";
                            %s
                            var p = c.prepareStatement("SELECT ?");
                            p.setString(1, values[0]); p.executeQuery();
                        }
                    }
                    """.formatted(write));
            var value = only(only(only(calls(result, "executeQuery")).statements()).bindings()).value();
            assertTrue(constants(value).contains("\"earlier\""), write);
            assertTrue(nodes(value.root()).anyMatch(node -> node.kind() == ValueTrace.Kind.NO_CALLER
                    && node.variable().expression().contains("@parameter1")), write);
            assertFalse(value.complete(), write);
            assertEquals(PARTIAL, result.status());
        }
    }

    @Test
    void unwrittenArrayElementRetainsJavaDefaultValue() throws Exception {
        var result = analyze("""
                public class Sample {
                    public void entry(java.sql.Connection c) throws Exception {
                        String[] values = new String[2]; values[1] = "other element";
                        var p = c.prepareStatement("SELECT ?");
                        p.setString(1, values[0]); p.executeQuery();
                    }
                }
                """);
        var value = only(only(only(calls(result, "executeQuery")).statements()).bindings()).value();
        assertTrue(constants(value).contains("null"));
        assertFalse(constants(value).contains("\"other element\""));
        assertTrue(value.complete());
    }

    @Test
    void externalArrayMutationThroughObjectRetainsKnownWriteAsIncomplete() throws Exception {
        Path dependency = compile("ArrayHelper", """
                package external;
                public class ArrayHelper {
                    public static void mutate(Object value) { ((String[]) value)[0] = "external"; }
                }
                """, null);
        var result = analyze("""
                public class Sample {
                    public void entry(java.sql.Connection c) throws Exception {
                        String[] values = new String[1]; values[0] = "before";
                        Object alias = values; external.ArrayHelper.mutate(alias);
                        var p = c.prepareStatement("SELECT ?");
                        p.setString(1, values[0]); p.executeQuery();
                    }
                }
                """, dependency);
        var value = only(only(only(calls(result, "executeQuery")).statements()).bindings()).value();
        assertTrue(constants(value).contains("\"before\""));
        assertFalse(value.complete());
        assertEquals(PARTIAL, result.status());
    }

    @Test
    void mayAliasClearBatchRetainsPossiblyUnclearedSql() throws Exception {
        var result = analyze("""
                public class Sample {
                    public void entry(java.sql.Connection c, boolean flag) throws Exception {
                        var p = c.prepareStatement("SELECT 1");
                        var other = c.prepareStatement("SELECT 2");
                        p.addBatch();
                        java.sql.PreparedStatement alias = flag ? p : other;
                        alias.clearBatch(); p.executeBatch();
                    }
                }
                """);
        var execution = only(calls(result, "executeBatch"));
        assertEquals(Set.of("SELECT 1"), renderedSql(execution));
        assertEquals(PARTIAL, result.status());
        assertFalse(execution.complete(), "可能清理不能冒充确定清理或确定保留");
    }

    @Test
    void definiteClearBatchCanProduceCompleteEmptyBatch() throws Exception {
        var result = analyze("""
                public class Sample {
                    public void entry(java.sql.Connection c) throws Exception {
                        var s = c.createStatement(); s.addBatch("SELECT removed");
                        java.sql.Statement alias = s; alias.clearBatch(); s.executeBatch();
                    }
                }
                """);
        var execution = only(calls(result, "executeBatch"));
        assertTrue(execution.statements().isEmpty());
        assertTrue(execution.complete());
        assertEquals(SUCCESS, result.status());
    }

    @Test
    void mayAliasAddBatchRetainsCandidateWithoutClaimingDefiniteAddition() throws Exception {
        var result = analyze("""
                public class Sample {
                    public void entry(java.sql.Connection c, boolean flag) throws Exception {
                        var s = c.createStatement(); var other = c.createStatement();
                        java.sql.Statement alias = flag ? s : other;
                        alias.addBatch("SELECT candidate"); s.executeBatch();
                    }
                }
                """);
        var execution = only(calls(result, "executeBatch"));
        assertEquals(Set.of("SELECT candidate"), renderedSql(execution));
        assertFalse(execution.complete());
        assertEquals(PARTIAL, result.status());
    }

    @Test
    void ambiguousEmptyBatchRetainsReceiverDiagnostic() throws Exception {
        var result = analyze("""
                public class Sample {
                    public void entry(java.sql.Connection c, boolean flag) throws Exception {
                        var s = c.createStatement(); var other = c.createStatement();
                        java.sql.Statement alias = flag ? s : other; alias.executeBatch();
                    }
                }
                """);
        var execution = only(calls(result, "executeBatch"));
        assertFalse(execution.complete());
        assertTrue(execution.statements().stream().anyMatch(statement -> statement.sql().isEmpty() && !statement.issues().isEmpty()));
    }

    @Test
    void virtualReturnRetainsExternalAlternativeAlongsideApplicationOverride() throws Exception {
        Path dependency = compile("Base", """
                package external;
                public class Base { public String source() { return System.getProperty("data"); } }
                """, null);
        var result = analyze("""
                class Derived extends external.Base {
                    @Override public String source() { return "app-only"; }
                }
                public class Sample {
                    public void entry(java.sql.Connection c, external.Base source) throws Exception {
                        var p = c.prepareStatement("SELECT ?");
                        p.setString(1, source.source()); p.executeQuery();
                    }
                }
                """, dependency);
        var value = only(only(only(calls(result, "executeQuery")).statements()).bindings()).value();
        assertTrue(constants(value).contains("\"app-only\""));
        assertTrue(nodes(value.root()).anyMatch(node -> node.kind() == ValueTrace.Kind.EXTERNAL_CALL),
                "存在项目实现时，也必须保留依赖库实现这一未知分支");
        assertFalse(value.complete());
        assertEquals(PARTIAL, result.status());
    }

    @Test
    void mixedVirtualBindingMutationPreservesExternalAndApplicationHistories() throws Exception {
        Path dependency = compile("Base", """
                package external;
                public class Base { public void bind(java.sql.PreparedStatement p) throws Exception {} }
                """, null);
        var result = analyze("""
                class Derived extends external.Base {
                    @Override public void bind(java.sql.PreparedStatement p) throws Exception { p.setInt(1, 22); }
                }
                public class Sample {
                    public void entry(java.sql.Connection c, external.Base helper) throws Exception {
                        var p = c.prepareStatement("SELECT ?"); p.setInt(1, 11);
                        helper.bind(p); p.executeQuery();
                    }
                }
                """, dependency);
        var execution = only(calls(result, "executeQuery"));
        Set<String> values = execution.statements().stream().flatMap(statement -> statement.bindings().stream())
                .flatMap(binding -> constants(binding.value()).stream()).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("11", "22"), values, "外部分派分支不能被项目实现的确定覆盖吞掉");
        assertFalse(execution.complete());
        assertEquals(PARTIAL, result.status());
    }

    @Test
    void mixedVirtualFactoryPreservesUnknownReceiverAlternative() throws Exception {
        Path dependency = compile("Base", """
                package external;
                public class Base {
                    public java.sql.PreparedStatement make(java.sql.Connection c) throws Exception {
                        return c.prepareStatement("SELECT external");
                    }
                }
                """, null);
        var result = analyze("""
                class Derived extends external.Base {
                    @Override public java.sql.PreparedStatement make(java.sql.Connection c) throws Exception {
                        return c.prepareStatement("SELECT application");
                    }
                }
                public class Sample {
                    public void entry(java.sql.Connection c, external.Base factory) throws Exception {
                        var p = factory.make(c); p.executeQuery();
                    }
                }
                """, dependency);
        var execution = only(calls(result, "executeQuery"));
        assertTrue(renderedSql(execution).contains("SELECT application"));
        assertTrue(execution.statements().stream().anyMatch(statement -> statement.sql().isEmpty()),
                "对象来源也必须保留外部工厂返回的未知 Statement");
        assertFalse(execution.complete());
        assertEquals(PARTIAL, result.status());
    }

    @Test
    void externalBatchMutationMarksKnownSqlAsCandidate() throws Exception {
        Path dependency = compile("BatchHelper", """
                package external;
                public class BatchHelper {
                    public static void mutate(java.sql.Statement s) throws Exception {
                        s.clearBatch(); s.addBatch("SELECT modified");
                    }
                }
                """, null);
        var result = analyze("""
                public class Sample {
                    public void entry(java.sql.Connection c) throws Exception {
                        var s = c.createStatement(); s.addBatch("SELECT before");
                        external.BatchHelper.mutate(s); s.executeBatch();
                    }
                }
                """, dependency);
        var execution = only(calls(result, "executeBatch"));
        assertEquals(Set.of("SELECT before"), renderedSql(execution), "保留已知 SQL 供调用方检查");
        assertFalse(execution.complete(), "外部方法可能替换整个批次，不能声称候选就是实际批次");
        assertTrue(execution.statements().stream().anyMatch(statement -> !statement.issues().isEmpty()));
        assertEquals(PARTIAL, result.status());
    }

    @Test
    void externalBatchMutationWithoutKnownEntriesIsUnknownInsteadOfEmpty() throws Exception {
        Path dependency = compile("BatchHelper", """
                package external;
                public class BatchHelper {
                    public static void mutate(java.sql.Statement s) throws Exception { s.addBatch("SELECT external"); }
                }
                """, null);
        var result = analyze("""
                public class Sample {
                    public void entry(java.sql.Connection c) throws Exception {
                        var s = c.createStatement(); external.BatchHelper.mutate(s); s.executeBatch();
                    }
                }
                """, dependency);
        var execution = only(calls(result, "executeBatch"));
        assertFalse(execution.complete());
        assertTrue(execution.statements().stream().anyMatch(statement -> statement.sql().isEmpty() && !statement.issues().isEmpty()));
        assertEquals(PARTIAL, result.status());
    }

    @Test
    void dependencyInterfaceAndAbstractClassKeepExternalImplementations() throws Exception {
        for (boolean abstractClass : List.of(false, true)) {
            String declaration = abstractClass ? "public abstract class Api { public abstract String source();"
                    : "public interface Api { String source();";
            String relation = abstractClass ? "extends" : "implements";
            Path dependency = compile("Api", "package external; " + declaration
                    + " class LibraryImpl " + relation + " Api { public String source() { return System.getProperty(\"external\"); } } }", null);
            var result = analyze("""
                    class AppImpl %s external.Api { public String source() { return "app-only"; } }
                    public class Sample {
                        public void entry(java.sql.Connection c, external.Api api) throws Exception {
                            var p = c.prepareStatement("SELECT ?"); p.setString(1, api.source()); p.executeQuery();
                        }
                    }
                    """.formatted(relation), dependency);
            var value = only(only(only(calls(result, "executeQuery")).statements()).bindings()).value();
            assertTrue(constants(value).contains("\"app-only\""));
            assertTrue(nodes(value.root()).anyMatch(node -> node.kind() == ValueTrace.Kind.EXTERNAL_CALL));
            assertFalse(value.complete());
            assertEquals(PARTIAL, result.status());
        }
    }

    @Test
    void stringCopyConstructorRetainsInputAcrossHelperReturn() throws Exception {
        var result = analyze("""
                public class Sample {
                    static String copy(String input) { return new String(input); }
                    public void entry(java.sql.Connection c, String input) throws Exception {
                        var p = c.prepareStatement("SELECT ?"); p.setString(1, copy(input)); p.executeQuery();
                    }
                }
                """);
        var value = only(only(only(calls(result, "executeQuery")).statements()).bindings()).value();
        assertTrue(nodes(value.root()).anyMatch(node -> node.kind() == ValueTrace.Kind.NO_CALLER
                && node.variable().expression().contains("@parameter1")));
        assertTrue(value.complete());
    }

    @Test
    void unmodeledObjectContentCannotBeCompleteAtAllocation() throws Exception {
        var result = analyze("""
                public class Sample {
                    static class Payload { final String value; Payload(String value) { this.value = value; } }
                    public void entry(java.sql.Connection c, String input) throws Exception {
                        var p = c.prepareStatement("SELECT ?"); p.setObject(1, new Payload(input)); p.executeQuery();
                    }
                }
                """);
        var value = only(only(only(calls(result, "executeQuery")).statements()).bindings()).value();
        assertFalse(value.complete());
        assertTrue(nodes(value.root()).anyMatch(node -> node.kind() == ValueTrace.Kind.NO_CALLER
                && node.variable().expression().contains("@parameter1")));
        assertEquals(PARTIAL, result.status());
    }

    @Test
    void arrayEscapingThroughHolderRetainsUnknownExternalModification() throws Exception {
        Path dependency = compile("Helper", """
                package external;
                public class Helper {
                    public static class Holder { public String[] values; }
                    public static void mutate(Holder h) { h.values[0] = "after"; }
                }
                """, null);
        for (String write : List.of("", "values[0] = \"before\";")) {
            var result = analyze("""
                    public class Sample {
                        public void entry(java.sql.Connection c) throws Exception {
                            String[] values = {"before"};
                            var h = new external.Helper.Holder(); h.values = values; %s external.Helper.mutate(h);
                            var p = c.prepareStatement("SELECT ?"); p.setString(1, values[0]); p.executeQuery();
                        }
                    }
                    """.formatted(write), dependency);
            var value = only(only(only(calls(result, "executeQuery")).statements()).bindings()).value();
            assertTrue(constants(value).contains("\"before\""));
            assertFalse(value.complete());
            assertEquals(PARTIAL, result.status());
        }
    }

    @Test
    void successfulBatchExecutionSeparatesSubsequentBatch() throws Exception {
        for (String execute : List.of("executeBatch", "executeLargeBatch")) {
            var result = analyze("""
                    public class Sample {
                        public void entry(java.sql.Connection c) throws Exception {
                            var s = c.createStatement(); s.addBatch("INSERT INTO t VALUES(1)"); s.%s();
                            s.addBatch("INSERT INTO t VALUES(2)"); s.%s();
                        }
                    }
                    """.formatted(execute, execute));
            var executions = calls(result, execute);
            assertEquals(2, executions.size());
            assertEquals(Set.of("INSERT INTO t VALUES(1)"), renderedSql(executions.getFirst()));
            assertEquals(Set.of("INSERT INTO t VALUES(2)"), renderedSql(executions.getLast()));
            assertTrue(executions.getLast().complete());
        }
    }

    @Test
    void arraySqlRecoveryAndTemplateShareSuccessfulResults() throws Exception {
        var result = analyze("""
                public class Sample {
                    public void entry(java.sql.Connection c) throws Exception {
                        String[] sql = {"SELECT 1"}; c.createStatement().executeQuery(sql[0]);
                        c.createStatement().executeQuery("SELECT 2");
                    }
                }
                """);
        var executions = calls(result, "executeQuery");
        var first = only(executions.getFirst().statements());
        assertTrue(first.sql().orElseThrow().complete());
        assertTrue(first.complete());
        assertTrue(first.templateIssues().isEmpty());
        assertTrue(first.sourcesComplete());
        assertTrue(first.templateComplete());
        assertEquals(Set.of("SELECT 1"), renderedSql(executions.getFirst()));
        assertEquals("SELECT 1", only(first.rawSql().variants()).text());
        assertEquals(Set.of("SELECT 2"), renderedSql(executions.getLast()));
        assertTrue(only(executions.getLast().statements()).templateComplete());
        assertTrue(executions.getLast().complete(), "前一条 SQL 的诊断不能污染后续结果");
        assertEquals(SUCCESS, result.status());
    }

    @Test
    void emptyStringConstructorHasKnownEmptyContent() throws Exception {
        var result = analyze("""
                public class Sample {
                    public void entry(java.sql.Connection c) throws Exception {
                        var p = c.prepareStatement("SELECT ?"); p.setString(1, new String()); p.executeQuery();
                    }
                }
                """);
        var value = only(only(only(calls(result, "executeQuery")).statements()).bindings()).value();
        assertTrue(constants(value).contains("\"\""));
        assertTrue(value.complete());
    }

    @Test
    void nestedHolderReceiverAndStaticFieldEscapeAreConservative() throws Exception {
        Path dependency = compile("Helper", """
                package external;
                public class Helper {
                    public static class Holder { public String[] values; }
                    public static class Outer { public Holder holder; public void mutate() { holder.values[0] = "after"; } }
                    public static String[] shared;
                    public static void mutateStatic() { shared[0] = "after"; }
                }
                """, null);
        for (String escape : List.of("""
                var h = new external.Helper.Holder(); h.values = values;
                var outer = new external.Helper.Outer(); outer.holder = h; outer.mutate();
                """, """
                var outer = new external.Helper.Outer(); var h = new external.Helper.Holder();
                outer.holder = h; h.values = values; outer.mutate();
                """, "external.Helper.shared = values; external.Helper.mutateStatic();")) {
            var result = analyze("""
                    public class Sample {
                        public void entry(java.sql.Connection c) throws Exception {
                            String[] values = {"before"}; %s
                            var p = c.prepareStatement("SELECT ?"); p.setString(1, values[0]); p.executeQuery();
                        }
                    }
                    """.formatted(escape), dependency);
            var value = only(only(only(calls(result, "executeQuery")).statements()).bindings()).value();
            assertTrue(constants(value).contains("\"before\""));
            assertFalse(value.complete());
            assertEquals(PARTIAL, result.status());
        }
    }

    @Test
    void heapStoreAfterExternalCallDoesNotRetroactivelyExposeArray() throws Exception {
        Path dependency = compile("Helper", """
                package external;
                public class Helper {
                    public static class Holder { public String[] values; }
                    public static void mutate(Holder h) { if (h.values != null) h.values[0] = "after"; }
                }
                """, null);
        var result = analyze("""
                public class Sample {
                    public void entry(java.sql.Connection c) throws Exception {
                        String[] values = {"before"}; var h = new external.Helper.Holder();
                        external.Helper.mutate(h); h.values = values;
                        var p = c.prepareStatement("SELECT ?"); p.setString(1, values[0]); p.executeQuery();
                    }
                }
                """, dependency);
        var value = only(only(only(calls(result, "executeQuery")).statements()).bindings()).value();
        assertTrue(value.complete());
        assertEquals(SUCCESS, result.status());
    }

    @Test
    void possibleAliasBatchExecutionRetainsUnsubmittedAlternative() throws Exception {
        var result = analyze("""
                public class Sample {
                    public void entry(java.sql.Connection c, boolean flag) throws Exception {
                        var s = c.createStatement(); var other = c.createStatement(); s.addBatch("SELECT earlier");
                        java.sql.Statement alias = flag ? s : other; alias.executeBatch();
                        s.addBatch("SELECT later"); s.executeBatch();
                    }
                }
                """);
        var execution = calls(result, "executeBatch").getLast();
        assertEquals(Set.of("SELECT earlier", "SELECT later"), renderedSql(execution));
        assertFalse(execution.complete());
    }

    @Test
    void exceptionalBatchExecutionDoesNotClaimHistoryWasCleared() throws Exception {
        var result = analyze("""
                public class Sample {
                    public void entry(java.sql.Connection c) throws Exception {
                        var s = c.createStatement(); s.addBatch("INSERT INTO t VALUES(1)");
                        try { s.executeBatch(); } catch (java.sql.SQLException e) { }
                        s.addBatch("INSERT INTO t VALUES(2)"); s.executeBatch();
                    }
                }
                """);
        var execution = calls(result, "executeBatch").getLast();
        assertTrue(renderedSql(execution).contains("INSERT INTO t VALUES(1)"), "异常路径不能直接按成功提交清除");
        assertTrue(renderedSql(execution).contains("INSERT INTO t VALUES(2)"));
        assertFalse(execution.complete());
    }

    @Test
    void preparedBatchExecutionClearsEntriesButKeepsParameterBindings() throws Exception {
        var result = analyze("""
                public class Sample {
                    public void entry(java.sql.Connection c) throws Exception {
                        var p = c.prepareStatement("INSERT INTO t VALUES(?)"); p.setString(1, "retained");
                        p.addBatch(); p.executeBatch(); p.addBatch(); p.executeBatch();
                    }
                }
                """);
        var statement = only(calls(result, "executeBatch").getLast().statements());
        assertEquals(Set.of("\"retained\""), constants(only(statement.bindings()).value()));
        assertTrue(statement.complete());
    }

    private static Set<String> renderedSql(SqlExtraction extraction) {
        return extraction.statements().stream().flatMap(statement -> SqlNodeRenderer.render(statement.template(), Map.of()).sql().stream())
                .collect(java.util.stream.Collectors.toSet());
    }

    private Path compile(String name, String source, Path dependency) throws Exception {
        Path root = Files.createDirectories(temporary.resolve(UUID.randomUUID().toString()));
        Path java = Files.writeString(root.resolve(name + ".java"), source);
        Path classes = Files.createDirectory(root.resolve("classes"));
        var args = new ArrayList<>(List.of("-proc:none", "-g", "-d", classes.toString()));
        if (dependency != null) args.addAll(List.of("-classpath", dependency.toString()));
        args.add(java.toString());
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        return classes;
    }

    private AnalysisResult analyze(String source) throws Exception { return analyze(source, null); }

    private AnalysisResult analyze(String source, Path dependency) throws Exception {
        Path classes = compile("Sample", source, dependency);
        List<SqlExtraction> events = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        var listener = new AnalysisListener() {
            public void onSqlExtraction(SqlExtraction value) { events.add(value); }
            public void onDiagnostic(String code, String message) { diagnostics.add(code + ": " + message); }
        };
        var result = new Analyzer(AnalyzerConfig.defaults().withMaxTraceDepth(64), List.of(new JdbcAnalysisModule()), List.of())
                .analyze(classes, dependency, AnalysisOptions.defaults().withListener(listener));
        assertNotEquals(FAILED, result.status(), diagnostics.toString());
        assertEquals(events, result.extractions());
        assertThrows(UnsupportedOperationException.class, () -> result.extractions().clear());
        return result;
    }
    private static List<SqlExtraction> calls(AnalysisResult result, String name) {
        return result.extractions().stream().filter(value -> value.callSite().target().getName().equals(name)).toList();
    }
    private static <T> T only(List<T> values) { assertEquals(1, values.size(), values.toString()); return values.getFirst(); }
    private static Stream<ValueTrace.Node> nodes(ValueTrace.Node node) {
        return Stream.concat(Stream.of(node), node.inputs().stream().flatMap(JdbcDataFlowTest::nodes));
    }
    private static Set<String> constants(ValueTrace trace) {
        return nodes(trace.root()).filter(node -> node.kind() == ValueTrace.Kind.CONSTANT)
                .map(node -> node.variable().expression()).collect(java.util.stream.Collectors.toSet());
    }
}
