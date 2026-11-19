package org.u80dc.jsqlint.core.node;

import org.junit.jupiter.api.Test;
import org.u80dc.jsqlint.core.ReplayBudgets;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SqlExpressionResultContractTest {
    private static final SqlNode ARRAY_ALIAS = SqlNodeBuilder.buildScript(
            "<bind name='alias' value='params.ids'/><if test='alias == params.ids'>#{alias[0]}</if>");

    @Test void transparentDelegationPreservesRawAndSavedPropertyArrayAliases() {
        var simple = SqlExpressionEvaluator.simple();
        SqlExpressionEvaluator delegate = (expression, parameters) -> simple.evaluate(expression, parameters);
        for (var evaluator : List.of(simple, delegate)) {
            for (Object params : List.of(Map.of("ids", new int[]{7}), new SqlValue.Known(Map.of("ids", new int[]{7})))) {
                var bound = SqlNodeRenderer.renderBound(ARRAY_ALIAS, Map.of("params", params), evaluator);
                assertEquals("?", bound.rendered().sql().orElseThrow());
                assertEquals(new SqlValue.Known(7), bound.bindings().getFirst().value());
                assertEquals("7", SqlNodeRenderer.render(ARRAY_ALIAS, Map.of("params", params), evaluator).sql().orElseThrow());
            }
        }
    }

    @Test void recordAndBeanPropertiesPreserveArrayIdentityAndStructuredSource() {
        var simple = SqlExpressionEvaluator.simple();
        SqlExpressionEvaluator delegate = (expression, parameters) -> simple.evaluate(expression, parameters);
        for (Object params : List.of(new Params(new int[]{7}), new Bean(new int[]{7}))) {
            var evaluated = delegate.evaluateWithReferences("params.ids", Map.of("params", params), false);
            assertEquals(List.of(new SqlExpressionEvaluator.Reference("params", List.of("ids"))), evaluated.references());
            assertFalse(evaluated.directReference());
            assertEquals(Optional.of(evaluated.references().getFirst()), evaluated.activity().orElseThrow().reference());
            assertEquals("?", SqlNodeRenderer.renderBound(ARRAY_ALIAS, Map.of("params", params), delegate).rendered().sql().orElseThrow());
        }
    }

    @Test void ignoredNestedResultsCannotReplaceTheReturnedResultsActivity() {
        var simple = SqlExpressionEvaluator.simple();
        SqlExpressionEvaluator delegate = (expression, parameters) -> {
            simple.evaluateWithReferences("other", parameters, false);
            return simple.evaluate(expression, parameters);
        };
        var params = Map.<String, Object>of("params", Map.of("ids", new int[]{7}), "other", new int[]{99});
        assertEquals("7", SqlNodeRenderer.render(ARRAY_ALIAS, params, delegate).sql().orElseThrow());
        var result = delegate.evaluateWithReferences("42", params, false);
        assertEquals(new SqlValue.Known(new java.math.BigDecimal("42")), result.value());
        assertTrue(result.activity().orElseThrow().reference().isEmpty());
    }

    @Test void legacyDirectReferenceFlagOnlyDenotesBareRootReferences() {
        var evaluator = SqlExpressionEvaluator.simple();
        var input = Map.of("params", Map.of("id", 7));
        var root = evaluator.evaluateWithReferences(" params ", input, false);
        assertTrue(root.directReference());
        assertEquals(Optional.of(new SqlExpressionEvaluator.Reference("params", List.of())),
                root.activity().orElseThrow().reference());
        var property = evaluator.evaluateWithReferences("params.id", input, false);
        assertFalse(property.directReference());
        assertEquals(Optional.of(new SqlExpressionEvaluator.Reference("params", List.of("id"))),
                property.activity().orElseThrow().reference());
        assertFalse(evaluator.evaluateWithReferences("(params)", input, false).directReference());
    }

    @Test void getterSourcesKeepObjectArrayAndNestedContainerIdentity() {
        var simple = SqlExpressionEvaluator.simple();
        SqlExpressionEvaluator delegate = (expression, parameters) -> simple.evaluate(expression, parameters);
        var node = SqlNodeBuilder.buildScript("<bind name='alias' value='params.ids'/><if test='alias == params.ids'>yes</if>");
        for (Object ids : List.of(new String[]{"seven"}, new Object[]{"seven"},
                new Object[]{List.of(new String[]{"seven"})}, List.of((Object) new String[]{"seven"}),
                Map.of("row", new String[]{"seven"}))) {
            for (Object params : List.of(new ObjectParams(ids), new ObjectBean(ids))) {
                for (var evaluator : List.of(simple, delegate)) {
                    var context = new SqlEvaluationContext(ReplayBudgets.defaults(), () -> {});
                    var result = evaluator.evaluateWithReferences("params.ids", Map.of("params", params), false, context);
                    assertSame(ids, result.activity().orElseThrow().value(context));
                    assertEquals("yes", SqlNodeRenderer.render(node, Map.of("params", params), evaluator).sql().orElseThrow());
                }
            }
        }
    }

    @Test void arraysReachedThroughGetterContainersKeepBindingIdentityAndSnapshots() {
        var simple = SqlExpressionEvaluator.simple();
        SqlExpressionEvaluator delegate = (expression, parameters) -> simple.evaluate(expression, parameters);
        String[] row = {"seven"};
        Object ids = Map.of("rows", List.of((Object) row));
        var node = SqlNodeBuilder.buildScript("<bind name='alias' value='params.ids.rows[0]'/><if test='alias == params.ids.rows[0]'>#{alias[0]}</if>");
        for (Object params : List.of(new ObjectParams(ids), new ObjectBean(ids))) {
            for (var evaluator : List.of(simple, delegate)) {
                var bound = SqlNodeRenderer.renderBound(node, Map.of("params", params), evaluator);
                assertEquals("?", bound.rendered().sql().orElseThrow());
                assertEquals(new SqlValue.Known("seven"), bound.bindings().getFirst().value());
            }
        }
        var context = new SqlEvaluationContext(ReplayBudgets.defaults(), () -> {});
        var result = delegate.evaluateWithReferences("params.ids.rows[0]", Map.of("params", new ObjectParams(ids)), false, context);
        assertSame(row, result.activity().orElseThrow().value(context));
        row[0] = "changed";
        assertArrayEquals(new String[]{"seven"}, (String[]) ((SqlValue.Known) result.value()).value());
    }

    @Test void activityAndPersistentSnapshotHaveSeparateLifetimes() {
        int[] array = {7};
        var context = new SqlEvaluationContext(ReplayBudgets.defaults(), () -> {});
        var result = SqlExpressionEvaluator.simple().evaluateWithReferences("ids", Map.of("ids", array), false, context);
        assertSame(array, result.activity().orElseThrow().value(context));
        array[0] = 9;
        var saved = assertInstanceOf(SqlValue.Known.class, result.value());
        assertArrayEquals(new int[]{7}, (int[]) saved.value());
        ((int[]) saved.value())[0] = 11;
        assertArrayEquals(new int[]{7}, (int[]) saved.value());
        assertThrows(IllegalArgumentException.class, () -> result.activity().orElseThrow()
                .value(new SqlEvaluationContext(ReplayBudgets.defaults(), () -> {})));
    }

    @Test void parserCapacityIsCarriedByTheResultThroughTransparentDelegation() {
        var simple = SqlExpressionEvaluator.simple();
        SqlExpressionEvaluator delegate = (expression, parameters) -> simple.evaluate(expression, parameters);
        for (var evaluator : List.of(simple, delegate)) {
            var limits = new ArrayList<String>();
            var context = new SqlEvaluationContext(ReplayBudgets.defaults().withSteps(1), () -> {}, limits::add);
            var result = evaluator.evaluateWithReferences("1 + 1", Map.of(), false, context);
            assertInstanceOf(SqlValue.Unknown.class, result.value());
            assertEquals(Optional.of("Expression parser work budget exceeded"), result.limit());
            assertTrue(result.activity().isEmpty());
            assertEquals(1, limits.size());
        }
    }

    @Test void ignoredLegacyResultsAreBoundedWithinOneEvaluation() {
        var simple = SqlExpressionEvaluator.simple();
        SqlExpressionEvaluator delegate = (expression, parameters) -> {
            for (int i = 0; i < 20; i++) simple.evaluate("1", parameters);
            return simple.evaluate(expression, parameters);
        };
        var limits = new ArrayList<String>();
        var context = new SqlEvaluationContext(ReplayBudgets.defaults().withSteps(5), () -> {}, limits::add);
        var result = delegate.evaluateWithReferences("2", Map.of(), false, context);
        assertInstanceOf(SqlValue.Unknown.class, result.value());
        assertEquals(Optional.of("Expression result bridge work budget exceeded"), result.limit());
        assertEquals(1, limits.size());
    }

    @Test void legacyReferenceConstructorStillRestoresDirectInputIdentity() {
        var simple = SqlExpressionEvaluator.simple();
        SqlExpressionEvaluator legacy = new SqlExpressionEvaluator() {
            public SqlValue evaluate(String expression, Map<String, ?> parameters) { return simple.evaluate(expression, parameters); }
            public Evaluation evaluateWithReferences(String expression, Map<String, ?> parameters, boolean parameter) {
                if (expression.equals("params.ids")) {
                    Object ids = ((Map<?, ?>) parameters.get("params")).get("ids");
                    return new Evaluation(new SqlValue.Known(ids), List.of(new Reference("params", List.of("ids"))), true);
                }
                return simple.evaluateWithReferences(expression, parameters, parameter);
            }
        };
        assertEquals("7", SqlNodeRenderer.render(ARRAY_ALIAS, Map.of("params", Map.of("ids", new int[]{7})), legacy).sql().orElseThrow());
    }

    @Test void referenceExtensionsCanExplicitlySupplyAnActivityValue() {
        var simple = SqlExpressionEvaluator.simple();
        SqlExpressionEvaluator extension = new SqlExpressionEvaluator() {
            public SqlValue evaluate(String expression, Map<String, ?> parameters) { return simple.evaluate(expression, parameters); }
            public Evaluation evaluateWithReferences(String expression, Map<String, ?> parameters, boolean parameter) {
                if (expression.equals("custom")) {
                    Object ids = parameters.get("ids");
                    var reference = new Reference("ids", List.of());
                    return new Evaluation(new SqlValue.Known(ids), List.of(reference), true,
                            Optional.of(SqlEvaluationContext.current().activityValue(ids, reference)), Optional.empty());
                }
                return simple.evaluateWithReferences(expression, parameters, parameter);
            }
        };
        var node = SqlNodeBuilder.buildScript("<bind name='alias' value='custom'/><if test='alias == ids'>#{alias[0]}</if>");
        assertEquals("7", SqlNodeRenderer.render(node, Map.of("ids", new int[]{7}), extension).sql().orElseThrow());
    }

    private record Params(int[] ids) {}
    private record ObjectParams(Object ids) {}
    private static final class ObjectBean {
        private final Object ids;
        private ObjectBean(Object ids) { this.ids = ids; }
        public Object getIds() { return ids; }
    }
    private static final class Bean {
        private final int[] ids;
        private Bean(int[] ids) { this.ids = ids; }
        public int[] getIds() { return ids; }
    }
}
