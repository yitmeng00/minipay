package com.minipay.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MoneyTest {

    @ParameterizedTest(name = "{0} {1} -> scale {2}")
    @CsvSource({
            "EUR, 10,     2, 10.00",
            "EUR, 10.5,   2, 10.50",
            "JPY, 1500,   0, 1500",
            "KWD, 1.25,   3, 1.250",
            "GBP, 0.10,   2, 0.10",
            "EUR, 10.500, 2, 10.50"   // trailing zeros beyond the minor units are fine
    })
    void normalises_scale_to_currency_minor_units(String ccy, String amount, int scale, String expected) {
        Money m = Money.of(amount, ccy);
        assertThat(m.amount().scale()).isEqualTo(scale);
        assertThat(m.amount().toPlainString()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"EUR, 10.005", "JPY, 100.5", "KWD, 1.2345"})
    void rejects_more_decimals_than_the_currency_allows(String ccy, String amount) {
        assertThatThrownBy(() -> Money.of(amount, ccy))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more decimals");
    }

    @Test
    void rounded_uses_bankers_rounding_by_default() {
        // HALF_EVEN: ties go to the even neighbour, so rounding errors cancel out over many fees.
        assertThat(Money.rounded(new BigDecimal("2.345"), CurrencyUnits.currency("EUR")))
                .isEqualTo(Money.of("2.34", "EUR"));
        assertThat(Money.rounded(new BigDecimal("2.355"), CurrencyUnits.currency("EUR")))
                .isEqualTo(Money.of("2.36", "EUR"));
        assertThat(Money.rounded(new BigDecimal("2.345"), CurrencyUnits.currency("EUR"), RoundingMode.HALF_UP))
                .isEqualTo(Money.of("2.35", "EUR"));
        assertThat(Money.rounded(new BigDecimal("1500.5"), CurrencyUnits.currency("JPY")))
                .isEqualTo(Money.of("1500", "JPY"));
    }

    @Test
    void times_applies_rate_and_rounds() {
        Money fee = Money.of("1234.56", "EUR").times(new BigDecimal("0.0015"));
        assertThat(fee).isEqualTo(Money.of("1.85", "EUR")); // 1.85184 -> 1.85
    }

    @Test
    void arithmetic_within_a_currency() {
        Money a = Money.of("100.10", "EUR");
        Money b = Money.of("0.20", "EUR");
        assertThat(a.plus(b)).isEqualTo(Money.of("100.30", "EUR"));
        assertThat(b.minus(a)).isEqualTo(Money.of("-99.90", "EUR"));
        assertThat(b.minus(a).isNegative()).isTrue();
        assertThat(a.negate().negate()).isEqualTo(a);
        assertThat(a.isGreaterThan(b)).isTrue();
        assertThat(Money.zero("EUR").isZero()).isTrue();
    }

    @Test
    void the_classic_float_bug_does_not_happen() {
        Money sum = Money.of("0.10", "EUR").plus(Money.of("0.20", "EUR"));
        assertThat(sum).isEqualTo(Money.of("0.30", "EUR"));
    }

    @Test
    void mixing_currencies_is_an_error() {
        assertThatThrownBy(() -> Money.of("1", "EUR").plus(Money.of("1", "USD")))
                .isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> Money.of("1", "EUR").compareTo(Money.of("1", "USD")))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void equality_ignores_input_scale() {
        assertThat(Money.of("10", "EUR")).isEqualTo(Money.of("10.00", "EUR"));
        assertThat(Money.of("10", "EUR")).hasSameHashCodeAs(Money.of("10.00", "EUR"));
        assertThat(Money.of("10", "EUR")).isNotEqualTo(Money.of("10", "USD"));
    }

    @Test
    void rejects_unknown_and_non_payment_currencies() {
        assertThatThrownBy(() -> Money.of("1", "ABC")).hasMessageContaining("Unknown");
        assertThatThrownBy(() -> Money.of("1", "eur")).hasMessageContaining("3 upper-case");
        assertThatThrownBy(() -> Money.of("1", "XAU")).hasMessageContaining("no minor units");
    }

    @Test
    void renders_iso_style() {
        assertThat(Money.of("1250", "EUR")).hasToString("EUR 1250.00");
    }
}
