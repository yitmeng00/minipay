package com.minipay.recon.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.minipay.recon.internal.domain.Matcher.BreakType;
import com.minipay.recon.internal.domain.Matcher.LedgerItem;
import com.minipay.recon.internal.domain.Matcher.Rule;
import com.minipay.recon.internal.domain.Matcher.StatementItem;
import com.minipay.recon.internal.domain.Matcher.Tx;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MatcherTest {

    static final LocalDate D = LocalDate.of(2026, 11, 23);
    static long ids = 1;

    /** A ledger line on our nostro: Dr = money in (+), Cr = money out (−). */
    static LedgerItem dr(String amount, String uetr, String e2e) {
        return new LedgerItem(ids++, D, Instant.parse("2026-11-23T09:00:00Z").plusSeconds(ids), new BigDecimal(amount), uetr, e2e, "x");
    }

    static LedgerItem cr(String amount, String uetr, String e2e) {
        return dr("-" + amount, uetr, e2e);
    }

    /** A statement entry: CRDT = money in (+), DBIT = money out (−). */
    static StatementItem crdt(String amount, String uetr, String e2e) {
        return new StatementItem(ids++, D, Instant.parse("2026-11-23T09:00:00Z").plusSeconds(ids), new BigDecimal(amount), "SVC" + ids,
                null, uetr == null && e2e == null ? List.of() : List.of(new Tx(uetr, e2e, new BigDecimal(amount))));
    }

    static StatementItem dbit(String amount, String uetr, String e2e) {
        return crdt("-" + amount, uetr, e2e);
    }

    @Test
    void a_ledger_credit_matches_a_statement_debit_on_uetr_and_amount() {
        LedgerItem sent = cr("48000.00", "u1", "E2E-1");
        StatementItem debited = dbit("48000.00", "u1", "E2E-1");
        var r = Matcher.match(List.of(sent), List.of(debited), Set.of());
        assertThat(r.matches()).singleElement().satisfies(m -> {
            assertThat(m.rule()).isEqualTo(Rule.UETR);
            assertThat(m.ledger()).containsExactly(sent.id());
            assertThat(m.statement()).containsExactly(debited.id());
        });
        assertThat(r.breaks()).isEmpty();
    }

    @Test
    void without_a_uetr_the_end_to_end_id_and_amount_match_but_notprovided_never_does() {
        var r = Matcher.match(List.of(cr("100.00", null, "INV-1"), cr("50.00", null, "NOTPROVIDED")),
                List.of(dbit("100.00", null, "INV-1"), dbit("50.00", null, "NOTPROVIDED")), Set.of());
        assertThat(r.matches()).extracting(Matcher.Match::rule).containsExactly(Rule.E2E);
        assertThat(r.breaks()).extracting(Matcher.Break::type)
                .containsExactlyInAnyOrder(BreakType.MISSING_IN_LEDGER, BreakType.MISSING_IN_STATEMENT);
    }

    @Test
    void a_dns_net_settlement_matches_every_gross_payment_it_lists() {
        LedgerItem out = cr("2000.00", "u1", null);
        LedgerItem in = dr("3000.00", "u2", null);
        StatementItem net = new StatementItem(ids++, D, Instant.now(), new BigDecimal("1000.00"), "CYCLE-1", "MiniPay Net cycle",
                List.of(new Tx("u1", null, new BigDecimal("-2000.00")), new Tx("u2", null, new BigDecimal("3000.00"))));
        var r = Matcher.match(List.of(out, in), List.of(net), Set.of());
        assertThat(r.matches()).singleElement().satisfies(m -> {
            assertThat(m.rule()).isEqualTo(Rule.BATCH);
            assertThat(m.ledger()).containsExactlyInAnyOrder(out.id(), in.id());
        });
        assertThat(r.breaks()).isEmpty();
    }

    @Test
    void a_charge_deducted_on_the_way_is_an_amount_mismatch_with_its_difference() {
        LedgerItem booked = dr("12500.00", "u1", "INV-FR-88213");
        StatementItem received = crdt("12485.00", "u1", "INV-FR-88213");
        var r = Matcher.match(List.of(booked), List.of(received), Set.of());
        assertThat(r.breaks()).singleElement().satisfies(b -> {
            assertThat(b.type()).isEqualTo(BreakType.AMOUNT_MISMATCH);
            assertThat(b.difference()).isEqualByComparingTo("-15.00");
        });
    }

    @Test
    void the_servicer_booking_a_payment_twice_is_a_duplicate() {
        LedgerItem sent = cr("4140.00", "u1", null);
        var r = Matcher.match(List.of(sent), List.of(dbit("4140.00", "u1", null), dbit("4140.00", "u1", null)), Set.of());
        assertThat(r.matches()).hasSize(1);
        assertThat(r.breaks()).singleElement().satisfies(b -> {
            assertThat(b.type()).isEqualTo(BreakType.DUPLICATE);
            assertThat(b.difference()).isEqualByComparingTo("-4140.00");
        });
        // Also when the original was matched in an earlier run.
        var later = Matcher.match(List.of(), List.of(dbit("4140.00", "u1", null)), Set.of(Matcher.key("u1", new BigDecimal("-4140.00"))));
        assertThat(later.breaks()).extracting(Matcher.Break::type).containsExactly(BreakType.DUPLICATE);
    }

    @Test
    void a_payment_sent_and_reversed_cancels_out_on_our_side_alone() {
        var r = Matcher.match(List.of(cr("1000.00", "u1", null), dr("1000.00", "u1", null)), List.of(), Set.of());
        assertThat(r.matches()).singleElement().satisfies(m -> assertThat(m.rule()).isEqualTo(Rule.CONTRA));
        assertThat(r.breaks()).isEmpty();
    }

    @Test
    void timing_and_a_servicer_charge_are_missing_items_with_signed_differences() {
        var r = Matcher.match(List.of(cr("60000.00", "u9", null)), List.of(new StatementItem(ids++, D, Instant.now(),
                new BigDecimal("-25.00"), "CHG-NOV-0441", "ACCOUNT MAINTENANCE FEE NOV 2026", List.of())), Set.of());
        assertThat(r.breaks()).extracting(Matcher.Break::type, b -> b.difference().toPlainString()).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(BreakType.MISSING_IN_STATEMENT, "60000.00"),
                org.assertj.core.groups.Tuple.tuple(BreakType.MISSING_IN_LEDGER, "-25.00"));
    }

    @Test
    void our_adjustment_naming_the_servicers_reference_matches_its_charge() {
        LedgerItem adj = new LedgerItem(ids++, D, Instant.now(), new BigDecimal("-25.00"), null, null, "Recon adjustment BRK-0441 · CHG-NOV-0441");
        StatementItem charge = new StatementItem(ids++, D, Instant.now(), new BigDecimal("-25.00"), "CHG-NOV-0441", "FEE", List.of());
        assertThat(Matcher.match(List.of(adj), List.of(charge), Set.of()).matches()).extracting(Matcher.Match::rule)
                .containsExactly(Rule.REFERENCE);
    }

    @Test
    void opening_balances_booked_as_several_lines_on_one_side_match_one_line_on_the_other() {
        LedgerItem a = new LedgerItem(ids++, D, Instant.now(), new BigDecimal("1000.00"), null, null, "Opening balance");
        LedgerItem b = new LedgerItem(ids++, D, Instant.now(), new BigDecimal("500.00"), null, null, "Opening balance");
        StatementItem one = new StatementItem(ids++, D, Instant.now(), new BigDecimal("1500.00"), "S", "Opening balance", List.of());
        assertThat(Matcher.match(List.of(a, b), List.of(one), Set.of()).matches()).extracting(Matcher.Match::rule)
                .containsExactly(Rule.TAKE_ON);
    }
}
