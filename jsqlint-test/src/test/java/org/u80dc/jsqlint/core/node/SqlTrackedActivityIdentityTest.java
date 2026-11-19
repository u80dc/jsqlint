package org.u80dc.jsqlint.core.node;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqlTrackedActivityIdentityTest {
    @Test void repeatedSavedArrayReadsKeepIdentityAndOnlyRecordTheRootReference() {
        var array = new SqlValue.Known(new int[]{7});
        var evaluated = SqlExpressionEvaluator.simple().evaluateWithReferences("a == a", Map.of("a", array), false);
        assertEquals(new SqlValue.Known(true), evaluated.value());
        assertEquals(List.of(new SqlExpressionEvaluator.Reference("a", List.of())), evaluated.references());
        var aliases = SqlExpressionEvaluator.simple().evaluateWithReferences("a == b", Map.of("a", array, "b", array), false);
        assertEquals(new SqlValue.Known(true), aliases.value());
        assertEquals(List.of(new SqlExpressionEvaluator.Reference("a", List.of()),
                new SqlExpressionEvaluator.Reference("b", List.of())), aliases.references());
    }

    @Test void reflexiveMapComparisonDoesNotEnumerateUnusedFields() {
        for (Object value : List.of(Map.of("id", 7, "unused", 9), new SqlValue.Known(Map.of("id", 7, "unused", 9)))) {
            var result = SqlExpressionEvaluator.simple().evaluateWithReferences("p == p", Map.of("p", value), false);
            assertEquals(new SqlValue.Known(true), result.value());
            assertEquals(List.of(new SqlExpressionEvaluator.Reference("p", List.of())), result.references());
        }
    }

    @Test void aliasMapsStillKeepTheReferencesOfEachAccessPath() {
        var value = Map.of("id", 7);
        var result = SqlExpressionEvaluator.simple().evaluateWithReferences("left.id == right.id",
                Map.of("left", value, "right", value), false);
        assertEquals(new SqlValue.Known(true), result.value());
        assertEquals(List.of(new SqlExpressionEvaluator.Reference("left", List.of("id")),
                new SqlExpressionEvaluator.Reference("right", List.of("id"))), result.references());
    }

    @Test void customEvaluatorsCannotMutateArraysInsidePersistedKnownValues() {
        var savedArray = new SqlValue.Known(new int[]{7});
        var savedMap = new SqlValue.Known(Map.of("ids", new int[]{8}));
        SqlExpressionEvaluator custom = (expression, parameters) -> {
            int[] direct = (int[]) ((SqlValue.Known) parameters.get("array")).value();
            direct[0] = 99;
            Map<?, ?> map = (Map<?, ?>) ((SqlValue.Known) parameters.get("map")).value();
            ((int[]) map.get("ids"))[0] = 88;
            return new SqlValue.Known(true);
        };
        var result = custom.evaluateWithReferences("custom", Map.of("array", savedArray, "map", savedMap), false);
        assertEquals(new SqlValue.Known(true), result.value());
        assertArrayEquals(new int[]{7}, (int[]) savedArray.value());
        assertArrayEquals(new int[]{8}, (int[]) ((Map<?, ?>) savedMap.value()).get("ids"));
        assertEquals(List.of(new SqlExpressionEvaluator.Reference("array", List.of()),
                new SqlExpressionEvaluator.Reference("map", List.of("ids"))), result.references());
    }
}
