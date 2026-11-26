package com.minipay.network.internal.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.minipay.common.money.Money;
import com.minipay.messaging.api.Charge;
import com.minipay.network.api.Hop;
import com.minipay.network.api.TrackedMessage;
import com.minipay.network.api.TrackerView;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RouteBuilderTest {

    static final String A = "MNPAMYKLXXX", B = "MNPBDEFFXXX", C = "MNPCGB2LXXX";
    static final Instant T0 = Instant.parse("2026-11-26T01:00:00Z");
    static final String UETR = "eb6305c9-1f7f-49de-aed0-16487c27b42d";

    static TrackedMessage msg(int second, String type, String msgId, String from, String to, String orig, String amount,
                              String method, String status, List<Charge> charges) {
        // Every pacs.008 here is for Bank C (its CdtrAgt), whichever bank the hop goes to.
        return new TrackedMessage(UUID.randomUUID(), type, msgId, from, to, orig, amount == null ? null : eur(amount), method,
                type.startsWith("pacs.008") ? C : null, status, null, charges, T0.plusSeconds(second));
    }

    static Money eur(String a) {
        return Money.of(a, "EUR");
    }

    /** A → B → C, B and C each take their charge, C confirms. */
    static List<TrackedMessage> serial() {
        List<TrackedMessage> m = new ArrayList<>();
        m.add(msg(0, "pacs.008.001.08", "A-008", A, B, null, "1000.00", "INDA", null, List.of()));
        m.add(msg(1, "pacs.008.001.08", "B-008", B, C, null, "985.00", "INGA", null, List.of(new Charge(eur("15.00"), B))));
        m.add(msg(1, "pacs.002.001.10", "B-002", B, A, "A-008", null, null, "ACSP", List.of()));
        m.add(msg(2, "pacs.002.001.10", "C-002", C, B, "B-008", null, null, "ACSC", List.of(new Charge(eur("10.00"), C))));
        m.add(msg(3, "pacs.002.001.10", "B-002b", B, A, "A-008", null, null, "ACSC", List.of(new Charge(eur("10.00"), C))));
        return m;
    }

    @Test
    void a_serial_payment_is_two_hops_with_every_deduction_and_the_credited_amount() {
        TrackerView v = RouteBuilder.build(UETR, serial());
        assertThat(v.method()).isEqualTo("SERIAL");
        assertThat(v.status()).isEqualTo("ACSC");
        assertThat(v.debtorAgentBic()).isEqualTo(A);
        assertThat(v.creditorAgentBic()).isEqualTo(C);
        assertThat(v.hops()).extracting(Hop::fromBic, Hop::toBic, Hop::reply)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(A, B, "ACSC"), org.assertj.core.groups.Tuple.tuple(B, C, "ACSC"));
        assertThat(v.deductions()).containsExactly(new Charge(eur("15.00"), B), new Charge(eur("10.00"), C));
        assertThat(v.sentAmount()).isEqualTo(eur("1000.00"));
        assertThat(v.creditedAmount()).isEqualTo(eur("975.00"));
        assertThat(v.endToEnd()).isEqualTo(Duration.ofSeconds(2));
    }

    /** Held for compliance at Bank B: only the first hop exists, but the payment is still for Bank C. */
    @Test
    void a_payment_waiting_at_an_intermediary_is_serial_and_still_for_the_creditors_bank() {
        TrackerView v = RouteBuilder.build(UETR, serial().subList(0, 1));
        assertThat(v.method()).isEqualTo("SERIAL");
        assertThat(v.creditorAgentBic()).isEqualTo(C);
        assertThat(v.status()).isEqualTo("ACSP");
        assertThat(v.hops()).hasSize(1);
    }

    @Test
    void a_return_that_reaches_the_debtors_bank_makes_the_payment_returned() {
        List<TrackedMessage> m = serial();
        m.add(msg(10, "pacs.004.001.09", "C-004", C, B, "B-008", "975.00", null, "RTND",
                List.of(new Charge(eur("10.00"), C))));
        TrackerView inFlight = RouteBuilder.build(UETR, m);
        assertThat(inFlight.status()).isEqualTo("ACSC"); // only on its way back so far
        m.add(msg(11, "pacs.004.001.09", "B-004", B, A, "A-008", "975.00", null, "RTND",
                List.of(new Charge(eur("10.00"), C), new Charge(eur("15.00"), B))));
        TrackerView v = RouteBuilder.build(UETR, m);
        assertThat(v.status()).isEqualTo("RTND");
        assertThat(v.returnPath()).extracting(Hop::fromBic).containsExactly(C, B);
        assertThat(v.returnedAmount()).isEqualTo(eur("975.00"));
    }

    @Test
    void a_cover_payment_has_one_hop_and_two_cover_legs() {
        List<TrackedMessage> m = List.of(
                msg(0, "pacs.008.001.08", "A-008", A, C, null, "1000.00", "COVE", null, List.of()),
                msg(0, "pacs.009.001.08", "A-009", A, B, null, "1000.00", "INDA", null, List.of()),
                msg(1, "camt.054.001.08", "B-054", B, C, null, "1000.00", null, "CRDT", List.of()),
                msg(2, "pacs.002.001.10", "C-002", C, A, "A-008", null, null, "ACSC", List.of(new Charge(eur("10.00"), C))));
        TrackerView v = RouteBuilder.build(UETR, m);
        assertThat(v.method()).isEqualTo("COVER");
        assertThat(v.hops()).singleElement().satisfies(h -> assertThat(h.reply()).isEqualTo("ACSC"));
        assertThat(v.coverLegs()).extracting(Hop::fromBic, Hop::toBic)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(A, B), org.assertj.core.groups.Tuple.tuple(B, C));
        assertThat(v.creditedAmount()).isEqualTo(eur("990.00"));
    }

    @Test
    void a_rejection_that_reaches_the_debtors_bank_is_rjct() {
        List<TrackedMessage> m = new ArrayList<>(serial().subList(0, 3));
        m.add(msg(2, "pacs.002.001.10", "C-002", C, B, "B-008", null, null, "RJCT", List.of()));
        m.add(msg(3, "pacs.002.001.10", "B-002b", B, A, "A-008", null, null, "RJCT", List.of()));
        TrackerView v = RouteBuilder.build(UETR, m);
        assertThat(v.status()).isEqualTo("RJCT");
        assertThat(v.hops().get(1).reply()).isEqualTo("RJCT");
        assertThat(v.creditedAmount()).isNull();
    }
}
