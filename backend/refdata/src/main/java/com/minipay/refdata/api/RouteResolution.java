package com.minipay.refdata.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The outcome of routing a payment: the winning rule, or why there is none.
 *
 * @param route      present if a usable rule matched
 * @param problem    why there is no route (null if there is one)
 * @param considered every rule for this currency whose destination matches, in the order they were
 *                   ranked, each with a note on why it won or lost — what an operator needs when
 *                   asking "why did this payment go that way?"
 */
public record RouteResolution(
        String creditorAgentBic,
        String currency,
        LocalDate valueDate,
        Optional<Route> route,
        String problem,
        List<Candidate> considered) {

    /**
     * @param instructedAgentBic the bank that receives our pacs.008 (InstdAgt): the next hop for
     *                           SERIAL, otherwise the creditor agent itself
     * @param system             the clearing system the payment goes through (RTGS, DNS, INSTANT), or null
     */
    public record Route(RoutingRuleView rule, String instructedAgentBic, ClearingSystemView system) {

        public Route(RoutingRuleView rule, String instructedAgentBic) {
            this(rule, instructedAgentBic, null);
        }

        /**
         * Who our message is addressed to (AppHdr/To): the clearing system's operator for a payment that goes
         * through one, otherwise the instructed agent.
         */
        public String messageReceiverBic() {
            return system != null ? system.operatorBic() : instructedAgentBic;
        }
    }

    public record Candidate(RoutingRuleView rule, String note) {
    }
}
