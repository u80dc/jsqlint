package org.u80dc.jsqlint.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.core.parameter.*;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** 通过真实字节码验证参数初始值、跨方法重演与候选传播链；不连接数据库。 @author u80dc */
class JdbcParameterInferenceTest {
    @TempDir Path temporary;

    @Test
    void literalsReplayArithmeticStringOperationsAndNullBinding() throws Exception {
        var result = analyze("""
                public class Sample {
                    public static void main(String[] args) throws Exception { run(null, 21, "  Ab'Cd  "); }
                    static void run(java.sql.Connection c, int id, String name) throws Exception {
                        var p = c.prepareStatement("SELECT ?,?,?,?");
                        int doubled = id * 2 + 1;
                        String cleaned = transform(name);
                        p.setInt(1, doubled); p.setString(2, cleaned); p.setBoolean(3, true);
                        p.setNull(4, java.sql.Types.VARCHAR); p.executeQuery();
                    }
                    static String transform(String value) { return value.trim().replace("'", "''").substring(1); }
                }
                """);
        var bindings = bindings(result);
        assertEquals(43, known(only(bindings.get("1").inference().possibleValues())));
        assertEquals("doubled", bindings.get("1").inference().name());
        assertEquals("int", bindings.get("1").inference().type());
        assertEquals("b''Cd", known(only(bindings.get("2").inference().possibleValues())));
        assertEquals(true, known(only(bindings.get("3").inference().possibleValues())));
        assertNull(known(only(bindings.get("4").inference().possibleValues())));
        assertTrue(bindings.values().stream().allMatch(b -> b.inference().complete()), bindings.toString());
        assertTrue(bindings.values().stream().flatMap(b -> b.inference().possibleValues().stream()).noneMatch(InferredParameterValue::sampled));
    }

    @Test
    void randomExternalInputIsReplayedAndReusedForRepeatedReferences() throws Exception {
        var result = analyze("""
                public class Sample {
                    static void run(java.sql.Connection c, int id, String name) throws Exception {
                        var p = c.prepareStatement("SELECT ?,?");
                        int doubled = id + id; String quoted = "[" + name.trim() + "]";
                        p.setInt(1, doubled); p.setString(2, quoted); p.executeQuery();
                    }
                }
                """);
        var bindings = bindings(result);
        var number = only(bindings.get("1").inference().possibleValues());
        assertTrue(number.complete()); assertTrue(number.sampled());
        var initial = only(number.initialValues().stream().filter(InferredParameterValue.InitialValue::generated).toList());
        int id = assertInstanceOf(Integer.class, ((SqlValue.Known) initial.value()).value());
        assertEquals(id + id, known(number));
        var string = only(bindings.get("2").inference().possibleValues());
        var name = only(string.initialValues().stream().filter(InferredParameterValue.InitialValue::generated).toList());
        assertEquals("[" + ((SqlValue.Known) name.value()).value().toString().trim() + "]", known(string));
    }

    @Test
    void branchValuesKeepSeparateSelectedPropagationTreesEvenWhenEqual() throws Exception {
        var result = analyze("""
                public class Sample {
                    static void run(java.sql.Connection c, boolean flag) throws Exception {
                        var p = c.prepareStatement("SELECT ?");
                        String value = flag ? left() : right(); p.setString(1, value); p.executeQuery();
                    }
                    static String left() { return "same".trim(); }
                    static String right() { return "same"; }
                }
                """);
        var inference = bindings(result).get("1").inference();
        assertTrue(inference.complete(), inference.issues().toString());
        assertEquals(2, inference.possibleValues().size());
        assertEquals(List.of("same", "same"), inference.possibleValues().stream().map(JdbcParameterInferenceTest::known).toList());
        assertEquals(Set.of(Set.of("run", "left"), Set.of("run", "right")), inference.possibleValues().stream()
                .map(v -> nodes(v.propagationPath().root()).map(n -> n.variable().method().getName()).collect(Collectors.toSet()))
                .collect(Collectors.toSet()));
    }

    @Test
    void repeatedBranchValueDoesNotCombineDifferentAssignments() throws Exception {
        var result = analyze("""
                public class Sample {
                    static void run(java.sql.Connection c, boolean flag) throws Exception {
                        var p = c.prepareStatement("SELECT ?");
                        int value = flag ? 10 : 20; int difference = value - value;
                        p.setInt(1, difference); p.executeQuery();
                    }
                }
                """);
        var inference = bindings(result).get("1").inference();
        assertTrue(inference.complete());
        assertEquals(List.of(0, 0), inference.possibleValues().stream().map(JdbcParameterInferenceTest::known).toList());
    }

    @Test
    void castsOverflowDivisionAndParsingUseJavaTypes() throws Exception {
        var result = analyze("""
                public class Sample {
                    public static void main(String[] args) throws Exception { run(null, 2147483647, 259, " 42 "); }
                    static void run(java.sql.Connection c, int large, int small, String digits) throws Exception {
                        var p = c.prepareStatement("SELECT ?,?,?,?");
                        p.setInt(1, large + 1); p.setByte(2, (byte) small); p.setInt(3, -small / 2);
                        p.setInt(4, Integer.parseInt(digits.trim())); p.executeQuery();
                    }
                }
                """);
        var bindings = bindings(result);
        assertEquals(Integer.MIN_VALUE, known(only(bindings.get("1").inference().possibleValues())));
        assertEquals((byte) 3, known(only(bindings.get("2").inference().possibleValues())));
        assertEquals(-129, known(only(bindings.get("3").inference().possibleValues())));
        assertEquals(42, known(only(bindings.get("4").inference().possibleValues())));
    }

    @Test
    void exceptionAndUnknownLibraryCallRemainUnknownWithoutLosingOtherValues() throws Exception {
        var result = analyze("""
                public class Sample {
                    public static void main(String[] args) throws Exception { run(null, 0); }
                    static void run(java.sql.Connection c, int zero) throws Exception {
                        var p = c.prepareStatement("SELECT ?,?,?");
                        p.setInt(1, 1 / zero); p.setString(2, System.getProperty("custom")); p.setString(3, "kept");
                        p.executeQuery();
                    }
                }
                """);
        var bindings = bindings(result);
        for (String key : List.of("1", "2")) {
            var inference = bindings.get(key).inference();
            assertFalse(inference.complete());
            assertInstanceOf(SqlValue.Unknown.class, only(inference.possibleValues()).value());
            assertFalse(only(inference.possibleValues()).issues().isEmpty());
        }
        assertEquals("kept", known(only(bindings.get("3").inference().possibleValues())));
    }

    @Test
    void rawSqlPlaceholdersInferPostTransformationValuesFromMainLiterals() throws Exception {
        var result = analyze("""
                public class Sample {
                    public static void main(String[] args) throws Exception { run(null, "O'Neil", 20); }
                    static void run(java.sql.Statement s, String name, Integer age) throws Exception {
                        s.execute("SELECT * FROM users WHERE name='" + quote(name) + "' AND age=" + age);
                    }
                    static String quote(String value) { return value.replace("'", "''"); }
                }
                """);
        var slots = only(result.rawSqls()).placeholders();
        assertEquals("O''Neil", known(only(slots.get(0).inference().possibleValues())));
        assertEquals("20", known(only(slots.get(1).inference().possibleValues())), "拼接槽位保存 String.valueOf 之后的文本");
        assertTrue(slots.stream().allMatch(s -> s.inference().complete()));
        assertThrows(UnsupportedOperationException.class, () -> slots.getFirst().inference().possibleValues().clear());
    }

    @Test
    void mainArrayInputsGenerateElementValuesAndReplayHelpers() throws Exception {
        var result = analyze("""
                public class Sample {
                    public static void main(String[] args) throws Exception { run(null, args[0]); }
                    static void run(java.sql.Connection c, String input) throws Exception {
                        var p = c.prepareStatement("SELECT ?"); p.setString(1, "prefix_" + input); p.executeQuery();
                    }
                }
                """);
        var value = only(bindings(result).get("1").inference().possibleValues());
        assertTrue(value.complete(), value.issues().toString()); assertTrue(value.sampled());
        var initial = only(value.initialValues().stream().filter(InferredParameterValue.InitialValue::generated).toList());
        assertEquals("java.lang.String", initial.variable().type());
        assertTrue(initial.variable().expression().endsWith("[0]"));
        assertEquals("prefix_" + ((SqlValue.Known) initial.value()).value(), known(value));
        assertEquals("main", value.propagationPath().callChain().getFirst().caller().orElseThrow().getName());
    }

    @Test
    void unsupportedExternalObjectIsNotInventedAsStringOrMap() throws Exception {
        var result = analyze("""
                public class Sample {
                    static void run(java.sql.Connection c, Sample object) throws Exception {
                        var p = c.prepareStatement("SELECT ?"); p.setObject(1, object); p.executeQuery();
                    }
                }
                """);
        var inference = bindings(result).get("1").inference();
        assertFalse(inference.complete());
        assertInstanceOf(SqlValue.Unknown.class, only(inference.possibleValues()).value());
    }

    @Test
    void injectedGeneratorAllowsRepeatableReplayAndCancellationIsPropagated() throws Exception {
        var result = analyze("""
                public class Sample {
                    static void run(java.sql.Connection c, int id) throws Exception {
                        var p = c.prepareStatement("SELECT ?"); p.setInt(1, id * 3); p.executeQuery();
                    }
                }
                """);
        var trace = bindings(result).get("1").value();
        var inferred = new ParameterValueInferencer((type, name) -> new SqlValue.Known(7)).infer(trace);
        assertEquals(21, known(only(inferred.possibleValues())));
        var stop = new IllegalStateException("cancelled");
        assertSame(stop, assertThrows(IllegalStateException.class, () -> new ParameterValueInferencer(
                new RandomExternalValueGenerator(1), () -> { throw stop; }).infer(trace)));
    }

    @Test
    void independentBranchOperandsProduceAllValuesWithTheirOwnInitials() throws Exception {
        var result = analyze("""
                public class Sample {
                    static void run(java.sql.Connection c, boolean a, boolean b) throws Exception {
                        var p = c.prepareStatement("SELECT ?"); int left = a ? 1 : 2; int right = b ? 10 : 20;
                        p.setInt(1, left + right); p.executeQuery();
                    }
                }
                """);
        var inference = bindings(result).get("1").inference();
        assertTrue(inference.complete());
        assertEquals(Set.of(11, 12, 21, 22), inference.possibleValues().stream().map(JdbcParameterInferenceTest::known).collect(Collectors.toSet()));
        assertTrue(inference.possibleValues().stream().allMatch(value -> value.initialValues().size() == 2));
    }

    @Test
    void parameterBranchLimitIsReportedWithoutLosingAvailableCandidates() throws Exception {
        var result = analyze("""
                public class Sample {
                    static void run(java.sql.Connection c, boolean a, boolean b, boolean d, boolean e,
                                    boolean f, boolean g, boolean h, boolean i) throws Exception {
                        var p = c.prepareStatement("SELECT ?");
                        String value = (a ? "a" : "A") + (b ? "b" : "B") + (d ? "d" : "D") + (e ? "e" : "E")
                            + (f ? "f" : "F") + (g ? "g" : "G") + (h ? "h" : "H") + (i ? "i" : "I");
                        p.setString(1, value); p.executeQuery();
                    }
                }
                """);
        var inference = bindings(result).get("1").inference();
        assertEquals(128, inference.possibleValues().size());
        assertFalse(inference.complete());
        assertTrue(inference.issues().stream().anyMatch(issue -> issue.contains("limit")));
    }

    @Test
    void typedRandomGeneratorProducesCorrectTypesAndSeededSequences() {
        var types = Map.of("boolean", Boolean.class, "byte", Byte.class, "short", Short.class, "int", Integer.class,
                "long", Long.class, "float", Float.class, "double", Double.class, "char", Character.class, "java.lang.String", String.class);
        var first = new RandomExternalValueGenerator(42); var second = new RandomExternalValueGenerator(42);
        for (var entry : types.entrySet()) {
            var sample = assertInstanceOf(SqlValue.Known.class, first.generate(entry.getKey(), "value"));
            assertInstanceOf(entry.getValue(), sample.value());
            assertEquals(sample, second.generate(entry.getKey(), "value"));
        }
    }

    @Test
    void primitiveMethodArgumentsRetainBooleanCharacterAndFloatingPointSemantics() throws Exception {
        var result = analyze("""
                public class Sample {
                    public static void main(String[] args) throws Exception { run(null, true, 'Z', 3.5f, 10L); }
                    static void run(java.sql.Connection c, boolean enabled, char marker, float amount, long id) throws Exception {
                        var p = c.prepareStatement("SELECT ?,?,?,?,?");
                        p.setBoolean(1, enabled); p.setObject(2, Character.valueOf(marker));
                        p.setFloat(3, amount / 2); p.setLong(4, id << 33);
                        p.setString(5, String.valueOf(enabled) + marker); p.executeQuery();
                    }
                }
                """);
        var bindings = bindings(result);
        assertEquals(true, known(only(bindings.get("1").inference().possibleValues())));
        assertEquals('Z', known(only(bindings.get("2").inference().possibleValues())));
        assertEquals(1.75f, known(only(bindings.get("3").inference().possibleValues())));
        assertEquals(10L << 33, known(only(bindings.get("4").inference().possibleValues())));
        assertEquals("trueZ", known(only(bindings.get("5").inference().possibleValues())));
        assertTrue(result.extractions().stream().flatMap(e -> e.statements().stream()).allMatch(SqlExtraction.Statement::inferenceComplete));
    }

    @Test
    void arrayWriteBranchesReplayTheirSelectedElements() throws Exception {
        var result = analyze("""
                public class Sample {
                    static void run(java.sql.Connection c, boolean flag) throws Exception {
                        var p = c.prepareStatement("SELECT ?");
                        int[] values = new int[1]; if (flag) values[0] = 5; else values[0] = 8;
                        p.setInt(1, values[0] * 2); p.executeQuery();
                    }
                }
                """);
        var inference = bindings(result).get("1").inference();
        assertTrue(inference.complete(), inference.issues().toString());
        assertEquals(Set.of(10, 16), inference.possibleValues().stream().map(JdbcParameterInferenceTest::known).collect(Collectors.toSet()));
        assertEquals(2, inference.possibleValues().size());
    }

    @Test
    void unknownBranchRetainsItsPathAlongsideTheKnownBranch() throws Exception {
        var result = analyze("""
                public class Sample {
                    static void run(java.sql.Connection c, boolean flag) throws Exception {
                        var p = c.prepareStatement("SELECT ?");
                        String value = flag ? "safe" : System.getProperty("unmodeled");
                        p.setString(1, value); p.executeQuery();
                    }
                }
                """);
        var inference = bindings(result).get("1").inference();
        assertFalse(inference.complete());
        assertEquals(2, inference.possibleValues().size());
        var known = only(inference.possibleValues().stream().filter(InferredParameterValue::complete).toList());
        var unknown = only(inference.possibleValues().stream().filter(value -> !value.complete()).toList());
        assertEquals("safe", known(known));
        assertInstanceOf(SqlValue.Unknown.class, unknown.value());
        assertTrue(nodes(unknown.propagationPath().root()).anyMatch(node -> node.kind() == ValueTrace.Kind.EXTERNAL_CALL));
        assertFalse(nodes(known.propagationPath().root()).anyMatch(node -> node.kind() == ValueTrace.Kind.EXTERNAL_CALL));
        var raw = only(result.rawSqls().stream().filter(sql -> !sql.bindings().isEmpty()).toList());
        assertFalse(raw.inferenceComplete());
        assertEquals(inference, only(raw.bindings()).inference(), "顶层 SQL 不能丢失未知分支及其传播链");
    }

    @Test
    void referenceCastsAndNullParsingFollowJavaRuntimeSemantics() throws Exception {
        var result = analyze("""
                public class Sample {
                    public static void main(String[] args) throws Exception { run(null, Long.valueOf(3L)); }
                    static void run(java.sql.Connection c, Object number) throws Exception {
                        var p = c.prepareStatement("SELECT ?,?,?,?");
                        p.setObject(1, (Integer) number);
                        p.setBoolean(2, Boolean.valueOf((String) null)); p.setString(3, (String) number);
                        p.setString(4, null);
                        p.executeQuery();
                    }
                }
                """);
        var bindings = bindings(result);
        assertInstanceOf(SqlValue.Unknown.class, only(bindings.get("1").inference().possibleValues()).value());
        assertEquals(false, known(only(bindings.get("2").inference().possibleValues())));
        assertInstanceOf(SqlValue.Unknown.class, only(bindings.get("3").inference().possibleValues()).value());
        assertEquals("java.lang.String", bindings.get("4").inference().type());
        assertNull(known(only(bindings.get("4").inference().possibleValues())));
    }

    @Test
    void resultAssociatesConcatenatedAndBoundParametersWithEachCallContext() throws Exception {
        var result = analyze("""
                public class Sample {
                    public static void main(String[] args) throws Exception { run(null, "east", 11); run(null, "west", 22); }
                    static void run(java.sql.Connection c, String table, int id) throws Exception {
                        var p = c.prepareStatement("SELECT * FROM " + table + " WHERE id=?");
                        p.setInt(1, id); p.executeQuery();
                    }
                }
                """);
        assertEquals(4, result.rawSqls().size(), "保留两个调用上下文中的准备点及执行点");
        assertEquals(1, result.rawSqls().stream().map(RawSql.Variant::text).distinct().count());
        var executions = result.rawSqls().stream().filter(sql -> !sql.bindings().isEmpty()).toList();
        assertEquals(2, executions.size());
        Map<Object, Object> paired = executions.stream().collect(Collectors.toMap(
                sql -> known(only(only(sql.parameters()).possibleValues())),
                sql -> known(only(only(sql.bindings()).inference().possibleValues()))));
        assertEquals(Map.of("east", 11, "west", 22), paired, "同一文本的不同调用不能串用参数");
        assertTrue(executions.stream().allMatch(RawSql.Variant::inferenceComplete));
        assertTrue(executions.stream().allMatch(sql -> only(sql.bindings()).key().equals("1")));
        assertThrows(UnsupportedOperationException.class, () -> executions.getFirst().bindings().clear());
        assertThrows(UnsupportedOperationException.class, () -> executions.getFirst().parameters().clear());
    }

    @Test
    void resultRetainsEachBatchBindingSnapshotInsteadOfTheLatestSetter() throws Exception {
        var result = analyze("""
                public class Sample {
                    static void run(java.sql.Connection c) throws Exception {
                        var p = c.prepareStatement("INSERT INTO t VALUES (?)");
                        p.setInt(1, 11); p.addBatch(); p.setInt(1, 22); p.addBatch();
                        p.setInt(1, 99); p.executeBatch();
                    }
                }
                """);
        var parameters = result.rawSqls().stream().flatMap(sql -> sql.bindings().stream())
                .flatMap(binding -> binding.inference().possibleValues().stream()).map(JdbcParameterInferenceTest::known)
                .collect(Collectors.groupingBy(value -> value, Collectors.counting()));
        assertEquals(Map.of(11, 2L, 22, 2L), parameters, "addBatch 和 executeBatch 均携带加入批次时的值，不使用 99");
        var batchSqls = result.extractions().stream().filter(e -> e.callSite().target().getName().equals("executeBatch"))
                .flatMap(e -> e.statements().stream()).flatMap(s -> s.rawSql().variants().stream()).toList();
        assertEquals(2, batchSqls.size());
        assertEquals(Set.of(11, 22), batchSqls.stream().map(sql -> known(only(only(sql.bindings()).inference().possibleValues())))
                .collect(Collectors.toSet()));
    }

    @Test
    void clearedBindingsDoNotLeakIntoLaterResultWithTheSameSqlText() throws Exception {
        var result = analyze("""
                public class Sample {
                    static void run(java.sql.Connection c) throws Exception {
                        var p = c.prepareStatement("SELECT ?"); p.setInt(1, 5); p.executeQuery();
                        p.clearParameters(); p.executeQuery();
                    }
                }
                """);
        assertEquals(3, result.rawSqls().size());
        assertEquals(2, result.rawSqls().stream().filter(sql -> sql.bindings().isEmpty()).count(),
                "准备点和清空后的执行点均无已设置的绑定参数");
        var bound = only(result.rawSqls().stream().filter(sql -> !sql.bindings().isEmpty()).toList());
        assertEquals(5, known(only(only(bound.bindings()).inference().possibleValues())));
    }

    @Test
    void independentInvocationsKeepAllReturnBranchCombinations() throws Exception {
        var result = analyze("""
                public class Sample {
                    static int choose(boolean flag) { int value = flag ? 1 : 2; return value; }
                    static void run(java.sql.Connection c, boolean a, boolean b) throws Exception {
                        var p = c.prepareStatement("SELECT ?");
                        int left = choose(a); int right = choose(b);
                        p.setInt(1, left * 10 + right); p.executeQuery();
                    }
                }
                """);
        var inference = bindings(result).get("1").inference();
        assertTrue(inference.complete());
        assertEquals(Set.of(11, 12, 21, 22), inference.possibleValues().stream()
                .map(JdbcParameterInferenceTest::known).collect(Collectors.toSet()));
        assertEquals(4, inference.possibleValues().size());
        assertTrue(inference.possibleValues().stream().allMatch(value -> value.initialValues().stream()
                .map(InferredParameterValue.InitialValue::variable).filter(variable -> variable.method().getName().equals("choose"))
                .map(ValueTrace.Variable::callContext).distinct().count() == 2), "同值的两次调用也应保留各自的来源上下文");
    }

    @Test
    void forwardedValueRetainsTheOriginalBranchChoice() throws Exception {
        var result = analyze("""
                public class Sample {
                    static int forward(int value) { return value; }
                    static void run(java.sql.Connection c, boolean flag) throws Exception {
                        var p = c.prepareStatement("SELECT ?");
                        int value = flag ? 10 : 20; int copy = forward(value);
                        p.setInt(1, value - copy); p.executeQuery();
                    }
                }
                """);
        var inference = bindings(result).get("1").inference();
        assertTrue(inference.complete());
        assertEquals(List.of(0, 0), inference.possibleValues().stream().map(JdbcParameterInferenceTest::known).toList());
        assertTrue(inference.possibleValues().stream().allMatch(value -> value.initialValues().size() == 1));
    }

    @Test
    void replacementAfterNumericStringificationUsesConvertedText() throws Exception {
        var result = analyze("""
                public class Sample {
                    public static void main(String[] args) throws Exception { run(null, 12); }
                    static void run(java.sql.Statement s, int id) throws Exception {
                        s.execute(("SELECT " + id).replace("1", "X"));
                    }
                }
                """);
        var sql = only(result.rawSqls());
        var parameter = only(sql.parameters());
        assertEquals("SELECT ${id}", sql.text());
        assertEquals("X2", known(only(parameter.possibleValues())));
        assertEquals("java.lang.String", parameter.type());
        assertTrue(nodes(sql.trace().orElseThrow().root()).filter(node -> node.computation().operation() == ValueTrace.Operation.STRINGIFY)
                .allMatch(node -> node.variable().type().equals("java.lang.String")), "完整传播树也应记录转换后的结果类型");
        assertTrue(only(parameter.possibleValues()).initialValues().stream()
                .anyMatch(input -> input.value().equals(new SqlValue.Known(12)) && input.variable().type().equals("int")));
    }

    @Test
    void reassignedLocalDoesNotReuseThePreviousDefinitionChoice() throws Exception {
        var result = analyze("""
                public class Sample {
                    static int forward(int value) { return value; }
                    static void run(java.sql.Connection c, boolean a, boolean b) throws Exception {
                        var p = c.prepareStatement("SELECT ?");
                        int value = a ? 1 : 2; int previous = forward(value);
                        value = b ? 10 : 20;
                        p.setInt(1, previous + value); p.executeQuery();
                    }
                }
                """);
        var inference = bindings(result).get("1").inference();
        assertTrue(inference.complete());
        assertEquals(Set.of(11, 12, 21, 22), inference.possibleValues().stream()
                .map(JdbcParameterInferenceTest::known).collect(Collectors.toSet()));
        assertEquals(4, inference.possibleValues().size());
    }

    @Test
    void replacementAfterNullStringificationUsesNullText() throws Exception {
        var result = analyze("""
                public class Sample {
                    public static void main(String[] args) throws Exception { run(null, null); }
                    static void run(java.sql.Statement s, String name) throws Exception {
                        s.execute(("SELECT '" + name + "'").replace("l", "L"));
                    }
                }
                """);
        var parameter = only(only(result.rawSqls()).parameters());
        assertEquals("nuLL", known(only(parameter.possibleValues())));
        assertEquals("java.lang.String", parameter.type());
        assertTrue(only(parameter.possibleValues()).initialValues().stream()
                .anyMatch(input -> input.value().equals(new SqlValue.Known(null))));
    }

    @Test
    void chainedReplacementRetainsBooleanAndCharacterStringConversion() throws Exception {
        var result = analyze("""
                public class Sample {
                    public static void main(String[] args) throws Exception { run(null, true, 'A'); }
                    static void run(java.sql.Statement s, boolean enabled, char marker) throws Exception {
                        s.execute(("SELECT " + enabled + "," + marker).replace("r", "R").replace("A", "Z"));
                    }
                }
                """);
        var sql = only(result.rawSqls());
        assertTrue(sql.inferenceComplete());
        assertEquals(Map.of("enabled", "tRue", "marker", "Z"), sql.parameters().stream()
                .collect(Collectors.toMap(ParameterValueInferenceResult::name, p -> known(only(p.possibleValues())))));
        assertTrue(sql.parameters().stream().allMatch(p -> p.type().equals("java.lang.String")));
    }

    private AnalysisResult analyze(String source) throws Exception {
        Path java = Files.writeString(temporary.resolve("Sample.java"), source);
        Path classes = Files.createDirectory(temporary.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-proc:none", "-g", "-d", classes.toString(), java.toString()));
        var result = new Analyzer(AnalyzerConfig.defaults().withMaxTraceDepth(64), List.of(new JdbcAnalysisModule()), List.of()).analyze(classes);
        assertNotEquals(AnalysisResult.Status.FAILED, result.status());
        return result;
    }
    private static Map<String, SqlExtraction.Binding> bindings(AnalysisResult result) {
        var statements = result.extractions().stream().filter(e -> e.callSite().target().getName().equals("executeQuery"))
                .flatMap(e -> e.statements().stream()).toList();
        return only(statements).bindings().stream().collect(Collectors.toMap(SqlExtraction.Binding::key, b -> b));
    }
    private static Object known(InferredParameterValue value) { return assertInstanceOf(SqlValue.Known.class, value.value(), value.issues().toString()).value(); }
    private static <T> T only(List<T> values) { assertEquals(1, values.size(), values.toString()); return values.getFirst(); }
    private static Stream<ValueTrace.Node> nodes(ValueTrace.Node node) { return Stream.concat(Stream.of(node), node.inputs().stream().flatMap(JdbcParameterInferenceTest::nodes)); }
}
