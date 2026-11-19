package org.u80dc.jsqlint.core.node;

import org.junit.jupiter.api.Test;

import java.util.AbstractList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqlExpressionActivityValueTest {
    private final SqlExpressionEvaluator evaluator = SqlExpressionEvaluator.simple();

    @Test void arrayEqualityUsesOriginalIdentityAndContainersKeepTheirEqualsSemantics() {
        int[] ids = {7}, other = {7};
        var values = Map.of("ids", ids, "alias", ids, "other", other,
                "rows", List.of(ids), "sharedRows", List.of(ids), "otherRows", List.of(other),
                "map", Map.of("ids", ids), "sharedMap", Map.of("ids", ids), "otherMap", Map.of("ids", other));
        for (String expression : List.of("ids == ids", "ids == alias", "ids != other", "rows == rows",
                "rows == sharedRows", "rows != otherRows", "map == map", "map == sharedMap", "map != otherMap"))
            assertEquals(SqlValue.Truth.TRUE, evaluator.evaluate(expression, values).truth(), expression);
        assertEquals(SqlValue.Truth.FALSE, evaluator.evaluate("ids == other", values).truth());
        assertEquals(SqlValue.Truth.FALSE, evaluator.evaluate("rows == otherRows", values).truth());

        var saved = new SqlValue.Known(ids);
        assertEquals(SqlValue.Truth.TRUE, evaluator.evaluate("ids == alias", Map.of("ids", saved, "alias", saved)).truth());
        assertEquals(SqlValue.Truth.FALSE, evaluator.evaluate("ids == other",
                Map.of("ids", saved, "other", new SqlValue.Known(other))).truth());
    }

    @Test void indexingAndSizeDoNotFreezeOrEnumerateTheWholeActivityInput() {
        var ids = new AbstractList<Integer>() {
            @Override public Integer get(int index) {
                assertEquals(0, index, "The expression only reads the first element");
                return 7;
            }
            @Override public int size() { return 1000; }
        };
        assertEquals(new SqlValue.Known(7), evaluator.evaluate("ids[0]", Map.of("ids", ids)));
        assertEquals(new SqlValue.Known(1000), evaluator.evaluate("ids.size()", Map.of("ids", ids)));
    }

    @Test void activityComparisonsChooseTheCorrectSqlAndReplayKeepsFrozenInputs() {
        int[] ids = {7}, other = {8};
        var node = SqlNodeBuilder.buildScript("SELECT <if test='ids == ids'>#{ids[0]}</if><if test='ids != other'>,#{other[0]}</if>");
        var saved = SqlNodeRenderer.renderBound(node, Map.of("ids", ids, "other", other), evaluator);
        assertEquals("SELECT ?,?", saved.rendered().sql().orElseThrow());
        assertEquals(List.of(new SqlValue.Known(7), new SqlValue.Known(8)),
                saved.bindings().stream().map(SqlNodeRenderer.BoundParameter::value).toList());
        ids[0] = 99; other[0] = 88;
        ((int[]) saved.rendered().parameters().get("ids"))[0] = 77;
        var replayed = SqlNodeRenderer.renderBound(saved.rendered().residual(), saved.rendered().parameters(), evaluator);
        assertEquals(saved.rendered().sql(), replayed.rendered().sql());
        assertEquals(saved.bindings(), replayed.bindings());
        assertEquals(saved.rendered().recoveryMode(), replayed.rendered().recoveryMode());
        assertArrayEquals(new int[]{7}, (int[]) replayed.rendered().parameters().get("ids"));
        assertArrayEquals(new int[]{8}, (int[]) replayed.rendered().parameters().get("other"));
    }

    @Test void finalValuesStillFreezeArraysAndReferenceTrackingKeepsActualReads() {
        int[] ids = {7};
        var saved = assertInstanceOf(SqlValue.Known.class, evaluator.evaluate("ids", Map.of("ids", ids)));
        ids[0] = 99;
        ((int[]) saved.value())[0] = 88;
        assertArrayEquals(new int[]{7}, (int[]) saved.value());

        var evaluated = evaluator.evaluateWithReferences("params.id + ids[0]",
                Map.of("params", Map.of("id", 3, "unused", 9), "ids", new int[]{7}), false);
        assertEquals(new SqlValue.Known(new java.math.BigDecimal("10")), evaluated.value());
        assertEquals(List.of(new SqlExpressionEvaluator.Reference("params", List.of("id")),
                new SqlExpressionEvaluator.Reference("ids", List.of())), evaluated.references());
    }
}
