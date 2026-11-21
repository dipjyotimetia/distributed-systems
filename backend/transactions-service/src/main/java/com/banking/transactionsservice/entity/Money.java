package com.banking.transactionsservice.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Ledger amounts are exact cents. Callers may pass a decimal with at most two places. */
public final class Money {
    private Money() {}

    public static BigDecimal of(double amount) {
        return of(new BigDecimal(Double.toString(amount)));
    }

    public static BigDecimal of(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("Amount is required");
        }
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("Amount must have at most 2 decimal places");
        }
    }

    public static long minorUnits(BigDecimal amount) {
        return of(amount).movePointRight(2).longValueExact();
    }
}
