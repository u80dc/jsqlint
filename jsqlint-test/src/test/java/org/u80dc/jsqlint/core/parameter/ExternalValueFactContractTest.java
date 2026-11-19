package org.u80dc.jsqlint.core.parameter;

import org.junit.jupiter.api.Test;
import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.SqlValue;
import sootup.java.core.JavaIdentifierFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.dataflow.ValueTrace.*;

/** 外部模型与生成样例都必须符合入口的 Java 声明类型，显式转换仍由目标操作决定。 */
class ExternalValueFactContractTest {
    private record Fact(String type, Object supplied, Object assigned) {}

    @Test
    void explicitModelsRejectInvalidPrimitiveFacts() {
        for (Fact fact : invalidPrimitives()) assertRejected(fact, false);
    }

    @Test
    void customGeneratorsRejectInvalidPrimitiveFacts() {
        for (Fact fact : invalidPrimitives()) assertRejected(fact, true);
    }

    @Test
    void explicitModelsRejectIncompatibleReferenceFacts() {
        for (Fact fact : invalidReferences()) assertRejected(fact, false);
    }

    @Test
    void customGeneratorsRejectIncompatibleReferenceFacts() {
        for (Fact fact : invalidReferences()) assertRejected(fact, true);
    }

    @Test
    void explicitModelsKeepLegalPrimitiveAssignmentConversions() {
        for (Fact fact : validPrimitives()) assertAccepted(fact, false);
    }

    @Test
    void customGeneratorsKeepLegalPrimitiveAssignmentConversions() {
        for (Fact fact : validPrimitives()) assertAccepted(fact, true);
    }

    @Test
    void referenceNullAndCompatibleInterfaceFactsRemainComplete() {
        for (Fact fact : List.of(
                new Fact("java.lang.String", null, null),
                new Fact("java.lang.Integer", null, null),
                new Fact("int[]", null, null),
                new Fact("java.lang.CharSequence", "value", "value"),
                new Fact("java.lang.Number", 7, 7),
                new Fact("java.util.Map", Map.of("id", 7), Map.of("id", 7)))) {
            assertAccepted(fact, false);
            assertAccepted(fact, true);
        }
    }

    @Test
    void referenceArrayCovarianceAndPrimitiveArraysRetainTheirTypes() {
        for (Fact fact : List.of(
                new Fact("java.lang.Object[]", new String[]{"value"}, new String[]{"value"}),
                new Fact("java.lang.Number[]", new Integer[]{7}, new Integer[]{7}),
                new Fact("int[]", new int[]{7}, new int[]{7}),
                new Fact("int[][]", new int[][]{{7}}, new int[][]{{7}}))) {
            assertAccepted(fact, false);
            assertAccepted(fact, true);
        }
    }

    @Test
    void definitionsCannotCoerceInvalidEntryFactsIntoCompleteValues() {
        for (Fact fact : List.of(new Fact("long", 1.9d, null), new Fact("boolean", 1, null))) {
            Node entry = entry(fact.type());
            Node definition = expression(fact.type(), Kind.DEFINITION,
                    new Computation(Operation.ALTERNATIVES, "reaching definition"), entry);
            for (boolean generated : List.of(false, true)) {
                var result = infer(definition, entry, fact.supplied(), generated);
                assertFalse(result.complete());
                assertInstanceOf(SqlValue.Unknown.class, result.possibleValues().getFirst().value());
            }
        }
    }

    @Test
    void explicitJavaCastCanStillNarrowAValidLongEntryFact() {
        Node entry = entry("long");
        Node converted = expression("int", Kind.EXPRESSION, new Computation(Operation.CAST, "int"), entry);
        for (boolean generated : List.of(false, true)) {
            var result = infer(converted, entry, 4294967297L, generated);
            assertTrue(result.complete(), result.issues().toString());
            assertEquals(new SqlValue.Known(1), result.possibleValues().getFirst().value());
        }
    }

    @Test
    void externalArrayReadValidatesTheDeclaredArrayBeforeElementCoercion() {
        Node array = entry("int[]");
        Node index = expression("int", Kind.CONSTANT, new Computation(Operation.LITERAL, "0"));
        Node read = expression("int", Kind.EXPRESSION,
                new Computation(Operation.ARRAY_INPUT, "external array element"), index, array);
        var result = infer(read, array, new char[]{7}, false);
        assertFalse(result.complete());
        assertInstanceOf(SqlValue.Unknown.class, result.possibleValues().getFirst().value());

        var valid = infer(read, array, new int[]{7}, false);
        assertTrue(valid.complete(), valid.issues().toString());
        assertEquals(new SqlValue.Known(7), valid.possibleValues().getFirst().value());
    }

    private static List<Fact> invalidPrimitives() {
        var facts = new ArrayList<>(List.of(
                new Fact("long", 1.9d, null),
                new Fact("boolean", 1, null),
                new Fact("char", 65, null),
                new Fact("byte", (short) 7, null),
                new Fact("short", 7, null),
                new Fact("int", 7L, null),
                new Fact("float", 7d, null),
                new Fact("double", BigDecimal.ONE, null)));
        for (String type : List.of("boolean", "byte", "short", "char", "int", "long", "float", "double"))
            facts.add(new Fact(type, null, null));
        return facts;
    }

    private static List<Fact> invalidReferences() {
        return List.of(
                new Fact("java.lang.String", 7, null),
                new Fact("java.lang.Integer", 7L, null),
                new Fact("java.lang.Number", "7", null),
                new Fact("java.lang.String[]", new Integer[]{7}, null),
                new Fact("int[]", new long[]{7}, null),
                new Fact("int[]", new char[]{7}, null),
                new Fact("int[][]", new char[][]{{7}}, null));
    }

    private static List<Fact> validPrimitives() {
        return List.of(
                new Fact("boolean", true, true),
                new Fact("byte", (byte) 7, (byte) 7),
                new Fact("short", (byte) 7, (short) 7),
                new Fact("char", 'A', 'A'),
                new Fact("int", 'A', 65),
                new Fact("long", 7, 7L),
                new Fact("float", 7L, 7f),
                new Fact("double", 7f, 7d));
    }

    private static void assertRejected(Fact fact, boolean generated) {
        Node entry = entry(fact.type());
        var result = infer(entry, entry, fact.supplied(), generated);
        String message = fact.type() + (generated ? " generator" : " model");
        assertFalse(result.complete(), message);
        assertFalse(result.truncated(), message);
        assertEquals(1, result.possibleValues().size(), message);
        SqlValue.Unknown unknown = assertInstanceOf(SqlValue.Unknown.class,
                result.possibleValues().getFirst().value(), message);
        assertTrue(unknown.reason().contains("assignment semantics"), unknown.reason());
        assertFalse(result.issues().isEmpty(), message);
    }

    private static void assertAccepted(Fact fact, boolean generated) {
        Node entry = entry(fact.type());
        var result = infer(entry, entry, fact.supplied(), generated);
        String message = fact.type() + (generated ? " generator" : " model") + ": " + result.issues();
        assertTrue(result.complete(), message);
        assertEquals(1, result.possibleValues().size(), message);
        assertEquals(new SqlValue.Known(fact.assigned()), result.possibleValues().getFirst().value(), message);
    }

    private static ParameterValueInferenceResult infer(Node value, Node input, Object supplied, boolean generated) {
        SqlValue model = new SqlValue.Known(supplied);
        ExternalValueGenerator generator = generated ? (type, name) -> model
                : (type, name) -> new SqlValue.Unknown("No generated model");
        var models = generated ? Map.<Variable, SqlValue>of() : Map.of(input.variable(), model);
        return new ParameterValueInferencer(generator, () -> {}, models).infer(trace(value));
    }

    private static Node entry(String type) {
        var method = JavaIdentifierFactory.getInstance().getMethodSignature("example.InputFacts", "query", "void", List.of());
        return new Node(new Variable(method, 1, "input", type), Kind.ENTRY, "input", List.of());
    }

    private static Node expression(String type, Kind kind, Computation computation, Node... inputs) {
        var method = JavaIdentifierFactory.getInstance().getMethodSignature("example.InputFacts", "query", "void", List.of());
        return new Node(new Variable(method, 2, "value", type), kind, computation.text(), List.of(inputs), computation);
    }

    private static ValueTrace trace(Node node) { return new ValueTrace(node.variable(), List.of(), node); }
}
