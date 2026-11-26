package com.minipay.settlement.internal.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Deferred net settlement: what a cycle's payments add up to, and what can actually settle.
 *
 * <ul>
 *   <li><b>Gross:</b> every payment on its own. Settling gross needs as much liquidity as is paid.</li>
 *   <li><b>Bilateral net:</b> per pair of banks, only the difference between what each pays the other.</li>
 *   <li><b>Multilateral net:</b> per bank, everything it receives minus everything it pays, from and to
 *       everyone. The net positions sum to zero; only the net payers pay in, to the net receivers. This is
 *       what an ACH settles, and why it needs so little liquidity.</li>
 * </ul>
 *
 * <h2>Unwinding</h2>
 * A net payer that cannot cover its position at settlement time breaks the cycle: the net positions of
 * everyone else assumed its payments. Without a settlement guarantee the ACH <em>unwinds</em> it: every
 * payment to and from that bank is taken out and the positions are computed again. Other banks then find
 * that payments they counted on are gone, and may in turn be short: the systemic risk of deferred net
 * settlement (the Herstatt and Lamfalussy lessons). Unwinding repeats, biggest shortfall first, until the
 * rest settles.
 */
public final class Netting {

    private Netting() {
    }

    public record Obligation(UUID id, String payer, String payee, BigDecimal amount) {
    }

    public record Position(String bic, BigDecimal pays, BigDecimal receives) {
        public BigDecimal net() {
            return receives.subtract(pays);
        }
    }

    /** @param settled the obligations that settle; @param unwound banks taken out, in the order they were */
    public record Outcome(List<Obligation> settled, List<Obligation> removed, Map<String, Position> positions,
                          List<String> unwound) {
    }

    /** Pays and receives per bank (every listed participant appears, with zeros if it has nothing). */
    public static Map<String, Position> positions(Collection<Obligation> obligations, Collection<String> participants) {
        Map<String, BigDecimal> pays = new TreeMap<>();
        Map<String, BigDecimal> receives = new TreeMap<>();
        participants.forEach(b -> {
            pays.put(b, BigDecimal.ZERO);
            receives.put(b, BigDecimal.ZERO);
        });
        for (Obligation o : obligations) {
            pays.merge(o.payer(), o.amount(), BigDecimal::add);
            receives.merge(o.payee(), o.amount(), BigDecimal::add);
            pays.putIfAbsent(o.payee(), BigDecimal.ZERO);
            receives.putIfAbsent(o.payer(), BigDecimal.ZERO);
        }
        Map<String, Position> out = new TreeMap<>();
        pays.forEach((b, p) -> out.put(b, new Position(b, p, receives.get(b))));
        return out;
    }

    /** payer → payee → total. */
    public static Map<String, Map<String, BigDecimal>> gross(Collection<Obligation> obligations) {
        Map<String, Map<String, BigDecimal>> m = new TreeMap<>();
        for (Obligation o : obligations) {
            m.computeIfAbsent(o.payer(), k -> new TreeMap<>()).merge(o.payee(), o.amount(), BigDecimal::add);
        }
        return m;
    }

    public static BigDecimal grossTotal(Collection<Obligation> obligations) {
        return obligations.stream().map(Obligation::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** What settling each pair's difference would move. */
    public static BigDecimal bilateralTotal(Collection<Obligation> obligations) {
        Map<String, Map<String, BigDecimal>> g = gross(obligations);
        Set<String> banks = new LinkedHashSet<>();
        obligations.forEach(o -> {
            banks.add(o.payer());
            banks.add(o.payee());
        });
        List<String> list = new ArrayList<>(banks);
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                BigDecimal ab = g.getOrDefault(list.get(i), Map.of()).getOrDefault(list.get(j), BigDecimal.ZERO);
                BigDecimal ba = g.getOrDefault(list.get(j), Map.of()).getOrDefault(list.get(i), BigDecimal.ZERO);
                total = total.add(ab.subtract(ba).abs());
            }
        }
        return total;
    }

    /** What settling the multilateral net positions moves: the sum of the net debits (= the sum of the net credits). */
    public static BigDecimal multilateralTotal(Collection<Obligation> obligations) {
        return positions(obligations, List.of()).values().stream().map(Position::net)
                .filter(n -> n.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Settles a cycle: unwinds net payers that cannot cover their position until every remaining net payer can.
     *
     * @param available each bank's available liquidity on its settlement account (missing = zero)
     */
    public static Outcome settle(List<Obligation> obligations, Map<String, BigDecimal> available, Collection<String> participants) {
        List<Obligation> remaining = new ArrayList<>(obligations);
        List<Obligation> removed = new ArrayList<>();
        List<String> unwound = new ArrayList<>();
        while (true) {
            Map<String, Position> positions = positions(remaining, participants);
            String worst = positions.values().stream()
                    .filter(p -> shortfall(p, available).signum() > 0)
                    .max(Comparator.comparing((Position p) -> shortfall(p, available)).thenComparing(Position::bic,
                            Comparator.reverseOrder()))
                    .map(Position::bic).orElse(null);
            if (worst == null) {
                return new Outcome(List.copyOf(remaining), List.copyOf(removed), positions, List.copyOf(unwound));
            }
            unwound.add(worst);
            remaining.removeIf(o -> {
                boolean involved = o.payer().equals(worst) || o.payee().equals(worst);
                if (involved) {
                    removed.add(o);
                }
                return involved;
            });
        }
    }

    private static BigDecimal shortfall(Position p, Map<String, BigDecimal> available) {
        BigDecimal debit = p.net().negate();
        return debit.signum() <= 0 ? BigDecimal.ZERO : debit.subtract(available.getOrDefault(p.bic(), BigDecimal.ZERO));
    }
}
