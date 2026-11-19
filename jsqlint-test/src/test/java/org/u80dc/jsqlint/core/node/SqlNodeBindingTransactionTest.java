package org.u80dc.jsqlint.core.node;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class SqlNodeBindingTransactionTest {
    private final SqlExpressionEvaluator evaluator = SqlExpressionEvaluator.simple();

    @Test void largeForeachKeepsEveryValueAndConsecutivePositionsWithinTheBudget() {
        int[] ids = IntStream.range(0, 4999).toArray();
        var node = new ForEachSqlNode("ids", "id", "", "(", ")", ",", ValueSqlNode.parameter("id"));
        var checkpoints = new AtomicInteger();
        var result = SqlNodeRenderer.renderBound(node, Map.of("ids", ids), evaluator, checkpoints::incrementAndGet);
        assertTrue(result.valuesComplete());
        assertEquals(ids.length, result.bindings().size());
        assertEquals("(" + String.join(",", java.util.Collections.nCopies(ids.length, "?")) + ")", result.rendered().sql().orElseThrow());
        for (int i = 0; i < ids.length; i++) {
            assertEquals(i + 1, result.bindings().get(i).position());
            assertEquals(new SqlValue.Known(i), result.bindings().get(i).value());
        }
        assertTrue(checkpoints.get() <= 10000);
    }

    @Test void anUnfinishedTrimInsideASelectedBranchRollsBackOnlyThatScope() {
        var node = SqlNodeBuilder.buildScript("#{before}<choose><when test='selected'><trim prefix='(' suffix=')'>#{inside}${missing}</trim></when><otherwise>#{fallback}</otherwise></choose>#{tail}");
        var parameters = new LinkedHashMap<String, Object>(Map.of("before", 1, "inside", 2, "fallback", 9, "tail", 3, "selected", true));
        var pending = SqlNodeRenderer.renderBound(node, parameters, evaluator);
        assertFalse(pending.rendered().complete());
        assertEquals(List.of("before"), pending.bindings().stream().map(SqlNodeRenderer.BoundParameter::expression).toList());
        parameters.put("missing", "x");
        var replayed = SqlNodeRenderer.renderBound(pending.rendered().residual(), parameters, evaluator);
        assertTrue(replayed.valuesComplete());
        assertEquals(List.of("before", "inside", "tail"), replayed.bindings().stream().map(SqlNodeRenderer.BoundParameter::expression).toList());
        assertEquals(List.of(1, 2, 3), replayed.bindings().stream().map(SqlNodeRenderer.BoundParameter::position).toList());
    }

    @Test void anUnfinishedLaterIterationAlsoRollsBackEarlierCompletedIterations() {
        var node = SqlNodeBuilder.buildScript("#{before}<foreach collection='ids' item='id'>#{id}<if test='id == 2'>${missing}</if></foreach>#{tail}");
        var parameters = new LinkedHashMap<String, Object>(Map.of("before", 1, "ids", List.of(1, 2), "tail", 3));
        var pending = SqlNodeRenderer.renderBound(node, parameters, evaluator);
        assertFalse(pending.rendered().complete());
        assertEquals(List.of("before"), pending.bindings().stream().map(SqlNodeRenderer.BoundParameter::expression).toList());
        parameters.put("missing", "x");
        var replayed = SqlNodeRenderer.renderBound(pending.rendered().residual(), parameters, evaluator);
        assertTrue(replayed.valuesComplete());
        assertEquals(List.of("before", "id", "id", "tail"), replayed.bindings().stream().map(SqlNodeRenderer.BoundParameter::expression).toList());
        assertEquals(List.of(1, 2, 3, 4), replayed.bindings().stream().map(SqlNodeRenderer.BoundParameter::position).toList());
    }

    @Test void budgetFailureClearsAllAppendedBindingsAndReplayStartsFresh() {
        var node = SqlNodeBuilder.buildScript("<bind name='name' value=\"name + '!'\"/>#{name}<foreach collection='ids' item='id'>#{id}</foreach>");
        var parameters = new LinkedHashMap<String, Object>(Map.of("name", "a", "ids", java.util.Collections.nCopies(10001, 1)));
        var pending = SqlNodeRenderer.renderBound(node, parameters, evaluator);
        assertFalse(pending.rendered().complete());
        assertTrue(pending.bindings().isEmpty());
        assertEquals("a", pending.rendered().parameters().get("name"));
        assertTrue(pending.rendered().diagnostics().stream().anyMatch(value -> value.code().equals("RENDER_LIMIT")));
        parameters.put("ids", List.of(2, 3));
        var replayed = SqlNodeRenderer.renderBound(pending.rendered().residual(), parameters, evaluator);
        assertEquals(List.of(new SqlValue.Known("a!"), new SqlValue.Known(2), new SqlValue.Known(3)),
                replayed.bindings().stream().map(SqlNodeRenderer.BoundParameter::value).toList());
        assertEquals(List.of(1, 2, 3), replayed.bindings().stream().map(SqlNodeRenderer.BoundParameter::position).toList());
    }
}
