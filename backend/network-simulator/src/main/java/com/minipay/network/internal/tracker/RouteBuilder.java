package com.minipay.network.internal.tracker;

import com.minipay.common.id.Bic;
import com.minipay.common.money.Money;
import com.minipay.messaging.api.Charge;
import com.minipay.network.api.Hop;
import com.minipay.network.api.TrackedMessage;
import com.minipay.network.api.TrackerView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Turns the messages seen for one UETR into its route, the way a gpi tracker reads a payment:
 * <ul>
 *   <li>each pacs.008 is a hop; the pacs.002 that answers it (same banks, other direction, pointing at
 *       its MsgId) is the hop's status;</li>
 *   <li>the debtor's bank sent the first pacs.008; the creditor's bank received the last one;</li>
 *   <li>a pacs.009 and its camt.054 are the cover legs; pacs.004s are the return path;</li>
 *   <li>the payment's status is the creditor's bank's ACSC, a RJCT that reached the debtor's bank, a
 *       pacs.004 that reached it, or else "on its way" (ACSP).</li>
 * </ul>
 */
public final class RouteBuilder {

    private RouteBuilder() {
    }

    public static TrackerView build(String uetr, List<TrackedMessage> unordered) {
        List<TrackedMessage> messages = unordered.stream().sorted(Comparator.comparing(TrackedMessage::sentAt)).toList();
        List<TrackedMessage> pacs008 = of(messages, "pacs.008.001.08");
        List<TrackedMessage> replies = of(messages, "pacs.002.001.10");

        List<Hop> hops = new ArrayList<>();
        for (TrackedMessage m : pacs008) {
            Optional<TrackedMessage> reply = replies.stream()
                    .filter(r -> same(r.fromBic(), m.toBic()) && same(r.toBic(), m.fromBic())
                            && (r.originalMsgId() == null || r.originalMsgId().equals(m.msgId())))
                    .reduce((a, b) -> rank(b.status()) >= rank(a.status()) ? b : a); // the most advanced answer
            hops.add(hop(hops.size() + 1, m, reply));
        }
        List<Hop> cover = new ArrayList<>();
        for (TrackedMessage m : messages) {
            if (m.type().equals("pacs.009.001.08") || m.type().equals("camt.054.001.08")) {
                cover.add(hop(cover.size() + 1, m, Optional.empty()));
            }
        }
        List<Hop> returns = new ArrayList<>();
        for (TrackedMessage m : of(messages, "pacs.004.001.09")) {
            returns.add(hop(returns.size() + 1, m, Optional.empty()));
        }

        String debtorAgent = pacs008.isEmpty() ? null : pacs008.get(0).fromBic();
        // The pacs.008 names the bank the payment is for (CdtrAgt). The last hop's receiver is only the
        // creditor's bank once the payment has got there: until then (held at an intermediary, say) it is
        // somewhere on the way.
        String creditorAgent = pacs008.isEmpty() ? null : pacs008.get(0).creditorAgentBic() != null
                ? pacs008.get(0).creditorAgentBic() : pacs008.get(pacs008.size() - 1).toBic();
        Optional<TrackedMessage> acsc = replies.stream()
                .filter(r -> "ACSC".equals(r.status()) && same(r.fromBic(), creditorAgent)).findFirst();
        Optional<TrackedMessage> rjct = replies.stream()
                .filter(r -> "RJCT".equals(r.status()) && same(r.toBic(), debtorAgent)).findFirst();
        Optional<TrackedMessage> returned = of(messages, "pacs.004.001.09").stream()
                .filter(r -> same(r.toBic(), debtorAgent)).reduce((a, b) -> b);

        String status = returned.isPresent() ? "RTND" : rjct.isPresent() ? "RJCT" : acsc.isPresent() ? "ACSC" : "ACSP";
        List<Charge> deductions = new ArrayList<>(pacs008.isEmpty() ? List.of() : pacs008.get(pacs008.size() - 1).charges());
        acsc.ifPresent(a -> a.charges().stream().filter(c -> deductions.stream().noneMatch(d -> same(d.agentBic(), c.agentBic())))
                .forEach(deductions::add));
        Money last = pacs008.isEmpty() ? null : pacs008.get(pacs008.size() - 1).amount();
        Money credited = acsc.isPresent() && last != null
                ? acsc.get().charges().stream().map(Charge::amount).reduce(last, Money::minus) : null;
        Duration endToEnd = acsc.isPresent() && !pacs008.isEmpty()
                ? Duration.between(pacs008.get(0).sentAt(), acsc.get().sentAt()) : null;
        String method = pacs008.isEmpty() ? null
                : "COVE".equals(pacs008.get(0).settlementMethod()) ? "COVER"
                : pacs008.size() > 1 || !same(pacs008.get(0).toBic(), creditorAgent) ? "SERIAL" : "DIRECT";

        return new TrackerView(uetr, status, method, debtorAgent, creditorAgent,
                pacs008.isEmpty() ? null : pacs008.get(0).amount(), credited,
                returned.map(TrackedMessage::amount).orElse(null), deductions, hops, cover, returns, messages, endToEnd);
    }

    private static Hop hop(int n, TrackedMessage m, Optional<TrackedMessage> reply) {
        return new Hop(n, m.type(), m.fromBic(), m.toBic(), m.msgId(), m.amount(), m.settlementMethod(), m.sentAt(),
                reply.map(TrackedMessage::status).orElse(null), reply.map(TrackedMessage::reasonCode).orElse(null),
                reply.map(TrackedMessage::sentAt).orElse(null));
    }

    /** ACSP/ACCP < ACSC/RJCT: a final answer outranks an acknowledgement. */
    private static int rank(String status) {
        return "ACSC".equals(status) || "RJCT".equals(status) ? 2 : 1;
    }

    private static List<TrackedMessage> of(List<TrackedMessage> messages, String type) {
        return messages.stream().filter(m -> m.type().equals(type)).toList();
    }

    private static boolean same(String a, String b) {
        return a != null && b != null && Bic.isValid(a) && Bic.isValid(b) && Bic.parse(a).bic8().equals(Bic.parse(b).bic8());
    }
}
