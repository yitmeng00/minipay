package com.minipay.paymenthub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minipay.common.iso.ChargeBearer;
import com.minipay.common.money.Money;
import com.minipay.ledger.api.AccountView;
import com.minipay.ledger.api.EntryType;
import com.minipay.ledger.api.JournalEntryView;
import com.minipay.ledger.api.ReservationStatus;
import com.minipay.paymenthub.api.CreatePaymentCommand;
import com.minipay.paymenthub.api.NetworkEvent;
import com.minipay.paymenthub.api.PaymentError;
import com.minipay.paymenthub.api.PaymentException;
import com.minipay.paymenthub.api.PaymentQuery;
import com.minipay.paymenthub.api.PaymentStatus;
import com.minipay.paymenthub.api.PaymentView;
import com.minipay.paymenthub.api.Replayable;
import com.minipay.paymenthub.api.StateChangeView;
import com.minipay.paymenthub.internal.processing.RecoverySweeper;
import com.minipay.refdata.api.CreateRoutingRuleCommand;
import com.minipay.refdata.api.RoutingMethod;
import com.minipay.refdata.api.RoutingRuleView;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class PaymentLifecycleIT extends PaymentHubIntegrationTest {

    @Autowired
    RecoverySweeper sweeper;

    @Autowired
    JdbcClient jdbc;

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);

    @Test
    void happy_path_shar_goes_all_the_way_to_sent_and_books_principal_and_fee() {
        AccountView debtor = customer("EUR", "100000.00");
        Money nostroBooked = reload(eurNostro).booked();
        Money feesBooked = reload(eurFees).booked();

        PaymentView p = hub.create(payment(debtor, "48250.00", ChargeBearer.SHAR)).value();

        assertThat(p.status()).isEqualTo(PaymentStatus.SENT);
        assertThat(p.uetr()).matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        assertThat(p.instructionId()).startsWith("MNPA261124");
        assertThat(p.creditorAccount()).isEqualTo("GB96MNPC60161331926819"); // spaces removed
        assertThat(p.debtorName()).isEqualTo("Selangor Precision Components Sdn Bhd");
        assertThat(p.chargesAmount()).isEqualTo(eur("20.00"));
        assertThat(p.interbankSettlementAmount()).isEqualTo(eur("48250.00"));
        assertThat(p.instructedAgentBic()).isEqualTo(BANK_B); // serial via our EUR correspondent
        assertThat(p.valueDate()).isEqualTo(TODAY);

        assertThat(hub.get(p.uetr()).history()).extracting(StateChangeView::to).containsExactly(
                PaymentStatus.RECEIVED, PaymentStatus.VALIDATED, PaymentStatus.SCREENED,
                PaymentStatus.FUNDS_RESERVED, PaymentStatus.SENT);

        // Ledger: debtor paid amount + fee; nostro credited the amount; fee income earned the fee.
        AccountView d = reload(debtor);
        assertThat(d.booked()).isEqualTo(eur("51730.00"));
        assertThat(d.reserved()).isEqualTo(eur("0.00"));
        assertThat(reload(eurNostro).booked()).isEqualTo(nostroBooked.minus(eur("48250.00")));
        assertThat(reload(eurFees).booked()).isEqualTo(feesBooked.plus(eur("20.00")));
        assertThat(ledger.entriesForPayment(p.id())).extracting(JournalEntryView::entryType)
                .containsExactly(EntryType.RESERVE, EntryType.RELEASE, EntryType.SETTLE, EntryType.FEE);
        assertThat(ledger.getReservation(p.reservationId()).status()).isEqualTo(ReservationStatus.SETTLED);
    }

    @Test
    void every_status_change_wrote_an_outbox_event_in_the_same_transaction() {
        PaymentView p = hub.create(payment(customer("EUR", "500.00"), "100.00", ChargeBearer.SHAR)).value();
        Long events = jdbc.sql("SELECT count(*) FROM payment.outbox_event WHERE aggregate_id = :id")
                .param("id", p.id()).query(Long.class).single();
        Long history = jdbc.sql("SELECT count(*) FROM payment.payment_state_history WHERE payment_id = :id")
                .param("id", p.id()).query(Long.class).single();
        assertThat(events).isEqualTo(history).isEqualTo(5L);
    }

    @Test
    void cred_deducts_our_fee_from_what_is_sent() {
        AccountView debtor = customer("EUR", "1000.00");
        PaymentView p = hub.create(payment(debtor, "1000.00", ChargeBearer.CRED)).value();

        assertThat(p.status()).isEqualTo(PaymentStatus.SENT);
        assertThat(p.interbankSettlementAmount()).isEqualTo(eur("980.00"));
        assertThat(reload(debtor).booked()).isEqualTo(eur("0.00"));
    }

    @Test
    void payment_to_a_bank_with_a_vostro_here_is_credited_to_that_vostro() {
        AccountView debtor = customer("MYR", "5000.00");
        AccountView vostro = ledger.findAccountByNumber("T-VOSTRO-MYR-MNPB").orElseThrow();
        Money before = reload(vostro).booked();

        PaymentView p = hub.create(new CreatePaymentCommand(key(), debtor.accountNumber(), "Kessler Maschinenbau GmbH",
                "DE13500100600412280017", BANK_B, Money.of("1200.00", "MYR"), ChargeBearer.SHAR, null, null, null)).value();

        assertThat(p.status()).isEqualTo(PaymentStatus.SENT);
        assertThat(p.endToEndId()).isEqualTo("NOTPROVIDED");
        assertThat(reload(vostro).booked()).isEqualTo(before.plus(Money.of("1200.00", "MYR")));
    }

    @Test
    void business_validation_failures_are_rejected_with_iso_reason_codes() {
        AccountView debtor = customer("EUR", "100.00");
        CreatePaymentCommand base = payment(debtor, "10.00", ChargeBearer.SHAR);

        assertRejected(new CreatePaymentCommand(key(), "DOES-NOT-EXIST", base.creditorName(), base.creditorAccount(),
                BANK_C, base.amount(), base.chargeBearer(), null, null, null), "AC01");
        assertRejected(new CreatePaymentCommand(key(), debtor.accountNumber(), base.creditorName(), base.creditorAccount(),
                BANK_C, Money.of("10.00", "GBP"), base.chargeBearer(), null, null, null), "AM03");
        assertRejected(new CreatePaymentCommand(key(), debtor.accountNumber(), base.creditorName(), base.creditorAccount(),
                "NOT-A-BIC", base.amount(), base.chargeBearer(), null, null, null), "RC01");
        assertRejected(new CreatePaymentCommand(key(), debtor.accountNumber(), base.creditorName(), "GB96",
                BANK_C, base.amount(), base.chargeBearer(), null, null, null), "AC03");
        assertRejected(withDate(payment(debtor, "10.00", ChargeBearer.SHAR), TODAY.minusDays(1)), "DT01");
        assertRejected(new CreatePaymentCommand(key(), debtor.accountNumber(), base.creditorName(), base.creditorAccount(),
                "MNPAMYKLXXX", base.amount(), base.chargeBearer(), null, null, null), "AG03");
    }

    @Test
    void reference_data_checks_reject_unknown_banks_and_bad_or_mismatched_ibans() {
        AccountView debtor = customer("EUR", "100.00");
        CreatePaymentCommand base = payment(debtor, "10.00", ChargeBearer.SHAR);

        // Well-formed BIC, but no such institution in the directory.
        PaymentView unknown = assertRejected(to(base, "GB96MNPC60161331926819", "MNPZDEFFXXX"), "RC01");
        assertThat(unknown.rejectionDetail()).contains("not in the BIC directory on 2026-11-24");
        // In the directory today, gone by the execution date: the bank must exist when we pay.
        assertRejected(withDate(to(base, "12345678", "MNPXNL2AXXX"), LocalDate.of(2027, 1, 4)), "RC01");
        // One digit wrong: mod-97 catches it.
        PaymentView badIban = assertRejected(to(base, "GB97MNPC60161331926819", BANK_C), "AC03");
        assertThat(badIban.rejectionDetail()).contains("expected check digits 96");
        // A valid IBAN of Bank C, sent to a Belgian bank: one of the two is wrong.
        PaymentView mismatch = assertRejected(to(base, "GB96MNPC60161331926819", "MNPDBEBBXXX"), "AC03");
        assertThat(mismatch.rejectionDetail()).contains("belongs to MNPCGB2LXXX");
        // Germany uses IBANs: a bare account number is not enough.
        assertRejected(to(base, "0412280017", BANK_B), "AC03");
    }

    @Test
    void a_payment_requested_for_a_weekend_executes_on_the_next_business_day() {
        AccountView debtor = customer("EUR", "1000.00");
        LocalDate saturday = LocalDate.of(2026, 11, 28);
        PaymentView p = hub.create(withDate(payment(debtor, "100.00", ChargeBearer.SHAR), saturday)).value();

        assertThat(p.status()).isEqualTo(PaymentStatus.FUNDS_RESERVED); // warehoused
        assertThat(p.requestedExecutionDate()).isEqualTo(saturday);
        assertThat(p.executionDate()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(hub.get(p.uetr()).history().get(1).detail()).contains("2026-11-28 is not a T2 RTGS business day");

        clock.advance(Duration.ofDays(4)); // Saturday: still waiting
        try {
            sweeper.resumeStuckPayments();
            assertThat(hub.get(p.uetr()).payment().status()).isEqualTo(PaymentStatus.FUNDS_RESERVED);
            clock.advance(Duration.ofDays(2)); // Monday
            sweeper.resumeStuckPayments();
            PaymentView sent = hub.get(p.uetr()).payment();
            assertThat(sent.status()).isEqualTo(PaymentStatus.SENT);
            assertThat(sent.valueDate()).isEqualTo(LocalDate.of(2026, 11, 30));
        } finally {
            clock.advance(Duration.ofDays(-6));
        }
    }

    @Test
    void after_the_cut_off_the_payment_is_sent_with_the_next_business_day_as_value_date() {
        AccountView debtor = customer("MYR", "5000.00");
        clock.advance(Duration.ofHours(9)); // 11:00 UTC = 19:00 Kuala Lumpur: RENTAS (18:00) has closed
        try {
            PaymentView p = hub.create(new CreatePaymentCommand(key(), debtor.accountNumber(), "Kessler Maschinenbau GmbH",
                    "DE13500100600412280017", BANK_B, Money.of("250.00", "MYR"), ChargeBearer.SHAR, null, null, null)).value();
            assertThat(p.status()).isEqualTo(PaymentStatus.SENT);
            assertThat(p.executionDate()).isEqualTo(TODAY);
            assertThat(p.valueDate()).isEqualTo(TODAY.plusDays(1));
            assertThat(hub.get(p.uetr()).history().get(4).detail())
                    .startsWith("Value date 2026-11-25 (RENTAS cut-off 18:00 Asia/Kuala_Lumpur on 2026-11-24 has passed)");
        } finally {
            clock.advance(Duration.ofHours(-9));
        }
    }

    @Test
    void a_currency_we_do_not_settle_is_rejected_AM03_before_any_hold() {
        AccountView debtor = customer("JPY", "1000000");
        PaymentView p = assertRejected(new CreatePaymentCommand(key(), debtor.accountNumber(), "Tokyo Precision KK",
                "123456789", "MNPJJPJTXXX", Money.of("50000", "JPY"), ChargeBearer.SHAR, null, null, null), "AM03");
        assertThat(p.rejectionDetail()).contains("no settlement scheme");
        assertThat(ledger.entriesForPayment(p.id())).isEmpty();
    }

    @Test
    void no_routing_rule_is_rejected_CNOR_at_validation_before_any_hold() {
        AccountView debtor = customer("GBP", "1000.00");
        // GBP to a Belgian bank: our only GBP rule is for Bank C.
        PaymentView p = assertRejected(new CreatePaymentCommand(key(), debtor.accountNumber(), "Brouwerij De Klok NV",
                "BE71096123456769", "MNPDBEBBXXX", Money.of("100.00", "GBP"), ChargeBearer.SHAR, null, null, null), "CNOR");
        assertThat(p.rejectionDetail()).contains("No routing rule for MNPDBEBB in GBP");
        assertThat(ledger.entriesForPayment(p.id())).isEmpty();
    }

    @Test
    void a_route_that_ends_before_the_value_date_rejects_CNOR_and_releases_the_hold() {
        // MiniPay Mutual in MYR is reachable through RENTAS, by a rule that is valid today only.
        refdata.createRoutingRule(new CreateRoutingRuleCommand(key(), "MNPMMYKL*", "MYR", RoutingMethod.RTGS, null,
                "T-NOSTRO-MYR-CB", 10, TODAY, TODAY, null, "r.lim", "a.wong", "One-day RTGS route"));
        AccountView debtor = customer("MYR", "5000.00");
        clock.advance(Duration.ofHours(9)); // after the RENTAS cut-off: the value date becomes tomorrow
        try {
            PaymentView p = hub.create(new CreatePaymentCommand(key(), debtor.accountNumber(), "Ipoh Agro Supplies",
                    "8812340011", "MNPMMYKLXXX", Money.of("300.00", "MYR"), ChargeBearer.SHAR, null, null, null)).value();

            assertThat(p.status()).isEqualTo(PaymentStatus.REJECTED);
            assertThat(p.rejectionReason()).isEqualTo("CNOR");
            assertThat(p.rejectionDetail()).startsWith("No route on value date 2026-11-25");
            assertThat(hub.get(p.uetr()).history()).extracting(StateChangeView::from)
                    .contains(PaymentStatus.FUNDS_RESERVED); // rejected after the hold was placed ...
            assertThat(reload(debtor).reserved().isZero()).isTrue(); // ... and the hold was released
            assertThat(ledger.entriesForPayment(p.id())).extracting(JournalEntryView::entryType)
                    .containsExactly(EntryType.RESERVE, EntryType.RELEASE);
        } finally {
            clock.advance(Duration.ofHours(-9));
        }
    }

    @Test
    void a_warehoused_payment_is_routed_by_the_rule_valid_on_its_value_date() {
        // Today: Paris in EUR goes serial via Bank B. From Thursday a new rule sends it by cover.
        RoutingRuleView serial = refdata.createRoutingRule(new CreateRoutingRuleCommand(key(), "MNPEFRPP*", "EUR",
                RoutingMethod.SERIAL, BANK_B, "T-NOSTRO-EUR-MNPB", 20, TODAY, null, null, "r.lim", "a.wong", "Paris serial")).value();
        RoutingRuleView cover = refdata.createRoutingRule(new CreateRoutingRuleCommand(key(), "MNPEFRPP*", "EUR",
                RoutingMethod.COVER, BANK_B, "T-NOSTRO-EUR-MNPB", 20, TODAY.plusDays(2), null, serial.ruleId(), "r.lim", "a.wong",
                "Paris cover from Thursday")).value();

        AccountView debtor = customer("EUR", "1000.00");
        CreatePaymentCommand toParis = to(payment(debtor, "100.00", ChargeBearer.SHAR), "FR7630006000011234567890189", "MNPEFRPPXXX");
        PaymentView today = hub.create(toParis).value();
        PaymentView thursday = hub.create(withDate(to(payment(debtor, "100.00", ChargeBearer.SHAR),
                "FR7630006000011234567890189", "MNPEFRPPXXX"), TODAY.plusDays(2))).value();

        assertThat(today.routingRuleId()).isEqualTo(serial.ruleId());
        assertThat(today.instructedAgentBic()).isEqualTo(BANK_B); // serial: our message goes to the correspondent
        assertThat(thursday.status()).isEqualTo(PaymentStatus.FUNDS_RESERVED);

        clock.advance(Duration.ofDays(2));
        try {
            sweeper.resumeStuckPayments();
            PaymentView sent = hub.get(thursday.uetr()).payment();
            assertThat(sent.status()).isEqualTo(PaymentStatus.SENT);
            assertThat(sent.routingRuleId()).isEqualTo(cover.ruleId());
            assertThat(sent.instructedAgentBic()).isEqualTo("MNPEFRPPXXX"); // cover: pacs.008 to the creditor agent
            assertThat(hub.get(thursday.uetr()).history().get(4).detail()).contains("cover via " + BANK_B);
        } finally {
            clock.advance(Duration.ofDays(-2));
        }
    }

    @Test
    void insufficient_funds_is_rejected_AM04_and_leaves_no_hold() {
        AccountView debtor = customer("EUR", "100.00");
        // 90 + 20 fee = 110 > 100
        PaymentView p = assertRejected(payment(debtor, "90.00", ChargeBearer.SHAR), "AM04");

        assertThat(hub.get(p.uetr()).history()).extracting(StateChangeView::to).containsExactly(
                PaymentStatus.RECEIVED, PaymentStatus.VALIDATED, PaymentStatus.SCREENED, PaymentStatus.REJECTED);
        assertThat(reload(debtor).reserved()).isEqualTo(eur("0.00"));
        assertThat(reload(debtor).available()).isEqualTo(eur("100.00"));
    }

    @Test
    void a_future_dated_payment_is_warehoused_until_its_date() {
        AccountView debtor = customer("EUR", "1000.00");
        PaymentView p = hub.create(withDate(payment(debtor, "100.00", ChargeBearer.SHAR), TODAY.plusDays(1))).value();

        assertThat(p.status()).isEqualTo(PaymentStatus.FUNDS_RESERVED);
        assertThat(reload(debtor).available()).isEqualTo(eur("880.00")); // funds held, not yet booked
        sweeper.resumeStuckPayments();
        assertThat(hub.get(p.uetr()).payment().status()).isEqualTo(PaymentStatus.FUNDS_RESERVED);

        clock.advance(Duration.ofDays(1));
        try {
            sweeper.resumeStuckPayments();
            PaymentView sent = hub.get(p.uetr()).payment();
            assertThat(sent.status()).isEqualTo(PaymentStatus.SENT);
            assertThat(sent.valueDate()).isEqualTo(TODAY.plusDays(1));
        } finally {
            clock.advance(Duration.ofDays(-1));
        }
    }

    @Test
    void network_events_take_a_sent_payment_through_acked_settled_and_returned() {
        AccountView debtor = customer("EUR", "50000.00");
        PaymentView p = hub.create(payment(debtor, "48250.00", ChargeBearer.SHAR)).value();
        Money nostro = reload(eurNostro).booked();

        assertThat(hub.onNetworkEvent(key(), p.uetr(), new NetworkEvent(NetworkEvent.Type.ACCEPTED, null, null, null))
                .value().status()).isEqualTo(PaymentStatus.ACKED);
        assertThat(hub.onNetworkEvent(key(), p.uetr(), new NetworkEvent(NetworkEvent.Type.SETTLED, null, null, null))
                .value().status()).isEqualTo(PaymentStatus.SETTLED);

        // Returned AC04 (creditor account closed); intermediaries kept EUR 25.00 of charges.
        PaymentView returned = hub.onNetworkEvent(key(), p.uetr(),
                new NetworkEvent(NetworkEvent.Type.RETURNED, "AC04", eur("48225.00"), "MNPB20261124-004-003317")).value();

        assertThat(returned.status()).isEqualTo(PaymentStatus.RETURNED);
        assertThat(returned.rejectionReason()).isEqualTo("AC04");
        // Debtor: 50,000 − 48,250 − 20 (our fee, not refunded) + 48,225 returned.
        assertThat(reload(debtor).booked()).isEqualTo(eur("49955.00"));
        assertThat(reload(eurNostro).booked()).isEqualTo(nostro.plus(eur("48225.00")));
    }

    @Test
    void the_state_machine_refuses_impossible_network_events() {
        AccountView debtor = customer("EUR", "100.00");
        PaymentView rejected = assertRejected(payment(debtor, "500.00", ChargeBearer.SHAR), "AM04");
        assertThatThrownBy(() -> hub.onNetworkEvent(key(), rejected.uetr(),
                new NetworkEvent(NetworkEvent.Type.ACCEPTED, null, null, null)))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).error()).isEqualTo(PaymentError.ILLEGAL_TRANSITION);

        PaymentView sent = hub.create(payment(customer("EUR", "100.00"), "10.00", ChargeBearer.SHAR)).value();
        // Cannot return what has not settled — and the ledger must not have been touched.
        assertThatThrownBy(() -> hub.onNetworkEvent(key(), sent.uetr(),
                new NetworkEvent(NetworkEvent.Type.RETURNED, "AC04", null, null)))
                .extracting(e -> ((PaymentException) e).error()).isEqualTo(PaymentError.ILLEGAL_TRANSITION);
        assertThat(ledger.entriesForPayment(sent.id())).hasSize(4);
    }

    @Test
    void idempotency_key_replays_or_refuses() {
        AccountView debtor = customer("EUR", "1000.00");
        CreatePaymentCommand cmd = payment(debtor, "100.00", ChargeBearer.SHAR);

        Replayable<PaymentView> first = hub.create(cmd);
        Replayable<PaymentView> again = hub.create(cmd);
        assertThat(first.replayed()).isFalse();
        assertThat(again.replayed()).isTrue();
        assertThat(again.value().uetr()).isEqualTo(first.value().uetr());
        assertThat(reload(debtor).booked()).isEqualTo(eur("880.00")); // paid once

        CreatePaymentCommand different = new CreatePaymentCommand(cmd.idempotencyKey(), cmd.debtorAccount(),
                cmd.creditorName(), cmd.creditorAccount(), cmd.creditorAgentBic(), eur("101.00"), cmd.chargeBearer(),
                null, cmd.endToEndId(), cmd.remittanceInformation());
        assertThatThrownBy(() -> hub.create(different))
                .extracting(e -> ((PaymentException) e).error()).isEqualTo(PaymentError.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void search_filters_by_status_currency_amount_and_text() {
        AccountView debtor = customer("GBP", "10000.00");
        PaymentView p = hub.create(new CreatePaymentCommand(key(), debtor.accountNumber(), "Pennine Textiles Ltd",
                "GB70MNPC40051261047725", BANK_C, Money.of("4321.09", "GBP"), ChargeBearer.DEBT, null,
                "E2E-SEARCH-" + debtor.accountNumber(), null)).value();

        var byText = hub.search(new PaymentQuery(Set.of(), null, null, null, null, null, null,
                "e2e-search-" + debtor.accountNumber().toLowerCase(), 0, 50));
        assertThat(byText.items()).extracting(PaymentView::uetr).containsExactly(p.uetr());

        var byUetr = hub.search(new PaymentQuery(Set.of(), null, null, null, null, null, null, p.uetr(), 0, 50));
        assertThat(byUetr.total()).isEqualTo(1);

        var filtered = hub.search(new PaymentQuery(Set.of(PaymentStatus.SENT), null, "GBP", TODAY, TODAY,
                new java.math.BigDecimal("4321.09"), new java.math.BigDecimal("4321.09"), null, 0, 50));
        assertThat(filtered.items()).extracting(PaymentView::uetr).contains(p.uetr());

        assertThat(hub.countByStatus().get(PaymentStatus.SENT)).isPositive();
    }

    private PaymentView assertRejected(CreatePaymentCommand cmd, String code) {
        PaymentView p = hub.create(cmd).value();
        assertThat(p.status()).as("status for expected " + code).isEqualTo(PaymentStatus.REJECTED);
        assertThat(p.rejectionReason()).isEqualTo(code);
        assertThat(hub.get(p.uetr()).history()).last()
                .satisfies(h -> assertThat(h.reasonCode()).isEqualTo(code));
        return p;
    }
}
