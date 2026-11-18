package org.u80dc.jsqlint.core.node;

import org.junit.jupiter.api.Test;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 参数化渲染的顺序、部分完成、作用域和有界执行契约。 @author u80dc */
class SqlNodeBoundRendererTest {
    @Test void recoveryModeDistinguishesLiteralContinuationFromBoundReplay() {
        var node = SqlNodeBuilder.buildScript("SELECT #{first}<if test='enabled'>,#{second}</if>");
        var literal = SqlNodeRenderer.render(node, Map.of("first", 1));
        var bound = SqlNodeRenderer.renderBound(node, Map.of("first", 1), SqlExpressionEvaluator.simple());
        assertEquals(SqlNodeRenderer.RenderedSql.RecoveryMode.LITERAL_CONTINUATION, literal.recoveryMode());
        assertEquals(SqlNodeRenderer.RenderedSql.RecoveryMode.BOUND_REPLAY, bound.rendered().recoveryMode());
        assertEquals(SqlNodeRenderer.RenderedSql.RecoveryMode.LITERAL_CONTINUATION,
                new SqlNodeRenderer.RenderedSql(literal.sql(), literal.residual(), literal.parameters(), literal.diagnostics()).recoveryMode());
    }

    @Test void manuallyConstructedBindingsMustKeepRenderingOrder() {
        var rendered = SqlNodeRenderer.renderBound(SqlNodeBuilder.templateText("SELECT #{a},#{b}"),
                Map.of("a", 1, "b", 2), SqlExpressionEvaluator.simple());
        assertThrows(IllegalArgumentException.class, () -> new SqlNodeRenderer.BoundSql(rendered.rendered(),
                List.of(rendered.bindings().getLast(), rendered.bindings().getFirst())));
    }

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

    @Test void replayRebuildsPrefixBindingsAndKeepsLiteralQuestionMarksSeparate() {
        var node = SqlNodeBuilder.buildScript("SELECT '?' AS marker,#{first}<if test='enabled'>,#{second}</if>");
        var evaluator = SqlExpressionEvaluator.simple();
        var pending = SqlNodeRenderer.renderBound(node, Map.of("first", 1, "second", 2), evaluator);
        assertFalse(pending.rendered().complete());
        assertEquals(List.of("first"), pending.bindings().stream().map(SqlNodeRenderer.BoundParameter::expression).toList());

        var parameters = new LinkedHashMap<>(pending.rendered().parameters());
        parameters.put("enabled", true);
        var replayed = SqlNodeRenderer.renderBound(pending.rendered().residual(), parameters, evaluator);
        assertEquals("SELECT '?' AS marker,?,?", replayed.rendered().sql().orElseThrow());
        assertEquals(List.of(1, 2), replayed.bindings().stream().map(SqlNodeRenderer.BoundParameter::position).toList());
        assertEquals(List.of(new SqlValue.Known(1), new SqlValue.Known(2)),
                replayed.bindings().stream().map(SqlNodeRenderer.BoundParameter::value).toList());
        assertTrue(replayed.valuesComplete());
        assertEquals(SqlNodeRenderer.renderBound(node, parameters, evaluator), replayed);
    }

    @Test void replayCanCompletePreviouslyUnknownBindingsEvenWhenSqlWasAlreadyComplete() {
        var node = SqlNodeBuilder.templateText("SELECT #{first},#{second}");
        var evaluator = SqlExpressionEvaluator.simple();
        var pending = SqlNodeRenderer.renderBound(node, Map.of("first", 1), evaluator);
        assertTrue(pending.rendered().complete());
        assertFalse(pending.valuesComplete());

        var parameters = new LinkedHashMap<>(pending.rendered().parameters());
        parameters.put("second", 2);
        var replayed = SqlNodeRenderer.renderBound(pending.rendered().residual(), parameters, evaluator);
        assertTrue(replayed.valuesComplete());
        assertEquals(List.of(1, 2), replayed.bindings().stream().map(SqlNodeRenderer.BoundParameter::position).toList());
        assertEquals(List.of(new SqlValue.Known(1), new SqlValue.Known(2)),
                replayed.bindings().stream().map(SqlNodeRenderer.BoundParameter::value).toList());
        assertTrue(replayed.rendered().diagnostics().isEmpty());
    }

    @Test void replayStartsFromOriginalInputsAndRestoresBindAndForeachOrigins() {
        var node = SqlNodeBuilder.buildScript("""
                <bind name='name' value="name + '!'"/>SELECT #{name}<foreach collection='items' item='name'>,#{name}</foreach><if test='enabled'>,#{name}</if>
                """);
        var evaluator = SqlExpressionEvaluator.simple();
        var pending = SqlNodeRenderer.renderBound(node, Map.of("name", "outer", "items", List.of("a", "b")), evaluator);
        assertFalse(pending.rendered().complete());
        assertEquals("outer", pending.rendered().parameters().get("name"));

        var parameters = new LinkedHashMap<>(pending.rendered().parameters());
        parameters.put("enabled", true);
        var replayed = SqlNodeRenderer.renderBound(pending.rendered().residual(), parameters, evaluator);
        assertEquals(List.of(new SqlValue.Known("outer!"), new SqlValue.Known("a"), new SqlValue.Known("b"), new SqlValue.Known("outer!")),
                replayed.bindings().stream().map(SqlNodeRenderer.BoundParameter::value).toList());
        assertEquals(SqlNodeRenderer.renderBound(node, parameters, evaluator), replayed);
        var boundOrigin = assertInstanceOf(SqlValueOrigin.Expression.class, replayed.bindings().getLast().origin());
        assertEquals(List.of(new SqlValueOrigin.Input("name")), boundOrigin.inputs());
        var elementOrigin = assertInstanceOf(SqlValueOrigin.Expression.class, replayed.bindings().get(1).origin());
        assertEquals(new SqlValueOrigin.Input("items"), elementOrigin.inputs().getFirst());
        assertEquals("SELECT 'outer!','a','b','outer!'",
                SqlNodeRenderer.render(pending.rendered().residual(), parameters, evaluator).sql().orElseThrow().strip());
    }

    @Test void replayCanUseTheOriginalMapsImplicitNameFallback() {
        var node = SqlNodeBuilder.buildScript("SELECT #{alias}<if test='enabled'>,#{anotherAlias}</if>");
        var evaluator = SqlExpressionEvaluator.simple();
        var explicit = new LinkedHashMap<String, Object>();
        explicit.put("enabled", new SqlValue.Unknown("Not supplied"));
        Map<String, Object> parameters = new AbstractMap<>() {
            @Override public Set<Entry<String, Object>> entrySet() { return explicit.entrySet(); }
            @Override public boolean containsKey(Object key) { return key instanceof String; }
            @Override public Object get(Object key) { return explicit.containsKey(key) ? explicit.get(key) : 7; }
        };
        var pending = SqlNodeRenderer.renderBound(node, parameters, evaluator);
        assertFalse(pending.rendered().complete());
        assertEquals(new SqlValue.Known(7), pending.bindings().getFirst().value());
        explicit.put("enabled", true);
        var replayed = SqlNodeRenderer.renderBound(pending.rendered().residual(), parameters, evaluator);
        assertTrue(replayed.valuesComplete());
        assertEquals(List.of(new SqlValue.Known(7), new SqlValue.Known(7)),
                replayed.bindings().stream().map(SqlNodeRenderer.BoundParameter::value).toList());
        assertInstanceOf(SqlValue.Unknown.class, pending.rendered().parameters().get("enabled"));
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

    @Test void bindOriginsKeepTheTransformAndOnlyTheInputsThatWereRead() {
        var node = SqlNodeBuilder.buildScript("""
                <bind name='pattern' value="name + '!'"/>SELECT #{pattern},#{name}
                """);
        var result = SqlNodeRenderer.renderBound(node, Map.of("name", "abc", "unused", 9), SqlExpressionEvaluator.simple());
        assertEquals(new SqlValue.Known("abc!"), result.bindings().getFirst().value());
        var origin = assertInstanceOf(SqlValueOrigin.Expression.class, result.bindings().getFirst().origin());
        assertEquals("name + '!'", origin.expression());
        assertEquals(List.of(new SqlValueOrigin.Input("name")), origin.inputs());
        assertEquals(new SqlValueOrigin.Input("name"), result.bindings().getLast().origin());
    }

    @Test void iterationOriginsAndBindsStayLocalToTheIteration() {
        var node = SqlNodeBuilder.buildScript("""
                <foreach collection='values' item='value'>#{value}<bind name='value' value="value + '!'"/>#{value}</foreach>#{value}
                """);
        var result = SqlNodeRenderer.renderBound(node, Map.of("values", List.of("a", "b"), "value", "outside"), SqlExpressionEvaluator.simple());
        assertEquals(List.of("a", "a!", "b", "b!", "outside"), result.bindings().stream()
                .map(binding -> ((SqlValue.Known) binding.value()).value()).toList());
        assertEquals(new SqlValueOrigin.Input("value"), result.bindings().getLast().origin());
        var transformed = assertInstanceOf(SqlValueOrigin.Expression.class, result.bindings().get(1).origin());
        var element = assertInstanceOf(SqlValueOrigin.Expression.class, transformed.inputs().getFirst());
        assertEquals(new SqlValueOrigin.Input("values"), element.inputs().getFirst());
        assertEquals("outside", result.rendered().parameters().get("value"));
    }

    @Test void nestedMapReferencesAreRecordedWithoutExposingTrackingMaps() {
        var evaluator = SqlExpressionEvaluator.simple();
        var result = evaluator.evaluateWithReferences("params.id", Map.of("params", Map.of("id", 7, "unused", 9)), false);
        assertEquals(new SqlValue.Known(7), result.value());
        assertEquals(List.of(new SqlExpressionEvaluator.Reference("params", List.of("id"))), result.references());
        assertFalse(result.directReference());
        var map = evaluator.evaluateWithReferences("params", Map.of("params", Map.of("id", 7)), false);
        assertEquals(new SqlValue.Known(Map.of("id", 7)), map.value());
        assertFalse(((SqlValue.Known) map.value()).value().getClass().getName().contains("TrackedMap"));
    }

    @Test void presenceChecksKeepInputOriginsForPresentAndMissingNames() {
        SqlExpressionEvaluator evaluator = (expression, parameters) -> new SqlValue.Known(parameters.containsKey("flag"));
        var node = ValueSqlNode.parameter("hasFlag");
        var present = new LinkedHashMap<String, Object>();
        present.put("flag", null);
        for (Map<String, ?> parameters : List.<Map<String, ?>>of(present, Map.of())) {
            var evaluated = evaluator.evaluateWithReferences("hasFlag", parameters, true);
            assertEquals(List.of(new SqlExpressionEvaluator.Reference("flag", List.of())), evaluated.references());
            assertEquals(new SqlValue.Known(parameters.containsKey("flag")), evaluated.value());
            var bound = SqlNodeRenderer.renderBound(node, parameters, evaluator);
            var origin = assertInstanceOf(SqlValueOrigin.Expression.class, bound.bindings().getFirst().origin());
            assertEquals(List.of(new SqlValueOrigin.Input("flag")), origin.inputs());
        }
    }

    @Test void missingNamesAndNestedPropertiesRetainTheirReferences() {
        var evaluator = SqlExpressionEvaluator.simple();
        var absent = evaluator.evaluateWithReferences("missing", Map.of(), true);
        assertInstanceOf(SqlValue.Unknown.class, absent.value());
        assertEquals(List.of(new SqlExpressionEvaluator.Reference("missing", List.of())), absent.references());
        var nested = evaluator.evaluateWithReferences("params.missing", Map.of("params", Map.of("id", 7)), true);
        assertInstanceOf(SqlValue.Unknown.class, nested.value());
        assertEquals(List.of(new SqlExpressionEvaluator.Reference("params", List.of("missing"))), nested.references());
    }

    @Test void aRenderLimitRollsBackLocalBindingsAndLiteralRenderingUsesTheSameBudget() {
        var node = new MixedSqlNode(List.of(new BindSqlNode("name", "name + '!'"),
                new MixedSqlNode(Collections.nCopies(10001, new StaticTextSqlNode("x")))));
        var parameters = Map.of("name", "abc");
        var bound = SqlNodeRenderer.renderBound(node, parameters, SqlExpressionEvaluator.simple());
        assertEquals("abc", bound.rendered().parameters().get("name"));
        assertTrue(bound.bindings().isEmpty());
        var preview = SqlNodeRenderer.render(node, parameters);
        assertFalse(preview.complete()); assertEquals("abc", preview.parameters().get("name"));
        assertTrue(preview.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("RENDER_LIMIT")));
    }

    @Test void replayAfterARenderLimitUsesFreshBudgetAndOriginalBindInputs() {
        var node = SqlNodeBuilder.buildScript("""
                <bind name='name' value="name + '!'"/>SELECT #{name}<foreach collection='items' item='item'>,#{item}</foreach>
                """);
        var evaluator = SqlExpressionEvaluator.simple();
        var pending = SqlNodeRenderer.renderBound(node, Map.of("name", "outer", "items", Collections.nCopies(10001, 1)), evaluator);
        assertFalse(pending.rendered().complete());
        assertTrue(pending.bindings().isEmpty());
        assertEquals("outer", pending.rendered().parameters().get("name"));
        assertTrue(pending.rendered().diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("RENDER_LIMIT")));
        var parameters = new LinkedHashMap<>(pending.rendered().parameters());
        parameters.put("items", List.of(2, 3));
        var replayed = SqlNodeRenderer.renderBound(pending.rendered().residual(), parameters, evaluator);
        assertTrue(replayed.valuesComplete());
        assertEquals(List.of(new SqlValue.Known("outer!"), new SqlValue.Known(2), new SqlValue.Known(3)),
                replayed.bindings().stream().map(SqlNodeRenderer.BoundParameter::value).toList());
        assertTrue(replayed.rendered().diagnostics().isEmpty());
        assertEquals(SqlNodeRenderer.renderBound(node, parameters, evaluator), replayed);
    }
}
