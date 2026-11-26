package com.minipay.paymenthub.internal.domain;

import static com.minipay.paymenthub.api.PaymentStatus.ACKED;
import static com.minipay.paymenthub.api.PaymentStatus.AWAITING_COVER;
import static com.minipay.paymenthub.api.PaymentStatus.FUNDS_RESERVED;
import static com.minipay.paymenthub.api.PaymentStatus.HELD_COMPLIANCE;
import static com.minipay.paymenthub.api.PaymentStatus.RECEIVED;
import static com.minipay.paymenthub.api.PaymentStatus.REJECTED;
import static com.minipay.paymenthub.api.PaymentStatus.RETURNED;
import static com.minipay.paymenthub.api.PaymentStatus.SCREENED;
import static com.minipay.paymenthub.api.PaymentStatus.SENT;
import static com.minipay.paymenthub.api.PaymentStatus.SETTLED;
import static com.minipay.paymenthub.api.PaymentStatus.VALIDATED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minipay.paymenthub.api.PaymentDirection;
import com.minipay.paymenthub.api.PaymentError;
import com.minipay.paymenthub.api.PaymentException;
import com.minipay.paymenthub.api.PaymentStatus;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Every (direction, from, to) triple — all 242 of them — is either explicitly allowed or must throw. */
class PaymentStateMachineTest {

    static final Set<PaymentStatus> NONE = EnumSet.noneOf(PaymentStatus.class);

    static final Map<PaymentStatus, Set<PaymentStatus>> OUTGOING = Map.ofEntries(
            Map.entry(RECEIVED, EnumSet.of(VALIDATED, REJECTED)),
            Map.entry(VALIDATED, EnumSet.of(SCREENED, REJECTED)),
            Map.entry(SCREENED, EnumSet.of(FUNDS_RESERVED, HELD_COMPLIANCE, REJECTED)),
            Map.entry(HELD_COMPLIANCE, EnumSet.of(FUNDS_RESERVED, REJECTED)),
            Map.entry(FUNDS_RESERVED, EnumSet.of(SENT, REJECTED)),
            Map.entry(AWAITING_COVER, NONE),
            Map.entry(SENT, EnumSet.of(ACKED, SETTLED, REJECTED, RETURNED)),
            Map.entry(ACKED, EnumSet.of(SETTLED, REJECTED, RETURNED)),
            Map.entry(SETTLED, EnumSet.of(RETURNED)),
            Map.entry(REJECTED, NONE),
            Map.entry(RETURNED, NONE));

    static final Map<PaymentStatus, Set<PaymentStatus>> INCOMING = Map.ofEntries(
            Map.entry(RECEIVED, EnumSet.of(VALIDATED, REJECTED)),
            Map.entry(VALIDATED, EnumSet.of(SCREENED, REJECTED)),
            Map.entry(SCREENED, EnumSet.of(SETTLED, SENT, AWAITING_COVER, HELD_COMPLIANCE, REJECTED)),
            Map.entry(HELD_COMPLIANCE, EnumSet.of(REJECTED)),
            Map.entry(FUNDS_RESERVED, NONE),
            Map.entry(AWAITING_COVER, EnumSet.of(SETTLED, REJECTED)),
            Map.entry(SENT, EnumSet.of(ACKED, SETTLED, REJECTED, RETURNED)),
            Map.entry(ACKED, EnumSet.of(SETTLED, REJECTED, RETURNED)),
            Map.entry(SETTLED, EnumSet.of(RETURNED)),
            Map.entry(REJECTED, NONE),
            Map.entry(RETURNED, NONE));

    static Stream<Arguments> allTriples() {
        return Stream.of(PaymentDirection.values()).flatMap(d -> Stream.of(PaymentStatus.values())
                .flatMap(from -> Stream.of(PaymentStatus.values()).map(to -> Arguments.of(d, from, to))));
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @MethodSource("allTriples")
    void transition_is_allowed_exactly_when_listed(PaymentDirection d, PaymentStatus from, PaymentStatus to) {
        boolean expected = (d == PaymentDirection.OUTGOING ? OUTGOING : INCOMING).get(from).contains(to);
        assertThat(PaymentStateMachine.canTransition(d, from, to)).isEqualTo(expected);
        if (expected) {
            assertThatCode(() -> PaymentStateMachine.check(d, from, to)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> PaymentStateMachine.check(d, from, to))
                    .isInstanceOf(PaymentException.class)
                    .extracting(e -> ((PaymentException) e).error()).isEqualTo(PaymentError.ILLEGAL_TRANSITION);
        }
    }

    @Test
    void an_outgoing_payment_cannot_be_sent_without_reserving_the_funds() {
        for (PaymentStatus s : PaymentStatus.values()) {
            if (s != FUNDS_RESERVED) {
                assertThat(PaymentStateMachine.canTransition(PaymentDirection.OUTGOING, s, SENT)).as(s + " -> SENT").isFalse();
            }
        }
    }

    @Test
    void an_incoming_cover_payment_is_paid_out_only_from_awaiting_cover_or_after_screening() {
        assertThat(PaymentStateMachine.allowedFrom(PaymentDirection.INCOMING, AWAITING_COVER)).containsOnly(SETTLED, REJECTED);
        assertThat(PaymentStateMachine.canTransition(PaymentDirection.INCOMING, VALIDATED, SETTLED)).isFalse();
    }

    @Test
    void final_states_have_no_way_out() {
        for (PaymentDirection d : PaymentDirection.values()) {
            assertThat(PaymentStateMachine.allowedFrom(d, REJECTED)).isEmpty();
            assertThat(PaymentStateMachine.allowedFrom(d, RETURNED)).isEmpty();
        }
        assertThat(REJECTED.isFinal()).isTrue();
        assertThat(SETTLED.isFinal()).isFalse(); // a settled payment can still be returned
    }
}
