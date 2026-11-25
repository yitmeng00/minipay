package com.minipay.refdata.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.minipay.refdata.api.IbanCheck;
import com.minipay.refdata.api.IbanCheck.Outcome;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IbanValidatorTest {

    static final IbanFormat GB = new IbanFormat("GB", 22, List.of(
            new IbanFormat.BbanPart("Bank code", "BANK", 4, "a"),
            new IbanFormat.BbanPart("Sort code", "BRANCH", 6, "n"),
            new IbanFormat.BbanPart("Account", "ACCOUNT", 8, "n")));

    static final Map<String, String> MAP = Map.of("GB:MNPC", "MNPCGB2LXXX");

    static IbanCheck check(String iban, Optional<IbanFormat> format) {
        return IbanValidator.validate(iban, format, (cc, id) -> Optional.ofNullable(MAP.get(cc + ":" + id)));
    }

    static Outcome outcome(IbanCheck c, String name) {
        return c.checks().stream().filter(k -> k.name().equals(name)).findFirst().orElseThrow().outcome();
    }

    @Test
    void a_valid_iban_passes_every_check_and_is_split_into_its_parts() {
        IbanCheck c = check("GB86 MNPC 4005 1288 3344 10", Optional.of(GB));
        assertThat(c.valid()).isTrue();
        assertThat(c.electronic()).isEqualTo("GB86MNPC40051288334410");
        assertThat(c.printFormat()).isEqualTo("GB86 MNPC 4005 1288 3344 10");
        assertThat(c.parts()).extracting(IbanCheck.Part::name, IbanCheck.Part::value).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Country", "GB"),
                org.assertj.core.groups.Tuple.tuple("Check digits", "86"),
                org.assertj.core.groups.Tuple.tuple("Bank code", "MNPC"),
                org.assertj.core.groups.Tuple.tuple("Sort code", "400512"),
                org.assertj.core.groups.Tuple.tuple("Account", "88334410"));
        assertThat(c.checks()).allMatch(k -> k.outcome() == Outcome.PASS);
        assertThat(c.bic()).isEqualTo("MNPCGB2LXXX");
    }

    @Test
    void wrong_check_digits_fail_and_the_expected_ones_are_shown() {
        IbanCheck c = check("GB97MNPC60161331926819", Optional.of(GB));
        assertThat(c.valid()).isFalse();
        assertThat(c.firstFailure()).isEqualTo("Check digits (mod-97): remainder 2 · expected check digits 96");
    }

    @Test
    void wrong_length_fails() {
        IbanCheck c = check("GB96 MNPC", Optional.of(GB));
        assertThat(c.valid()).isFalse();
        assertThat(outcome(c, "Length")).isEqualTo(Outcome.FAIL);
        assertThat(c.firstFailure()).startsWith("Length: 8 of 22");
    }

    @Test
    void bban_structure_is_checked_per_part() {
        // Correct length and check digits, but the "sort code" contains a letter.
        String bban = "MNPC4005A288334410";
        String iban = "GB" + com.minipay.common.id.Iban.expectedCheckDigits("GB", bban) + bban;
        IbanCheck c = check(iban, Optional.of(GB));
        assertThat(outcome(c, "Check digits (mod-97)")).isEqualTo(Outcome.PASS);
        assertThat(outcome(c, "BBAN structure")).isEqualTo(Outcome.FAIL);
        assertThat(c.valid()).isFalse();
    }

    @Test
    void an_unknown_bank_code_is_only_a_warning() {
        String bban = "ZZZZ40051288334410";
        IbanCheck c = check("GB" + com.minipay.common.id.Iban.expectedCheckDigits("GB", bban) + bban, Optional.of(GB));
        assertThat(c.valid()).isTrue();
        assertThat(outcome(c, "Bank code in directory")).isEqualTo(Outcome.WARN);
        assertThat(c.bic()).isNull();
    }

    @Test
    void a_country_outside_the_registry_is_not_valid() {
        IbanCheck c = check("MY12345678901234567890", Optional.empty());
        assertThat(c.valid()).isFalse();
        assertThat(outcome(c, "Country supported")).isEqualTo(Outcome.FAIL);
    }

    @Test
    void the_registry_entry_must_add_up() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new IbanFormat("XX", 20,
                List.of(new IbanFormat.BbanPart("Account", "ACCOUNT", 10, "n"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
