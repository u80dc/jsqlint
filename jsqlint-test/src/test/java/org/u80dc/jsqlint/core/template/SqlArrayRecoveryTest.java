package org.u80dc.jsqlint.core.template;

import org.junit.jupiter.api.Test;
import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.core.parameter.ParameterValueInferencer;
import sootup.java.core.JavaIdentifierFactory;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.dataflow.ValueTrace.*;

class SqlArrayRecoveryTest {
    @Test void knownValidBoundsKeepLiteralSqlAndTheCheckedEvidence() {
        var trace = checked(literal("0", "int"), literal("1", "int"));
        var raw = RawSqlBuilder.build(trace, () -> {});
        assertEquals(List.of("SELECT 1"), raw.variants().stream().map(variant -> variant.text()).toList());
        var selected = raw.variants().getFirst().trace().orElseThrow();
        assertEquals(Operation.ARRAY_ACCESS_CHECK, selected.root().computation().operation());
        assertEquals(3, selected.root().inputs().size());
        assertEquals(new SqlValue.Known("SELECT 1"), new ParameterValueInferencer((type, name) -> new SqlValue.Unknown("missing"))
                .infer(selected).possibleValues().getFirst().value());
    }

    @Test void invalidBoundsNeverBecomeLiteralSql() {
        var raw = RawSqlBuilder.build(checked(literal("0", "int"), literal("0", "int")), () -> {});
        assertFalse(raw.issues().isEmpty());
        assertFalse(raw.variants().getFirst().placeholders().isEmpty());
    }

    @Test void symbolicBoundsRetainTheFullCheckForReplay() {
        var index = new Node(literal("0", "int").variable(), Kind.ENTRY, "input index", List.of());
        var raw = RawSqlBuilder.build(checked(index, literal("1", "int")), () -> {});
        var source = raw.variants().getFirst().placeholders().getFirst().source();
        assertEquals(Operation.ARRAY_ACCESS_CHECK, source.computation().operation());
        var result = new ParameterValueInferencer((type, name) -> new SqlValue.Known(2))
                .infer(new ValueTrace(source.variable(), List.of(), source));
        assertTrue(result.possibleValues().stream().noneMatch(value -> value.value() instanceof SqlValue.Known));
    }

    private static ValueTrace checked(Node index, Node length) {
        var sql = literal("SELECT 1", "java.lang.String");
        var root = new Node(sql.variable(), Kind.ARRAY_ELEMENT, "checked element", List.of(sql, index, length),
                new Computation(Operation.ARRAY_ACCESS_CHECK, ""));
        return new ValueTrace(root.variable(), List.of(), root);
    }
    private static Node literal(String text, String type) {
        var method = JavaIdentifierFactory.getInstance().getMethodSignature("example.Array", "sql", "void", List.of());
        return new Node(new Variable(method, 0, "array", type), Kind.CONSTANT, text, List.of(), new Computation(Operation.LITERAL, text));
    }
}
