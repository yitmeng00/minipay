package com.minipay.settlement.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.minipay.settlement.internal.domain.QueuePolicy.Item;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QueuePolicyTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 26);

    static Item item(String prio, long seq, String amount) {
        return new Item(UUID.randomUUID(), prio, seq, new BigDecimal(amount), TODAY);
    }

    @Test
    void urgent_payments_settle_before_normal_ones_whatever_the_order_they_arrived_in() {
        Item normal = item("NORM", 1, "50");
        Item urgent = item("HIGH", 2, "80");
        var plan = QueuePolicy.plan(List.of(normal, urgent), new BigDecimal("100"), TODAY, "EUR");
        assertThat(plan.settle()).containsExactly(urgent.id());
        assertThat(plan.waiting().get(normal.id())).startsWith("Insufficient liquidity: needs EUR 50, available 20");
    }

    @Test
    void an_urgent_payment_that_does_not_fit_blocks_everything_behind_it() {
        Item big = item("HIGH", 1, "500");
        Item smallUrgent = item("HIGH", 2, "10");
        Item normal = item("NORM", 3, "10");
        var plan = QueuePolicy.plan(List.of(big, smallUrgent, normal), new BigDecimal("100"), TODAY, "EUR");
        assertThat(plan.settle()).isEmpty();
        assertThat(plan.waiting().get(smallUrgent.id())).contains("urgent payments are FIFO");
        assertThat(plan.waiting().get(normal.id())).isEqualTo("Behind urgent payments");
    }

    @Test
    void a_normal_payment_that_does_not_fit_is_bypassed_by_smaller_ones() {
        Item big = item("NORM", 1, "500");
        Item small = item("NORM", 2, "60");
        Item smaller = item("NORM", 3, "40");
        var plan = QueuePolicy.plan(List.of(big, small, smaller), new BigDecimal("100"), TODAY, "EUR");
        assertThat(plan.settle()).containsExactly(small.id(), smaller.id());
        assertThat(plan.waiting()).containsOnlyKeys(big.id());
    }

    @Test
    void moving_a_payment_up_the_queue_changes_which_one_uses_the_liquidity() {
        Item first = item("NORM", 1, "70");
        Item second = item("NORM", 0, "60"); // moved up: now seq 0
        var plan = QueuePolicy.plan(List.of(first, second), new BigDecimal("100"), TODAY, "EUR");
        assertThat(plan.settle()).containsExactly(second.id());
    }

    @Test
    void a_warehoused_payment_waits_for_its_settlement_date() {
        Item later = new Item(UUID.randomUUID(), "HIGH", 1, new BigDecimal("1"), TODAY.plusDays(1));
        var plan = QueuePolicy.plan(List.of(later), new BigDecimal("100"), TODAY, "EUR");
        assertThat(plan.settle()).isEmpty();
        assertThat(plan.waiting().get(later.id())).isEqualTo("Warehoused until 2026-11-27");
    }
}
