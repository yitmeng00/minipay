package com.minipay.settlement.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.minipay.settlement.internal.domain.Netting.Obligation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NettingTest {

    static final List<String> BANKS = List.of("A", "B", "C", "D", "E");

    /** The design's matrix (EUR millions, row pays column). */
    static final double[][] G = {
            {0, 4.20, 2.85, 1.10, 0.95},
            {3.60, 0, 5.40, 2.20, 1.75},
            {1.90, 4.80, 0, 0.85, 1.30},
            {0.70, 2.65, 0.60, 0, 0.40},
            {1.15, 1.95, 0.90, 0.55, 0}};

    static List<Obligation> design() {
        List<Obligation> out = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                if (G[i][j] > 0) {
                    out.add(new Obligation(UUID.randomUUID(), BANKS.get(i), BANKS.get(j), BigDecimal.valueOf(G[i][j]).setScale(2)));
                }
            }
        }
        return out;
    }

    static Obligation pay(String from, String to, String amount) {
        return new Obligation(UUID.randomUUID(), from, to, new BigDecimal(amount));
    }

    @Test
    void the_net_positions_sum_to_zero_and_net_settlement_needs_far_less_liquidity_than_gross() {
        List<Obligation> o = design();
        var positions = Netting.positions(o, BANKS);
        assertThat(positions.values().stream().map(Netting.Position::net).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("0");
        assertThat(positions.get("A").net()).isEqualByComparingTo("-1.75");  // receives 7.35, pays 9.10
        assertThat(positions.get("B").net()).isEqualByComparingTo("0.65");
        assertThat(Netting.grossTotal(o)).isEqualByComparingTo("39.80");
        assertThat(Netting.bilateralTotal(o)).isEqualByComparingTo("4.20");
        assertThat(Netting.multilateralTotal(o)).isEqualByComparingTo("1.90");
    }

    @Test
    void a_cycle_whose_net_payers_can_pay_settles_whole() {
        List<Obligation> o = List.of(pay("A", "B", "100"), pay("B", "A", "70"), pay("B", "C", "50"));
        var outcome = Netting.settle(o, Map.of("A", new BigDecimal("30"), "B", new BigDecimal("20")), BANKS);
        assertThat(outcome.unwound()).isEmpty();
        assertThat(outcome.settled()).hasSize(3);
        assertThat(outcome.positions().get("A").net()).isEqualByComparingTo("-30");
        assertThat(outcome.positions().get("B").net()).isEqualByComparingTo("-20");
        assertThat(outcome.positions().get("C").net()).isEqualByComparingTo("50");
    }

    @Test
    void a_net_payer_that_cannot_pay_is_unwound_and_its_counterparties_positions_change() {
        // A owes 100 net; it has 10. Unwinding A removes A's payment to B, so B, which counted on it,
        // now owes C 60 net and has only 50: B is unwound too. The knock-on effect is the systemic risk.
        List<Obligation> o = List.of(pay("A", "B", "100"), pay("B", "C", "60"), pay("D", "C", "5"));
        var outcome = Netting.settle(o, Map.of("A", new BigDecimal("10"), "B", new BigDecimal("50"),
                "D", new BigDecimal("5")), BANKS);
        assertThat(outcome.unwound()).containsExactly("A", "B");
        assertThat(outcome.settled()).extracting(Obligation::payer).containsExactly("D");
        assertThat(outcome.removed()).hasSize(2);
        assertThat(outcome.positions().get("C").net()).isEqualByComparingTo("5");
    }

    @Test
    void an_exactly_covered_position_settles() {
        var outcome = Netting.settle(List.of(pay("A", "B", "10.00")), Map.of("A", new BigDecimal("10.00")), BANKS);
        assertThat(outcome.unwound()).isEmpty();
    }
}
