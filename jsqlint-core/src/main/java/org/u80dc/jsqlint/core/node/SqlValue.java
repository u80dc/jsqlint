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
        Object value = ((Known) this).value();
        if (value == null) {
            return Truth.FALSE;
        }
        if (value instanceof Boolean bool) {
            return bool ? Truth.TRUE : Truth.FALSE;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.signum() == 0 ? Truth.FALSE : Truth.TRUE;
        }
        if (value instanceof BigInteger integer) {
            return integer.signum() == 0 ? Truth.FALSE : Truth.TRUE;
        }
        if (value instanceof Number number) {
            return number.doubleValue() == 0 ? Truth.FALSE : Truth.TRUE;
        }
        return Truth.TRUE;
    }

    enum Truth {TRUE, FALSE, UNKNOWN}

    record Known(Object value) implements SqlValue {
    }

    record Unknown(String reason) implements SqlValue {
        public Unknown {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
