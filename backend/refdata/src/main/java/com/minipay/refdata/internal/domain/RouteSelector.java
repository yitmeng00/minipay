package com.minipay.refdata.internal.domain;

import com.minipay.common.id.Bic;
import com.minipay.refdata.api.BicEntry;
import com.minipay.refdata.api.Connectivity;
import com.minipay.refdata.api.RouteResolution;
import com.minipay.refdata.api.RouteResolution.Candidate;
import com.minipay.refdata.api.RouteResolution.Route;
import com.minipay.refdata.api.RoutingMethod;
import com.minipay.refdata.api.RoutingRuleView;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Chooses the routing rule for (creditor agent, currency, value date).
 *
 * <ol>
 *   <li>Candidates: rules in the currency whose destination covers the creditor agent's institution
 *       (its BIC8, or {@code *}).</li>
 *   <li>Only rules valid on the <b>value date</b> count. That is the date the money moves, not today. A
 *       payment warehoused until next week is routed by next week's rules.</li>
 *   <li>Lowest priority number wins. On equal priority, an institution-specific rule beats {@code *},
 *       then the lower rule id wins, so the choice is always deterministic.</li>
 *   <li>The winner must be usable: whoever receives our message must be CONNECTED in the BIC directory
 *       on that date. For SERIAL that is the next hop; for DIRECT and COVER the creditor agent; COVER also
 *       needs the next hop for the cover payment. An RTGS route only needs the creditor agent to exist,
 *       because RTGS participation is separate from network messaging.</li>
 * </ol>
 * An unusable winner is <b>not</b> skipped in favour of the runner-up: a quiet fall-through would send
 * money by a route nobody chose for that destination. It is reported, and the payment is rejected CNOR.
 */
public final class RouteSelector {

    private RouteSelector() {
    }

    public static RouteResolution select(List<RoutingRuleView> rules, String creditorAgentBic, String currency,
                                         LocalDate valueDate, Function<String, Optional<BicEntry>> directory) {
        Bic agent = Bic.parse(creditorAgentBic);
        List<RoutingRuleView> matching = rules.stream()
                .filter(r -> r.currency().equals(currency) && r.matches(agent.bic8()))
                .sorted(Comparator.comparing((RoutingRuleView r) -> !r.validOn(valueDate))
                        .thenComparingInt(RoutingRuleView::priority)
                        .thenComparing(RoutingRuleView::isCatchAll)
                        .thenComparing(RoutingRuleView::ruleId))
                .toList();

        List<Candidate> considered = new ArrayList<>();
        RoutingRuleView winner = null;
        for (RoutingRuleView r : matching) {
            String note;
            if (!r.validOn(valueDate)) {
                note = r.effectiveFrom().isAfter(valueDate) ? "Scheduled: starts " + r.effectiveFrom()
                        : "Expired: ended " + r.effectiveTo();
            } else if (winner == null) {
                winner = r;
                note = "Wins: lowest priority valid on " + valueDate;
            } else {
                note = "Loses: priority " + r.priority() + " vs " + winner.priority()
                        + (r.priority() == winner.priority() ? " (less specific or higher id)" : "");
            }
            considered.add(new Candidate(r, note));
        }

        if (winner == null) {
            return new RouteResolution(agent.value(), currency, valueDate, Optional.empty(),
                    "No routing rule for " + agent.bic8() + " in " + currency + " valid on " + valueDate, considered);
        }
        String instructedAgent = winner.method() == RoutingMethod.SERIAL ? winner.nextHopBic() : agent.value();
        String problem = null;
        if (winner.method() != RoutingMethod.RTGS) {
            problem = unreachable(instructedAgent, valueDate, directory);
        }
        if (problem == null && winner.method() == RoutingMethod.COVER) {
            problem = unreachable(winner.nextHopBic(), valueDate, directory);
        }
        if (problem != null) {
            return new RouteResolution(agent.value(), currency, valueDate, Optional.empty(),
                    winner.ruleId() + " cannot be used: " + problem, considered);
        }
        return new RouteResolution(agent.value(), currency, valueDate, Optional.of(new Route(winner, instructedAgent)),
                null, considered);
    }

    private static String unreachable(String bic, LocalDate on, Function<String, Optional<BicEntry>> directory) {
        Optional<BicEntry> entry = directory.apply(bic);
        if (entry.isEmpty()) {
            return bic + " is not in the BIC directory on " + on;
        }
        if (entry.get().connectivity() != Connectivity.CONNECTED) {
            return bic + " is " + entry.get().connectivity() + " (we cannot send to it)";
        }
        return null;
    }
}
