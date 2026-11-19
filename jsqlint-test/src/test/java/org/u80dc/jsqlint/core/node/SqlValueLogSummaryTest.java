package org.u80dc.jsqlint.core.node;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SqlValueLogSummaryTest {
    @Test void scalarSummariesKeepTheirExistingText() {
        assertEquals("Known[value=null]", new SqlValue.Known(null).toString());
        assertEquals("Known[value=7]", new SqlValue.Known(7).toString());
        assertEquals("Known[value=text]", new SqlValue.Known("text").toString());
    }

    @Test void sharedContainerAndKnownGraphsNeverExpandTheirChildrenForLogging() {
        Object item = new UnsafeText();
        for (int i = 0; i < 60; i++) item = new SqlValue.Known(List.of(item, item));
        Object shared = item;
        for (Object value : List.of(shared, List.of(shared, shared), new Object[]{shared, shared},
                Map.of("value", shared), Set.of(shared))) {
            String text = assertDoesNotThrow(() -> new SqlValue.Known(value).toString());
            assertTrue(text.length() < 150, text);
        }
        assertEquals("Known[value=List(size=2)]", new SqlValue.Known(List.of(shared, shared)).toString());
    }

    @Test void ordinaryObjectsAreSummarizedWithoutCallingTheirToString() {
        assertEquals("Known[value=" + UnsafeText.class.getName() + "]", new SqlValue.Known(new UnsafeText()).toString());
    }

    @Test void longScalarsAreBoundedBeforeTheyAreCombinedIntoLogMessages() {
        var hugeInteger = java.math.BigInteger.ONE.shiftLeft(100000);
        for (Object value : List.of("x".repeat(1000000), hugeInteger, new java.math.BigDecimal(hugeInteger))) {
            String text = new SqlValue.Known(value).toString();
            assertTrue(text.length() < 1100);
            assertTrue(text.contains("…"));
        }
    }

    private static final class UnsafeText {
        @Override public String toString() { fail("Logging must not invoke a business object's toString"); return "unreachable"; }
    }
}
