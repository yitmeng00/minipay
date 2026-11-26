package com.minipay.compliance.internal.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.minipay.compliance.api.NameToken;
import com.minipay.compliance.api.PartyRole;
import com.minipay.compliance.api.ScreenedParty;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ScreenerTest {

    static final int HOLD = 75;

    static ListEntry entity(String id, String name, String country, String... aliases) {
        return new ListEntry(1, id, "EU", "EU consolidated (sim)", "ENTITY", name, List.of(aliases), country, "addr", "reg 1",
                null, null, "Reg. (sim)", LocalDate.of(2026, 1, 1), null);
    }

    static final List<ListEntry> LIST = List.of(
            entity("EU.SIM.4417", "OAKRIDGE INTERIORS TRADING FZE", "AE", "Oakridge Trading FZE", "ORI Trading FZE"),
            entity("SDN.SIM-20931", "NORTH BRIDGE CAPITAL HOLDINGS LTD", "CY", "NBC Holdings", "Northbridge Cap."),
            entity("EU.SIM.3902", "BALTIC AMBER SHIPPING CO", "EE", "BA Shipping", "Baltic Amber Freight"),
            new ListEntry(2, "EU.SIM.5120", "EU", "EU consolidated (sim)", "INDIVIDUAL", "IVANOVA, SVETLANA PETROVNA",
                    List.of("Svetlana Ivanova"), "RU", null, null, LocalDate.of(1971, 5, 2), null, "Reg. (sim)", LocalDate.of(2024, 1, 1), null),
            entity("SDN.SIM-31877", "PACIFIC DELTA MINING CORP", "PH", "PDM Corp"),
            entity("EU.SIM.6031", "HALVORSEN MARITIME AS", "NO", "Halvorsen Mar."),
            new ListEntry(3, "SDN.SIM-40210", "OFAC", "OFAC SDN (sim)", "BANK", "ORONTES TRADE BANK", List.of(), "SY", null, null,
                    null, "MNPZSYDAXXX", "Program SIM-SYR", LocalDate.of(2024, 5, 15), null));

    static ScreenedParty creditor(String name, String iban) {
        return new ScreenedParty(PartyRole.CREDITOR, "Cdtr/Nm", name, iban, null);
    }

    static Screener.Result screen(ScreenedParty... parties) {
        return Screener.screen(List.of(parties), LIST, HOLD);
    }

    @Test
    void jaro_winkler_reference_values() {
        // Textbook examples (Winkler 1990).
        assertThat(JaroWinkler.similarity("MARTHA", "MARHTA")).isCloseTo(0.961, org.assertj.core.data.Offset.offset(0.001));
        assertThat(JaroWinkler.similarity("DIXON", "DICKSONX")).isCloseTo(0.813, org.assertj.core.data.Offset.offset(0.001));
        assertThat(JaroWinkler.similarity("ABC", "XYZ")).isZero();
    }

    @Test
    void names_are_folded_to_comparable_words() {
        assertThat(Names.tokens("Müller & Söhne GmbH")).containsExactly("MULLER", "SOHNE");
        assertThat(Names.tokens("Baltic Amber Freight OÜ")).containsExactly("BALTIC", "AMBER", "FREIGHT");
        assertThat(Names.tokens("IVANOVA, Svetlana")).containsExactly("IVANOVA", "SVETLANA");
        assertThat(Names.tokens("The Company Ltd")).containsExactly("THE", "COMPANY", "LTD"); // all noise: keep all
        assertThat(Names.ibanCountry("GB86MNPC40051288334410")).isEqualTo("GB");
        assertThat(Names.ibanCountry("0041-882910-77")).isNull();
    }

    /** The design's held payments: each must hold, for the reason shown. */
    @ParameterizedTest(name = "{0} ≈ {1}")
    @CsvSource({
            "Oakridge Interiors Ltd,      EU.SIM.4417,   NAME,  GB86MNPC40051288334410",
            "Northbridge Capital LLC,     SDN.SIM-20931, ALIAS, ",
            "Baltic Amber Freight OÜ,     EU.SIM.3902,   ALIAS, EE382200221020145685",
            "Svetla Ivanova,              EU.SIM.5120,   ALIAS, BG80BNBG96611020345678",
            "Pacific Delta Minerals Ltd,  SDN.SIM-31877, NAME,  ",
    })
    void similar_names_hold_the_payment(String name, String entryId, String on, String iban) {
        Screener.Result r = screen(creditor(name, iban));
        assertThat(r.hits()).isNotEmpty();
        Screener.Hit top = r.hits().get(0);
        assertThat(top.entry().entryId()).isEqualTo(entryId);
        assertThat(top.matchedOn()).isEqualTo(on);
        assertThat(top.score()).isBetween(HOLD, 100);
    }

    @Test
    void scores_rank_like_an_analyst_would() {
        int oakridge = screen(creditor("Oakridge Interiors Ltd", "GB86MNPC40051288334410")).bestScore();
        int baltic = screen(creditor("Baltic Amber Freight OÜ", "EE382200221020145685")).bestScore();
        // Exact alias + same country outranks a strong name overlap with a different country.
        assertThat(baltic).isGreaterThan(oakridge);
        assertThat(oakridge).isGreaterThanOrEqualTo(90); // four-eyes territory
    }

    @Test
    void words_split_differently_still_match() {
        Screener.Hit hit = screen(creditor("Northbridge Capital Holdings LLC", null)).hits().get(0);
        assertThat(hit.matchedOn()).isEqualTo("NAME");
        assertThat(hit.partyTokens()).extracting(NameToken::matched).containsExactly(true, true, true, false);
        assertThat(hit.listedTokens()).extracting(NameToken::text).containsExactly("NORTH", "BRIDGE", "CAPITAL", "HOLDINGS", "LTD");
        assertThat(hit.listedTokens()).extracting(NameToken::matched).containsExactly(true, true, true, true, false);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "Delta",                          // one shared word is not a match
            "Pacific Trading Pte Ltd",
            "Muar Furniture Works Sdn Bhd",
            "Hans Müller",
            "Amber Lee",
            "Capital Holdings Berhad",
            "Oak Furniture Ltd",
    })
    void ordinary_names_pass(String name) {
        assertThat(screen(creditor(name, "GB86MNPC40051288334410")).hits()).isEmpty();
    }

    @Test
    void the_same_country_corroborates_but_a_different_one_never_lowers_the_score() {
        ScreenedParty norway = creditor("Halvorsen Shipping AS", "NO9386011117947");
        ScreenedParty germany = creditor("Halvorsen Shipping AS", "DE51500100600123456789");
        ScreenedParty unknown = creditor("Halvorsen Shipping AS", null);
        int no = Screener.screen(List.of(norway), LIST, 0).bestScore();
        int de = Screener.screen(List.of(germany), LIST, 0).bestScore();
        int none = Screener.screen(List.of(unknown), LIST, 0).bestScore();
        assertThat(no).isEqualTo(de + Screener.COUNTRY_BONUS);
        assertThat(de).isEqualTo(none);
    }

    @Test
    void a_listed_bank_is_caught_by_its_bic_on_any_branch() {
        ScreenedParty agent = new ScreenedParty(PartyRole.CREDITOR_AGENT, "CdtrAgt/FinInstnId/BICFI", null, null, "MNPZSYDA100");
        Screener.Hit hit = screen(agent).hits().get(0);
        assertThat(hit.score()).isEqualTo(100);
        assertThat(hit.matchedOn()).isEqualTo("BIC");
        assertThat(hit.hint()).contains("sanctioned institution");
        assertThat(screen(new ScreenedParty(PartyRole.CREDITOR_AGENT, "CdtrAgt", null, null, "MNPCGB2LXXX")).hits()).isEmpty();
    }

    @Test
    void hints_point_at_what_to_check() {
        assertThat(screen(creditor("Oakridge Interiors Ltd", "GB86MNPC40051288334410")).hits().get(0).hint()).startsWith("Likely false positive");
        assertThat(screen(creditor("Baltic Amber Freight", "EE382200221020145685")).hits().get(0).hint()).startsWith("Escalate");
        assertThat(screen(creditor("Svetla Ivanova", null)).hits().get(0).hint()).startsWith("Request the date of birth");
    }

    @Test
    void every_hit_explains_itself() {
        Screener.Hit hit = screen(creditor("Oakridge Interiors Ltd", "GB86MNPC40051288334410")).hits().get(0);
        assertThat(hit.factors()).extracting(f -> f.name()).containsExactly("Name similarity", "Token overlap", "Country", "Alias");
        assertThat(hit.factors().get(1).value()).isEqualTo("2 / 3");
        assertThat(hit.comparison()).extracting(c -> c.attribute())
                .containsExactly("Aliases", "Country", "Address", "Registration", "Entity type", "Account / agent", "Listing");
        assertThat(hit.comparison().get(1).party()).isEqualTo("GB (IBAN)");
        assertThat(hit.comparison().get(1).match()).isEqualTo("No");
    }
}
