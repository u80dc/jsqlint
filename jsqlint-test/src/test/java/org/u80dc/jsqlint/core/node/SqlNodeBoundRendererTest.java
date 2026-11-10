package org.u80dc.jsqlint.core.node;

import org.junit.jupiter.api.Test;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 参数化渲染的顺序、部分完成、作用域和有界执行契约。 @author u80dc */
class SqlNodeBoundRendererTest {
    @Test void bindingValuesStaySeparateFromSqlAndUnknownStillHasAPlaceholder() {
        var node = SqlNodeBuilder.templateText("SELECT * FROM ${table} WHERE a=#{id,jdbcType=INTEGER} OR b=#{id} OR c=#{missing}");
        var result = SqlNodeRenderer.renderBound(node, Map.of("table", "users", "id", 7), SqlExpressionEvaluator.simple());
        assertEquals("SELECT * FROM users WHERE a=? OR b=? OR c=?", result.rendered().sql().orElseThrow());
        assertEquals(List.of(1, 2, 3), result.bindings().stream().map(SqlNodeRenderer.BoundParameter::position).toList());
        assertEquals(List.of("id", "id", "missing"), result.bindings().stream().map(SqlNodeRenderer.BoundParameter::expression).toList());
        assertEquals("jdbcType=INTEGER", result.bindings().getFirst().options());
        assertEquals(new SqlValue.Known(7), result.bindings().getFirst().value());
        assertInstanceOf(SqlValue.Unknown.class, result.bindings().getLast().value());
        assertTrue(result.rendered().complete()); assertFalse(result.valuesComplete());
        assertTrue(result.rendered().diagnostics().stream().anyMatch(value -> value.code().equals("UNKNOWN_PARAMETER")));
        assertThrows(UnsupportedOperationException.class, () -> result.bindings().clear());
        assertEquals("SELECT 'O''Neil'", SqlNodeRenderer.render(SqlNodeBuilder.templateText("SELECT #{name}"), Map.of("name", "O'Neil")).sql().orElseThrow());
    }

    @Test void branchesAndLoopsKeepBindingOrderAndDoNotLeakIterationVariables() {
        var node = SqlNodeBuilder.buildScript("""
                SELECT #{first}<if test='enabled'>,#{second}</if>
                FROM t WHERE id IN <foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach>
                AND last=#{id}
                """);
        var result = SqlNodeRenderer.renderBound(node, Map.of("first", 1, "enabled", true, "second", 2, "ids", List.of(3, 4), "id", 9), SqlExpressionEvaluator.simple());
        assertEquals(List.of(1, 2, 3, 4, 9), result.bindings().stream().map(binding -> ((SqlValue.Known) binding.value()).value()).toList());
        assertEquals(List.of(1, 2, 3, 4, 5), result.bindings().stream().map(SqlNodeRenderer.BoundParameter::position).toList());
        assertEquals(9, result.rendered().parameters().get("id"));
        assertTrue(result.valuesComplete());
    }

    @Test void unfinishedLoopDiscardsItsBindingSnapshotAndRetainsTheOuterBindings() {
        var node = new MixedSqlNode(List.of(ValueSqlNode.parameter("outer"), new ForEachSqlNode("items", "item", "", "(", ")", ",",
                new MixedSqlNode(List.of(ValueSqlNode.parameter("item"), ValueSqlNode.raw("suffix"))))));
        var result = SqlNodeRenderer.renderBound(node, Map.of("outer", 5, "items", List.of(1, 2)), SqlExpressionEvaluator.simple());
        assertFalse(result.rendered().complete());
        assertEquals(1, result.bindings().size()); assertEquals(new SqlValue.Known(5), result.bindings().getFirst().value());
        assertFalse(result.rendered().parameters().containsKey("item"));
    }

    @Test void renderingIsBoundedAndCancellationIsNotConvertedToAnUnknownValue() {
        var node = new ForEachSqlNode("items", "item", "", "", "", ",", ValueSqlNode.parameter("item"));
        var result = SqlNodeRenderer.renderBound(node, Map.of("items", Collections.nCopies(10001, 1)), SqlExpressionEvaluator.simple());
        assertFalse(result.rendered().complete()); assertTrue(result.bindings().isEmpty());
        assertTrue(result.rendered().diagnostics().stream().anyMatch(value -> value.code().equals("RENDER_LIMIT")));
        var stop = new IllegalStateException("cancelled");
        assertSame(stop, assertThrows(IllegalStateException.class, () -> SqlNodeRenderer.renderBound(node, Map.of(),
                SqlExpressionEvaluator.simple(), () -> { throw stop; })));
    }
}
