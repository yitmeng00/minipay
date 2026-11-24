package com.minipay.common.money;

import java.util.Currency;

/** Thrown when arithmetic or comparison mixes two currencies. FX must always be an explicit step. */
public class CurrencyMismatchException extends IllegalArgumentException {

    public CurrencyMismatchException(Currency left, Currency right) {
        super("Currency mismatch: " + left.getCurrencyCode() + " vs " + right.getCurrencyCode());
    }
}
