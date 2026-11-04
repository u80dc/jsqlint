package org.u80dc.jsqlint.core.node;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlValueTest {
    @Test
    void nonzeroNumbersRemainTrueWithoutLossyConversion() {
        for (Number number : List.<Number>of(new BigDecimal("1E-1000"), new BigDecimal("-1E-1000"),
                BigInteger.TEN.pow(1000), BigInteger.TEN.pow(1000).negate(), 1, -1L,
                Float.MIN_VALUE, -Float.MIN_VALUE, Double.MIN_VALUE, -Double.MIN_VALUE)) {
            assertEquals(SqlValue.Truth.TRUE, new SqlValue.Known(number).truth(), number.toString());
        }
    }

    @Test
    void numericZerosRemainFalseRegardlessOfTypeScaleOrSign() {
        for (Number number : List.<Number>of(new BigDecimal("0E-1000"), new BigDecimal("-0.000"),
                BigInteger.ZERO, (byte) 0, (short) 0, 0, 0L, 0.0f, -0.0f, 0.0, -0.0)) {
            assertEquals(SqlValue.Truth.FALSE, new SqlValue.Known(number).truth(), number.toString());
        }
    }
}
