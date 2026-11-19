package org.u80dc.jsqlint.core.node;

import org.junit.jupiter.api.Test;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SqlRenderingActivityScopeTest {
    @Test void bindsPreserveRawAndSavedArrayIdentityAcrossExpressions() {
        var node = SqlNodeBuilder.buildScript("<bind name='alias' value='ids'/><if test='alias == ids'>#{alias[0]}</if>");
        var evaluator = SqlExpressionEvaluator.simple();
        for (Object input : List.of(new int[]{7}, new SqlValue.Known(new int[]{7}))) {
            var bound = SqlNodeRenderer.renderBound(node, Map.of("ids", input), evaluator);
            assertEquals("?", bound.rendered().sql().orElseThrow());
            assertEquals(new SqlValue.Known(7), bound.bindings().getFirst().value());
            assertEquals("7", SqlNodeRenderer.render(node, Map.of("ids", input), evaluator).sql().orElseThrow());
        }
    }

    @Test void nestedArrayBindsAndForeachElementsShareTheProtectedActivityCopy() {
        var saved = new SqlValue.Known(Map.of("ids", new int[]{7}));
        var bound = SqlNodeRenderer.renderBound(SqlNodeBuilder.buildScript(
                "<bind name='alias' value='p.ids'/><if test='alias == p.ids'>#{alias[0]}</if>"),
                Map.of("p", saved), SqlExpressionEvaluator.simple());
        assertEquals("?", bound.rendered().sql().orElseThrow());
        assertEquals(new SqlValue.Known(7), bound.bindings().getFirst().value());

        var rows = new SqlValue.Known(new int[][]{{7}, {8}});
        var loop = SqlNodeBuilder.buildScript("<foreach collection='rows' item='row' index='i' separator=','>"
                + "<if test='row == rows[i]'>#{row[0]}</if></foreach>");
        var rendered = SqlNodeRenderer.renderBound(loop, Map.of("rows", rows), SqlExpressionEvaluator.simple());
        assertEquals("?,?", rendered.rendered().sql().orElseThrow());
        assertEquals(List.of(new SqlValue.Known(7), new SqlValue.Known(8)),
                rendered.bindings().stream().map(SqlNodeRenderer.BoundParameter::value).toList());
        assertArrayEquals(new int[]{7}, ((int[][]) rows.value())[0]);
    }

    @Test void customReferenceEvaluatorsRemainActiveInLiteralAndBoundModes() {
        SqlExpressionEvaluator custom = new SqlExpressionEvaluator() {
            @Override public SqlValue evaluate(String expression, Map<String, ?> parameters) { throw new AssertionError("Use reference extension"); }
            @Override public Evaluation evaluateWithReferences(String expression, Map<String, ?> parameters, boolean parameter) {
                return new Evaluation(new SqlValue.Known(parameters.get("id")), List.of(new Reference("id", List.of())), true);
            }
        };
        assertEquals("7", SqlNodeRenderer.render(ValueSqlNode.parameter("special"), Map.of("id", 7), custom).sql().orElseThrow());
        var bound = SqlNodeRenderer.renderBound(ValueSqlNode.parameter("special"), Map.of("id", 7), custom);
        assertEquals(new SqlValue.Known(7), bound.bindings().getFirst().value());
    }
}
