package com.minipay.common.money;

import java.util.Currency;

/**
 * ISO 4217 currency lookups.
 *
 * <p>We use {@link java.util.Currency} because the JDK ships the ISO 4217 table, including each
 * currency's number of minor units ("exponent"): EUR 2, JPY 0, KWD 3. We only reject currencies
 * that have no minor-unit definition (fraction digits {@code -1}): precious metals such as XAU,
 * SDR (XDR) and test codes — none of which are valid settlement currencies in CBPR+.
 */
public final class CurrencyUnits {

    private CurrencyUnits() {
    }

    public static Currency currency(String code) {
        if (code == null || !code.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Currency code must be 3 upper-case letters: " + code);
        }
        Currency currency;
        try {
            currency = Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown ISO 4217 currency: " + code, e);
        }
        minorUnits(currency);
        return currency;
    }

    public static int minorUnits(Currency currency) {
        int digits = currency.getDefaultFractionDigits();
        if (digits < 0) {
            throw new IllegalArgumentException(
                    "Currency " + currency.getCurrencyCode() + " has no minor units and cannot be used for payments");
        }
        return digits;
    }
}
