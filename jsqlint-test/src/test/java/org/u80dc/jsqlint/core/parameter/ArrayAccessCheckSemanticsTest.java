package org.u80dc.jsqlint.core.parameter;

import org.junit.jupiter.api.Test;
import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.core.semantics.JavaValueOperations;
import sootup.java.core.JavaIdentifierFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.dataflow.ValueTrace.*;
import static org.u80dc.jsqlint.core.parameter.JointSqlInferenceResult.*;

class ArrayAccessCheckSemanticsTest {
    private static final Computation CHECK = new Computation(Operation.ARRAY_ACCESS_CHECK, "allocated array access");

    @Test
    void allocatedArrayCheckUsesJavaIntOperandsAndPreservesTheElement() {
        assertEquals(7, JavaValueOperations.evaluate(CHECK, "int", List.of(7, 0, 1), 100));
        assertNull(JavaValueOperations.evaluate(CHECK, "java.lang.String", Arrays.asList(null, 0, 1), 100));
        assertEquals(7, JavaValueOperations.evaluate(CHECK, "int", List.of(7, (char) 1, (short) 2), 100));
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> JavaValueOperations.evaluate(CHECK, "int", List.of(7, 1, 1), 100));
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> JavaValueOperations.evaluate(CHECK, "int", List.of(7, -1, 1), 100));
        assertThrows(NegativeArraySizeException.class, () -> JavaValueOperations.evaluate(CHECK, "int", List.of(7, 0, -1), 100));
        assertThrows(IllegalArgumentException.class, () -> JavaValueOperations.evaluate(CHECK, "int", List.of(7, 0L, 1), 100));
        assertThrows(IllegalArgumentException.class, () -> JavaValueOperations.evaluate(CHECK, "int", List.of(7, 0, 1.0), 100));
    }

    @Test
    void replayAndSolverRetainUnknownForInvalidOrUnresolvedArrayAccess() {
        for (Node value : List.of(checked("1", "1"), checked("-1", "1"), checked("0", "-1"))) {
            var inferred = new ParameterValueInferencer().infer(trace(value));
            assertInstanceOf(SqlValue.Unknown.class, inferred.possibleValues().getFirst().value());
            assertEquals(Status.UNKNOWN, solve(value).status());
        }
        Node valid = checked("0", "1");
        assertEquals(7, assertInstanceOf(SqlValue.Known.class,
                new ParameterValueInferencer().infer(trace(valid)).possibleValues().getFirst().value()).value());
        assertEquals(Status.SATISFIABLE, solve(valid).status());

        Node length = node("int", Kind.UNRESOLVED, new Computation(Operation.OPAQUE, "unknown array length"));
        Node unresolved = node("int", Kind.EXPRESSION, CHECK, literal("7"), literal("0"), length);
        assertInstanceOf(SqlValue.Unknown.class, new ParameterValueInferencer().infer(trace(unresolved)).possibleValues().getFirst().value());
        assertEquals(Status.UNKNOWN, solve(unresolved).status());
    }

    @Test
    void definitionCoercionCannotTurnAnInvalidIntModelIntoAValidArrayBound() {
        for (Object model : List.of(4294967297L, 1.5)) {
            Node length = input("length", "int");
            Node checked = node("int", Kind.EXPRESSION, CHECK, literal("7"), literal("0"), definition(length));
            assertInstanceOf(SqlValue.Unknown.class, replay(checked, length, model), "Invalid length model: " + model);
        }
        for (Object model : List.of(4294967296L, 0.5)) {
            Node index = input("index", "int");
            Node checked = node("int", Kind.EXPRESSION, CHECK, literal("7"), definition(index), literal("1"));
            assertInstanceOf(SqlValue.Unknown.class, replay(checked, index, model), "Invalid index model: " + model);
        }
    }

    @Test
    void narrowIntegerInputsAndExplicitLongCastsKeepTheirJavaSemantics() {
        for (Object model : List.of(1, (byte) 1, (short) 1, (char) 1)) {
            Node length = input("length", "int");
            Node checked = node("int", Kind.EXPRESSION, CHECK, literal("7"), literal("0"), definition(length));
            assertEquals(new SqlValue.Known(7), replay(checked, length, model));
        }
        Node length = input("length", "long");
        Node castLength = node("int", Kind.EXPRESSION, new Computation(Operation.CAST, "int"), length);
        Node checkedLength = node("int", Kind.EXPRESSION, CHECK, literal("7"), literal("0"), definition(castLength));
        assertEquals(new SqlValue.Known(7), replay(checkedLength, length, 4294967297L));

        Node index = input("index", "long");
        Node castIndex = node("int", Kind.EXPRESSION, new Computation(Operation.CAST, "int"), index);
        Node checkedIndex = node("int", Kind.EXPRESSION, CHECK, literal("7"), definition(castIndex), literal("1"));
        assertEquals(new SqlValue.Known(7), replay(checkedIndex, index, 4294967296L));
    }

    @Test
    void externalArrayModelsStillSupplyTheirSelectedElement() {
        Node array = input("array", "int[]");
        Node read = node("int", Kind.EXPRESSION, new Computation(Operation.ARRAY_INPUT, "external array element"), literal("0"), array);
        assertEquals(new SqlValue.Known(7), replay(read, array, new int[]{7}));
    }

    private static Node checked(String index, String length) {
        return node("int", Kind.EXPRESSION, CHECK, literal("7"), literal(index), literal(length));
    }
    private static Node literal(String value) { return node("int", Kind.CONSTANT, new Computation(Operation.LITERAL, value)); }
    private static Node input(String name, String type) {
        var method = JavaIdentifierFactory.getInstance().getMethodSignature("example.ArrayCheck", "query", "void", List.of());
        return new Node(new Variable(method, 1, name, type), Kind.ENTRY, name, List.of());
    }
    private static Node definition(Node value) {
        return node("int", Kind.DEFINITION, new Computation(Operation.ALTERNATIVES, "reaching definition"), value);
    }
    private static SqlValue replay(Node value, Node input, Object model) {
        var unavailable = (ExternalValueGenerator) (type, name) -> new SqlValue.Unknown("No generated model");
        var inference = new ParameterValueInferencer(unavailable, () -> {}, Map.of(input.variable(), new SqlValue.Known(model))).infer(trace(value));
        return inference.possibleValues().getFirst().value();
    }
    private static Node node(String type, Kind kind, Computation computation, Node... inputs) {
        var method = JavaIdentifierFactory.getInstance().getMethodSignature("example.ArrayCheck", "query", "void", List.of());
        return new Node(new Variable(method, 0, "array element", type), kind, computation.text(), List.of(inputs), computation);
    }
    private static ValueTrace trace(Node node) { return new ValueTrace(node.variable(), List.of(), node); }
    private static PathPredicateSolver.Solution solve(Node value) {
        Node equal = node("boolean", Kind.EXPRESSION, new Computation(Operation.BINARY, "=="), value, literal("7"));
        return new PathPredicateSolver(() -> {}).solve(List.of(new Condition(trace(equal), true)));
    }
}
