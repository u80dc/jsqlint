package org.u80dc.jsqlint.core.dataflow;

import org.junit.jupiter.api.Test;
import org.u80dc.jsqlint.core.SqlRecoveryBudgets;
import sootup.java.core.JavaIdentifierFactory;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.dataflow.ValueTrace.*;

/** 验证生成文本及槽位消歧后的最终长度也受恢复预算限制。 */
class SqlRecoveryBudgetsTest {
    @Test
    void renamedCollidingSlotsRespectFinalLength() {
        Node first = node(0, Kind.ENTRY, Operation.OPAQUE, "", "java.lang.String");
        Node second = node(1, Kind.ENTRY, Operation.OPAQUE, "", "java.lang.String");
        Node joined = node(2, Kind.EXPRESSION, Operation.CONCAT, "", "java.lang.String", first, second);
        var trace = new ValueTrace(joined.variable(), List.of(), joined);
        var limited = RawSqlBuilder.build(trace, SqlRecoveryBudgets.defaults().withLength(12), () -> {});
        assertFalse(limited.complete());
        assertTrue(limited.issues().contains("Raw SQL length limit exceeded"));
        assertEquals(1, limited.variants().size());
        assertEquals(joined.variable(), limited.variants().getFirst().trace().orElseThrow().variable());

        var exact = RawSqlBuilder.build(trace, SqlRecoveryBudgets.defaults().withLength(14), () -> {});
        assertTrue(exact.complete(), exact.issues().toString());
        assertEquals("${sql}${sql_2}", exact.variants().getFirst().text());
    }

    @Test
    void booleanAndNullTextConversionsRespectFinalLength() {
        for (Node value : List.of(node(0, Kind.CONSTANT, Operation.LITERAL, "1", "boolean"),
                node(0, Kind.CONSTANT, Operation.NULL, "", "null"))) {
            Node converted = node(1, Kind.EXPRESSION, Operation.STRINGIFY, value.variable().type(), "java.lang.String", value);
            var raw = RawSqlBuilder.build(new ValueTrace(converted.variable(), List.of(), converted),
                    SqlRecoveryBudgets.defaults().withLength(1), () -> {});
            assertFalse(raw.complete());
            assertTrue(raw.issues().contains("Raw SQL length limit exceeded"));
        }
    }

    private static Node node(int position, Kind kind, Operation operation, String text, String type, Node... inputs) {
        var method = JavaIdentifierFactory.getInstance().getMethodSignature("example.Sample", "query", "void", List.of());
        var variable = new Variable(method, position, "value", type);
        return new Node(variable, kind, text, List.of(inputs), new Computation(operation, text));
    }
}
