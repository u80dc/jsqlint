package org.u80dc.jsqlint.core.node;

import org.junit.jupiter.api.Test;
import org.u80dc.jsqlint.core.ReplayBudgets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SqlReferenceTrackingCapacityTest {
    @Test void wideMapComparisonsKeepOnlyLeafReadsInTheirFirstAccessOrder() {
        Map<String, Object> left = wide(1200), right = wide(1200);
        var result = SqlExpressionEvaluator.simple().evaluateWithReferences("left == right", Map.of("left", left, "right", right),
                false, new SqlEvaluationContext(ReplayBudgets.defaults().withSteps(50000), () -> {}));
        assertEquals(new SqlValue.Known(true), result.value());
        assertEquals(2400, result.references().size());
        assertEquals(new SqlExpressionEvaluator.Reference("left", List.of("id0")), result.references().getFirst());
        assertEquals(new SqlExpressionEvaluator.Reference("right", List.of("id1199")), result.references().getLast());
        assertTrue(result.references().stream().allMatch(reference -> reference.path().size() == 1));
    }

    @Test void aReferenceBudgetProducesUnknownAndARenderLimitDiagnostic() {
        var parameters = Map.of("left", wide(50), "right", wide(50));
        var limits = new ArrayList<String>();
        var context = new SqlEvaluationContext(ReplayBudgets.defaults().withSteps(20), () -> {}, limits::add);
        var result = SqlExpressionEvaluator.simple().evaluateWithReferences("left == right", parameters, false, context);
        assertInstanceOf(SqlValue.Unknown.class, result.value());
        assertEquals(1, limits.size());
        assertFalse(result.references().isEmpty());
        var rendered = SqlNodeRenderer.renderBound(ValueSqlNode.parameter("left == right"), parameters,
                SqlExpressionEvaluator.simple(), ReplayBudgets.defaults().withSteps(20), () -> {});
        assertFalse(rendered.valuesComplete());
        assertTrue(rendered.rendered().diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("RENDER_LIMIT")));
    }

    @Test void cancellationDuringActualNestedReadsEscapesBothEvaluatorAndRenderer() {
        var reads = new AtomicInteger();
        Map<String, Object> values = new LinkedHashMap<>(wide(200)) {
            @Override public Object get(Object key) { reads.incrementAndGet(); return super.get(key); }
        };
        SqlExpressionEvaluator custom = (expression, parameters) -> {
            Map<?, ?> observed = (Map<?, ?>) parameters.get("values");
            for (int i = 0; i < 200; i++) observed.get("id" + i);
            return new SqlValue.Known(true);
        };
        var cancellation = new IllegalStateException("cancelled while reading inputs");
        Runnable checkpoint = () -> { if (reads.get() >= 5) throw cancellation; };
        var context = new SqlEvaluationContext(ReplayBudgets.defaults(), checkpoint);
        assertSame(cancellation, assertThrows(IllegalStateException.class,
                () -> custom.evaluateWithReferences("scan", Map.of("values", values), false, context)));
        assertTrue(reads.get() >= 5);
        reads.set(0);
        assertSame(cancellation, assertThrows(IllegalStateException.class,
                () -> SqlNodeRenderer.renderBound(ValueSqlNode.parameter("scan"), Map.of("values", values), custom,
                        new SqlEvaluationContext(ReplayBudgets.defaults(), checkpoint))));
        assertTrue(reads.get() >= 5);
    }

    @Test void nestedMapComparisonStopsBeforeRecursiveEqualityOverflowsTheStack() {
        var limits = new ArrayList<String>();
        var context = new SqlEvaluationContext(ReplayBudgets.defaults().withSteps(100000000), () -> {}, limits::add);
        var result = SqlExpressionEvaluator.simple().evaluateWithReferences("left == right",
                Map.of("left", nested(10000), "right", nested(10000)), false, context);
        assertInstanceOf(SqlValue.Unknown.class, result.value());
        assertEquals(1, limits.size());
        assertTrue(limits.getFirst().contains("path depth"));
        assertTrue(result.references().stream().allMatch(reference -> reference.path().size() <= 128));
    }

    @Test void configuredPathDepthAlsoAppliesToCustomEvaluatorsActualReads() {
        SqlExpressionEvaluator custom = (expression, parameters) -> {
            Map<?, ?> value = (Map<?, ?>) parameters.get("value");
            for (int i = 0; i < 20; i++) value = (Map<?, ?>) value.get("child");
            return new SqlValue.Known(value.get("id"));
        };
        var limits = new ArrayList<String>();
        var context = new SqlEvaluationContext(ReplayBudgets.defaults().withDepth(4), () -> {}, limits::add);
        var result = custom.evaluateWithReferences("nested", Map.of("value", nested(20)), false, context);
        assertInstanceOf(SqlValue.Unknown.class, result.value());
        assertEquals(1, limits.size());
        assertTrue(limits.getFirst().contains("path depth"));
        assertTrue(result.references().stream().allMatch(reference -> reference.path().size() <= 4));
    }

    private static Map<String, Object> nested(int depth) {
        Map<String, Object> result = Map.of("id", 7);
        for (int i = 0; i < depth; i++) result = Map.of("child", result);
        return result;
    }

    private static Map<String, Object> wide(int count) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < count; i++) result.put("id" + i, i);
        return result;
    }
}
