package org.u80dc.jsqlint.core.parameter;

import org.junit.jupiter.api.Test;
import org.u80dc.jsqlint.core.ReplayBudgets;
import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.SqlValue;
import sootup.java.core.JavaIdentifierFactory;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.dataflow.ValueTrace.*;

class ReplayBudgetsTest {
    @Test
    void stringReadFromAnArrayModelRespectsReplayLength() {
        var method = JavaIdentifierFactory.getInstance().getMethodSignature("example.Sample", "query", "void", List.of());
        var array = new Variable(method, 0, "array", "java.lang.String[]");
        Node base = new Node(array, Kind.ENTRY, "array input", List.of());
        var index = new Variable(method, 1, "index", "int");
        Node offset = new Node(index, Kind.CONSTANT, "index", List.of(), new Computation(Operation.LITERAL, "0"));
        var element = new Variable(method, 2, "array[0]", "java.lang.String");
        Node read = new Node(element, Kind.EXPRESSION, "array element", List.of(offset, base),
                new Computation(Operation.ARRAY_INPUT, "array element"));
        var trace = new ValueTrace(element, List.of(), read);
        Map<Variable, SqlValue> model = Map.of(array, new SqlValue.Known(new String[]{"abcdef"}));
        ExternalValueGenerator unavailable = (type, name) -> new SqlValue.Unknown("No generated inputs");

        var limited = new ParameterValueInferencer(unavailable, () -> {}, model,
                ReplayBudgets.defaults().withLength(3)).infer(trace);
        assertTrue(limited.truncated());
        assertFalse(limited.complete());
        assertInstanceOf(SqlValue.Unknown.class, limited.possibleValues().getFirst().value());
        var exact = new ParameterValueInferencer(unavailable, () -> {}, model,
                ReplayBudgets.defaults().withLength(6)).infer(trace);
        assertTrue(exact.complete(), exact.issues().toString());
        assertEquals(new SqlValue.Known("abcdef"), exact.possibleValues().getFirst().value());
    }
}
