package com.budgetintel.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/** Conversions between JSON numbers in ML responses and database decimals. */
final class Num {

    private Num() {
    }

    static BigDecimal dec(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? BigDecimal.valueOf(d) : null;
        }
        throw new IllegalArgumentException("Not a number: " + v);
    }

    static BigDecimal dec(Object v, int scale) {
        BigDecimal b = dec(v);
        return b == null ? null : b.setScale(scale, RoundingMode.HALF_UP);
    }

    static Double dbl(BigDecimal v) {
        return v == null ? null : v.doubleValue();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object v) {
        return (Map<String, Object>) v;
    }
}
