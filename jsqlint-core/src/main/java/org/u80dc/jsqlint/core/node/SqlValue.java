package org.u80dc.jsqlint.core.node;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

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
        public Known { value = SqlValueSnapshots.capture(value); }
        @Override public Object value() { return SqlValueSnapshots.capture(value); }
        Object snapshotValue() { return value; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof Known known && SqlValueSnapshots.sameValue(value, known.value);
        }
        @Override public int hashCode() { return SqlValueSnapshots.contentHash(value); }
    }

    record Unknown(String reason) implements SqlValue {
        public Unknown {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
