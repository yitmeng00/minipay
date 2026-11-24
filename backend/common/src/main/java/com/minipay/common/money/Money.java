package com.minipay.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount of money in one ISO 4217 currency.
 *
 * <h2>Why a value object and not a bare BigDecimal?</h2>
 * A bare number has no currency, so nothing stops you adding EUR to JPY. And a bare BigDecimal
 * has no notion of how many decimals are legal: EUR has 2 ("minor units" = cents), JPY has 0,
 * KWD/BHD have 3. Payment messages are rejected if an amount has more decimals than the currency
 * allows (ISO 20022 {@code ActiveCurrencyAndAmount} plus CBPR+ usage rules), so we normalise every
 * amount to exactly the currency's minor-unit scale at construction time.
 *
 * <h2>Two ways in</h2>
 * <ul>
 *   <li>{@link #of} — for amounts that come from outside (APIs, messages, seed data). These must
 *       already be valid for the currency; {@code EUR 10.005} is <em>rejected</em>, never silently
 *       rounded, because silently changing an instructed amount is how money goes missing.</li>
 *   <li>{@link #rounded} — for amounts we <em>compute</em> (fees as a percentage, FX conversion,
 *       interest). These are rounded with {@link #DEFAULT_ROUNDING} (HALF_EVEN, "banker's rounding")
 *       which is unbiased over many operations, unlike HALF_UP.</li>
 * </ul>
 *
 * <p>Money may be negative (e.g. a balance in overdraft). Ledger postings themselves carry a
 * positive amount plus a debit/credit direction — that rule lives in the ledger, not here.
 */
public final class Money implements Comparable<Money> {

    public static final RoundingMode DEFAULT_ROUNDING = RoundingMode.HALF_EVEN;

    private final BigDecimal amount;
    private final Currency currency;

    private Money(BigDecimal amount, Currency currency) {
        this.amount = amount;
        this.currency = currency;
    }

    /** Strict factory: rejects amounts with more decimals than the currency's minor units. */
    public static Money of(BigDecimal amount, Currency currency) {
        Objects.requireNonNull(amount, "amount");
        int scale = scaleOf(currency);
        BigDecimal normalised;
        try {
            // UNNECESSARY throws if rounding would be needed: "10.50" -> 10.50 OK, "10.505" -> error.
            normalised = amount.setScale(scale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "Amount " + amount.toPlainString() + " has more decimals than " + currency.getCurrencyCode()
                            + " allows (" + scale + ")", e);
        }
        return new Money(normalised, currency);
    }

    public static Money of(String amount, String currencyCode) {
        return of(new BigDecimal(amount), CurrencyUnits.currency(currencyCode));
    }

    public static Money of(BigDecimal amount, String currencyCode) {
        return of(amount, CurrencyUnits.currency(currencyCode));
    }

    /** Lenient factory for computed amounts: rounds to the currency's minor units with HALF_EVEN. */
    public static Money rounded(BigDecimal amount, Currency currency) {
        return rounded(amount, currency, DEFAULT_ROUNDING);
    }

    public static Money rounded(BigDecimal amount, Currency currency, RoundingMode mode) {
        Objects.requireNonNull(amount, "amount");
        return new Money(amount.setScale(scaleOf(currency), mode), currency);
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO.setScale(scaleOf(currency)), currency);
    }

    public static Money zero(String currencyCode) {
        return zero(CurrencyUnits.currency(currencyCode));
    }

    public BigDecimal amount() {
        return amount;
    }

    public Currency currency() {
        return currency;
    }

    public String currencyCode() {
        return currency.getCurrencyCode();
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    /** Multiplies by a factor (e.g. a fee rate) and rounds the result HALF_EVEN. */
    public Money times(BigDecimal factor) {
        return rounded(amount.multiply(factor), currency);
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    private static int scaleOf(Currency currency) {
        Objects.requireNonNull(currency, "currency");
        return CurrencyUnits.minorUnits(currency);
    }

    /** Scale is always normalised, so BigDecimal.equals (which compares scale) is safe here. */
    @Override
    public boolean equals(Object o) {
        return o instanceof Money m && amount.equals(m.amount) && currency.equals(m.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount, currency);
    }

    /** ISO-style rendering, e.g. {@code EUR 1250.00}. */
    @Override
    public String toString() {
        return currency.getCurrencyCode() + " " + amount.toPlainString();
    }
}
