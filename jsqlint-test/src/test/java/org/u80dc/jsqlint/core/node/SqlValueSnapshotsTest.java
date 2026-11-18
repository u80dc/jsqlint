package org.u80dc.jsqlint.core.node;

import org.junit.jupiter.api.Test;
import org.u80dc.jsqlint.core.TemplateInferenceResult;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SqlValueSnapshotsTest {
    @Test void cleaningSharedKnownGraphsPreservesSharingAndRemovesTrackers() {
        SqlExpressionEvaluator evaluator = (expression, parameters) -> {
            Object item = parameters.get("params");
            for (int i = 0; i < 30; i++) item = new SqlValue.Known(List.of(item, item));
            return new SqlValue.Known(item);
        };
        var original = new LinkedHashMap<String, Object>(); original.put("id", 7);
        var result = evaluator.evaluateWithReferences("derived", Map.of("params", original), false);
        assertEquals(List.of(new SqlExpressionEvaluator.Reference("params", List.of())), result.references());
        original.put("id", 99);
        Object item = assertInstanceOf(SqlValue.Known.class, result.value()).value();
        for (int i = 0; i < 30; i++) {
            var children = assertInstanceOf(List.class, assertInstanceOf(SqlValue.Known.class, item).value());
            assertSame(children.get(0), children.get(1), "Shared immutable children must not be expanded into a tree");
            item = children.getFirst();
        }
        assertEquals(7, assertInstanceOf(Map.class, item).get("id"));
    }

    @Test void cleaningDerivedSetsDoesNotHashSharedGraphsOrRetainTrackingWrappers() {
        var leaf = new CountingValue(7);
        SqlExpressionEvaluator evaluator = (expression, parameters) -> {
            Object item = List.of(parameters.get("params"), leaf);
            for (int i = 0; i < 30; i++) item = List.of(item, item);
            Set<Object> set = Collections.newSetFromMap(new IdentityHashMap<>()); set.add(item);
            return new SqlValue.Known(set);
        };
        var original = new LinkedHashMap<String, Object>(); original.put("id", 7);
        var result = evaluator.evaluateWithReferences("derived", Map.of("params", original), false);
        assertInstanceOf(SqlValue.Known.class, result.value());
        assertEquals(0, leaf.hashes);
        assertEquals(List.of(new SqlExpressionEvaluator.Reference("params", List.of())), result.references());
        original.put("id", 99);
        Object item = ((Set<?>) ((SqlValue.Known) result.value()).value()).iterator().next();
        for (int i = 0; i < 30; i++) item = ((List<?>) item).getFirst();
        assertEquals(7, ((Map<?, ?>) ((List<?>) item).getFirst()).get("id"));
    }

    @Test void unsupportedCyclicExpressionValuesRemainUnknown() {
        var cycle = new LinkedHashMap<String, Object>(); cycle.put("self", cycle);
        var result = SqlExpressionEvaluator.simple().evaluateWithReferences("params", Map.of("params", cycle), false);
        assertInstanceOf(SqlValue.Unknown.class, result.value());
        assertEquals(List.of(new SqlExpressionEvaluator.Reference("params", List.of())), result.references());
    }
    @Test void sharedContainerGraphsAreComparedAndHashedOncePerUniqueValue() {
        var leftLeaf = new CountingValue(7); var rightLeaf = new CountingValue(7);
        Object left = leftLeaf, right = rightLeaf;
        for (int i = 0; i < 30; i++) { left = List.of(left, left); right = List.of(right, right); }
        var a = new SqlValue.Known(left); var b = new SqlValue.Known(right);
        assertEquals(a, b);
        assertEquals(1, leftLeaf.comparisons);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(1, leftLeaf.hashes); assertEquals(1, rightLeaf.hashes);

        Set<Object> set = Collections.newSetFromMap(new IdentityHashMap<>()); set.add(left);
        leftLeaf.hashes = 0;
        var capturedSet = new SqlValue.Known(set);
        assertEquals(0, leftLeaf.hashes, "Capturing Set membership does not hash its entire expression graph");
        assertEquals(1, ((Set<?>) capturedSet.value()).size());
        capturedSet.hashCode();
        assertEquals(1, leftLeaf.hashes);
    }

    private static final class CountingValue {
        private final int value;
        private int comparisons, hashes;
        private CountingValue(int value) { this.value = value; }
        @Override public boolean equals(Object other) { comparisons++; return other instanceof CountingValue item && value == item.value; }
        @Override public int hashCode() { hashes++; return value; }
    }

    @Test void derivedArraysDoNotKeepDependencyTrackersOrInputStorage() {
        var original = new LinkedHashMap<String, Object>(); original.put("id", 7);
        SqlExpressionEvaluator evaluator = (expression, parameters) -> new SqlValue.Known(new Object[]{parameters.get("params")});
        var evaluated = evaluator.evaluateWithReferences("derived", Map.of("params", original), false);
        assertEquals(List.of(new SqlExpressionEvaluator.Reference("params", List.of())), evaluated.references());
        original.put("id", 99);
        var array = assertInstanceOf(Object[].class, assertInstanceOf(SqlValue.Known.class, evaluated.value()).value());
        var map = assertInstanceOf(Map.class, array[0]);
        assertEquals(7, map.get("id"));
        assertThrows(UnsupportedOperationException.class, map::clear);
    }

    @Test void templateEnvironmentsDoNotExposeSavedConstantArrays() {
        int[] ids = {3, 4};
        var saved = new TemplateInferenceResult(Map.of(), Map.of(), Map.of(), Optional.empty(), Optional.empty(),
                Map.of("ids", ids), Optional.empty(), List.of(), List.of());
        ids[0] = 99;
        ((int[]) saved.environment(Map.of()).get("ids"))[1] = 88;
        ((int[]) saved.constants().get("ids"))[0] = 77;
        assertArrayEquals(new int[]{3, 4}, (int[]) saved.constants().get("ids"));
        assertArrayEquals(new int[]{3, 4}, (int[]) saved.environment(Map.of()).get("ids"));
    }
    @Test void capturingAnExpressionResultDoesNotInventInputDependencies() {
        var evaluator = SqlExpressionEvaluator.simple();
        var parameters = Map.of("params", Map.of("id", 7, "unused", 9));
        var root = evaluator.evaluateWithReferences("params", parameters, false);
        assertTrue(root.directReference());
        assertEquals(List.of(new SqlExpressionEvaluator.Reference("params", List.of())), root.references());
        var nested = evaluator.evaluateWithReferences("params.id", parameters, false);
        assertEquals(new SqlValue.Known(7), nested.value());
        assertEquals(List.of(new SqlExpressionEvaluator.Reference("params", List.of("id"))), nested.references());
    }
    @Test void nestedEvidenceDoesNotShareStorageWithInputsOrReturnedValues() {
        int[] numbers = {3, 4};
        var rows = new ArrayList<Object>(); rows.add(numbers);
        var original = new LinkedHashMap<String, Object>(); original.put("rows", rows); original.put("empty", null);
        var saved = new SqlValue.Known(original);
        int hash = saved.hashCode();
        numbers[0] = 99; rows.clear(); original.clear();

        var first = assertInstanceOf(Map.class, saved.value());
        var firstRows = assertInstanceOf(List.class, first.get("rows"));
        assertArrayEquals(new int[]{3, 4}, assertInstanceOf(int[].class, firstRows.getFirst()));
        assertTrue(first.containsKey("empty")); assertNull(first.get("empty"));
        assertThrows(UnsupportedOperationException.class, first::clear);
        assertThrows(UnsupportedOperationException.class, firstRows::clear);
        ((int[]) firstRows.getFirst())[0] = 88;
        var second = assertInstanceOf(Map.class, saved.value());
        assertArrayEquals(new int[]{3, 4}, (int[]) ((List<?>) second.get("rows")).getFirst());
        assertEquals(hash, saved.hashCode());
        var expected = new LinkedHashMap<String, Object>(); expected.put("rows", List.of(new int[]{3, 4})); expected.put("empty", null);
        assertEquals(new SqlValue.Known(expected), saved);
        assertEquals(new SqlValue.Known(expected).hashCode(), saved.hashCode());
    }

    @Test void arraysKeepTheirRuntimeTypesAndNestedArrayEqualityUsesContents() {
        String[][] original = {{"a"}, {"b"}};
        var saved = new SqlValue.Known(original);
        original[0][0] = "changed";
        var exposed = assertInstanceOf(String[][].class, saved.value());
        exposed[1][0] = "changed again";
        assertEquals(new SqlValue.Known(new String[][]{{"a"}, {"b"}}), saved);
        assertNotEquals(new SqlValue.Known(new Object[]{"a"}), new SqlValue.Known(new String[]{"a"}));
    }

    @Test void aBoundReplayUsesTheSavedArrayInsteadOfLaterCallerChanges() {
        int[] ids = {3, 4};
        var template = SqlNodeBuilder.buildScript("SELECT <foreach collection='ids' item='id' separator=','>#{id}</foreach>");
        var evaluator = SqlExpressionEvaluator.simple();
        var saved = SqlNodeRenderer.renderBound(template, Map.of("ids", ids), evaluator);
        ids[0] = 99;
        ((int[]) saved.rendered().parameters().get("ids"))[1] = 88;
        var replayed = SqlNodeRenderer.renderBound(saved.rendered().residual(), saved.rendered().parameters(), evaluator);
        assertEquals(saved.rendered().sql(), replayed.rendered().sql());
        assertEquals(List.of(new SqlValue.Known(3), new SqlValue.Known(4)),
                replayed.bindings().stream().map(SqlNodeRenderer.BoundParameter::value).toList());
    }

    @Test void cyclicOrUnfreezableContainersFailAtTheSnapshotBoundary() {
        var cycle = new ArrayList<Object>(); cycle.add(cycle);
        assertThrows(IllegalArgumentException.class, () -> new SqlValue.Known(cycle));
        ArrayList<?>[] concreteLists = {new ArrayList<>(List.of(1))};
        assertThrows(IllegalArgumentException.class, () -> new SqlValue.Known(concreteLists));
    }
}
