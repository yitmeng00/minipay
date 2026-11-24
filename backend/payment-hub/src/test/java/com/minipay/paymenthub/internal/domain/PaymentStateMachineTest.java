package com.minipay.paymenthub.internal.domain;

import static com.minipay.paymenthub.api.PaymentStatus.ACKED;
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

/** Every (from, to) pair — all 100 of them — is either explicitly allowed or must throw. */
class PaymentStateMachineTest {

    static final Map<PaymentStatus, Set<PaymentStatus>> EXPECTED = Map.of(
            RECEIVED, EnumSet.of(VALIDATED, REJECTED),
            VALIDATED, EnumSet.of(SCREENED, REJECTED),
            SCREENED, EnumSet.of(FUNDS_RESERVED, HELD_COMPLIANCE, REJECTED),
            HELD_COMPLIANCE, EnumSet.of(FUNDS_RESERVED, REJECTED),
            FUNDS_RESERVED, EnumSet.of(SENT, REJECTED),
            SENT, EnumSet.of(ACKED),
            ACKED, EnumSet.of(SETTLED),
            SETTLED, EnumSet.of(RETURNED),
            REJECTED, EnumSet.noneOf(PaymentStatus.class),
            RETURNED, EnumSet.noneOf(PaymentStatus.class));

    static Stream<Arguments> allPairs() {
        return Stream.of(PaymentStatus.values())
                .flatMap(from -> Stream.of(PaymentStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allPairs")
    void transition_is_allowed_exactly_when_listed(PaymentStatus from, PaymentStatus to) {
        boolean expected = EXPECTED.get(from).contains(to);
        assertThat(PaymentStateMachine.canTransition(from, to)).isEqualTo(expected);
        if (expected) {
            assertThatCode(() -> PaymentStateMachine.check(from, to)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> PaymentStateMachine.check(from, to))
                    .isInstanceOf(PaymentException.class)
                    .extracting(e -> ((PaymentException) e).error()).isEqualTo(PaymentError.ILLEGAL_TRANSITION);
        }
    }

    @Test
    void rejection_is_only_possible_before_the_payment_leaves_the_bank() {
        for (PaymentStatus s : EnumSet.of(SENT, ACKED, SETTLED, RETURNED, REJECTED)) {
            assertThat(PaymentStateMachine.canTransition(s, REJECTED)).as(s + " -> REJECTED").isFalse();
        }
    }

    @Test
    void final_states_have_no_way_out() {
        assertThat(PaymentStateMachine.allowedFrom(REJECTED)).isEmpty();
        assertThat(PaymentStateMachine.allowedFrom(RETURNED)).isEmpty();
        assertThat(REJECTED.isFinal()).isTrue();
        assertThat(SETTLED.isFinal()).isFalse(); // a settled payment can still be returned
    }
}
