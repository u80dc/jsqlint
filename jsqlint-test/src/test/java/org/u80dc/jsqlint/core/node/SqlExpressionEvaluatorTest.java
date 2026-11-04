package org.u80dc.jsqlint.core.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqlExpressionEvaluatorTest {
    private final SqlExpressionEvaluator evaluator = SqlExpressionEvaluator.simple();

    @ParameterizedTest
    @ValueSource(strings = {
            "true or missing", "true || missing",
            "!(false)", "not false", "1 + 2 == 3", "(1 + 2) >= 3",
            "name != null and name.length() > 0", "ids.size() == 2",
            "ids[0] == 7", "map['value'] == 9", "null == null"
    })
    void supportedExpressionsHaveKnownResults(String expression) {
        assertEquals(SqlValue.Truth.TRUE, evaluator.evaluate(expression,
                Map.of("name", "Alice", "ids", List.of(7, 8), "map", Map.of("value", 9))).truth());
    }

    @Test
    void booleanOperatorsUseThreeValuedLogic() {
        assertEquals(SqlValue.Truth.FALSE, evaluator.evaluate("false and missing", Map.of()).truth());
        assertEquals(SqlValue.Truth.TRUE, evaluator.evaluate("true or missing", Map.of()).truth());
        assertEquals(SqlValue.Truth.UNKNOWN, evaluator.evaluate("true and missing", Map.of()).truth());
        assertEquals(SqlValue.Truth.UNKNOWN, evaluator.evaluate("false or missing", Map.of()).truth());
    }

    @Test
    void distinguishesAbsentMapPropertyAndKnownNull() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", null);
        assertEquals(SqlValue.Truth.TRUE, evaluator.evaluate("id == null", values).truth());
        assertEquals(SqlValue.Truth.UNKNOWN, evaluator.evaluate("missing == null", values).truth());
        assertInstanceOf(SqlValue.Known.class, evaluator.evaluate("user.id", Map.of("user", values)));
        assertInstanceOf(SqlValue.Unknown.class, evaluator.evaluate("user.missing", Map.of("user", values)));
    }

    @Test
    void explicitUnknownPropagatesThroughExpressions() {
        assertInstanceOf(SqlValue.Unknown.class, evaluator.evaluate("'%' + name + '%'",
                Map.of("name", new SqlValue.Unknown("No inference result"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "name.startsWith('A')", "name.toString()", "1 / 0", "ids[99]", "map.noSuchProperty",
            "missing != null", "true &&", "'unterminated", "1 < null"
    })
    void unsupportedOrUnresolvedExpressionsAreUnknown(String expression) {
        assertInstanceOf(SqlValue.Unknown.class, evaluator.evaluate(expression,
                Map.of("name", "Alice", "ids", List.of(1), "map", Map.of())));
    }

    @Test
    void preservesIntegerPrecisionAndSupportsRecordProperties() {
        assertEquals(SqlValue.Truth.FALSE,
                evaluator.evaluate("9007199254740992 == 9007199254740993", Map.of()).truth());
        assertEquals(SqlValue.Truth.TRUE,
                evaluator.evaluate("user.id == 7", Map.of("user", new User(7))).truth());
    }

    private record User(int id) {}
}
