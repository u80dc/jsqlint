package org.u80dc.jsqlint.core.node;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.lang.reflect.Array;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 已知值（可以是 null）与未知值不能相互替代。
 *
 * @author u80dc
 */
public sealed interface SqlValue permits SqlValue.Known, SqlValue.Unknown {
    default Truth truth() {
        if (this instanceof Unknown) {
            return Truth.UNKNOWN;
        }
        Object value = ((Known) this).value;
        return switch (value) {
            case null -> Truth.FALSE;
            case Boolean bool -> bool ? Truth.TRUE : Truth.FALSE;
            case BigDecimal decimal -> decimal.signum() == 0 ? Truth.FALSE : Truth.TRUE;
            case BigInteger integer -> integer.signum() == 0 ? Truth.FALSE : Truth.TRUE;
            case Number number -> number.doubleValue() == 0 ? Truth.FALSE : Truth.TRUE;
            default -> Truth.TRUE;
        };
    }

    enum Truth {TRUE, FALSE, UNKNOWN}

    record Known(Object value) implements SqlValue {
        private static final int SUMMARY_LIMIT = 1024;
        private static final Set<Class<?>> SCALAR_TYPES = Set.of(String.class, Boolean.class, Character.class,
                Byte.class, Short.class, Integer.class, Long.class, Float.class, Double.class,
                BigDecimal.class, BigInteger.class);
        public Known { value = SqlValueSnapshots.capture(value); }
        @Override public Object value() { return SqlValueSnapshots.capture(value); }
        Object snapshotValue() { return value; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof Known known && SqlValueSnapshots.sameValue(value, known.value);
        }
        @Override public int hashCode() { return SqlValueSnapshots.contentHash(value); }
        /** Logging a saved value must not copy or recursively expand its container graph. */
        @Override public String toString() {
            String summary;
            if (value == null || SCALAR_TYPES.contains(value.getClass())) summary = scalarSummary(value);
            else if (SqlEvaluationTracker.isTrackedValue(value)) summary = "tracked Map";
            else if (value.getClass().isArray()) summary = value.getClass().getTypeName() + "(length=" + Array.getLength(value) + ")";
            else if (value instanceof List<?> list) summary = "List(size=" + list.size() + ")";
            else if (value instanceof Set<?> set) summary = "Set(size=" + set.size() + ")";
            else if (value instanceof Map<?, ?> map) summary = "Map(size=" + map.size() + ")";
            else summary = value.getClass().getName();
            return "Known[value=" + summary + "]";
        }
        private static String scalarSummary(Object value) {
            // Avoid first materializing a huge decimal representation just to shorten it.
            if (value instanceof BigInteger integer && integer.bitLength() > 3400)
                return "BigInteger(bitLength=" + integer.bitLength() + ")…";
            if (value instanceof BigDecimal decimal && decimal.unscaledValue().bitLength() > 3400)
                return "BigDecimal(unscaledBitLength=" + decimal.unscaledValue().bitLength() + ", scale=" + decimal.scale() + ")…";
            String text = String.valueOf(value);
            return text.length() <= SUMMARY_LIMIT ? text : text.substring(0, SUMMARY_LIMIT - 1) + "…";
        }
    }

    record Unknown(String reason) implements SqlValue {
        public Unknown {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
