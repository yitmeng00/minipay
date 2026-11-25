package com.minipay.common.id;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IbanTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "GB86MNPC40051288334410", "DE87500100600778812003", "BE71096123456769",
            "FR7630006000011234567890189", "NL91ABNA0417164300", "DE89370400440532013000"})
    void valid_ibans_leave_remainder_one(String iban) {
        assertThat(Iban.remainder(iban)).isEqualTo(1);
        assertThat(Iban.hasValidCheckDigits(iban)).isTrue();
    }

    @Test
    void a_single_wrong_digit_is_caught() {
        assertThat(Iban.hasValidCheckDigits("GB97MNPC60161331926819")).isFalse(); // check digit 96 → 97
        assertThat(Iban.hasValidCheckDigits("GB96MNPC60161331926818")).isFalse(); // last digit 9 → 8
    }

    @Test
    void adjacent_transposition_is_caught() {
        assertThat(Iban.hasValidCheckDigits("DE87500100600778812030")).isFalse(); // …003 → …030
    }

    @Test
    void expected_check_digits_are_computed_from_the_bban() {
        assertThat(Iban.expectedCheckDigits("GB", "MNPC60161331926819")).isEqualTo("96");
        assertThat(Iban.expectedCheckDigits("DE", "500100600778812003")).isEqualTo("87");
    }

    @Test
    void normalises_and_prints() {
        String e = Iban.normalize(" gb86 mnpc 4005 1288 3344 10 ");
        assertThat(e).isEqualTo("GB86MNPC40051288334410");
        assertThat(Iban.printFormat(e)).isEqualTo("GB86 MNPC 4005 1288 3344 10");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "GB", "G186MNPC4005", "GBX6MNPC4005", "GB86MNPC-4005", "GB860123456789012345678901234567890"})
    void malformed_strings_are_not_ibans(String s) {
        assertThat(Iban.isWellFormed(s)).isFalse();
        assertThat(Iban.hasValidCheckDigits(s)).isFalse();
    }
}
