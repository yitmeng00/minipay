package com.minipay.settlement.internal.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Which of a participant's queued RTGS payments settle now, and why the others wait (as T2 does it):
 *
 * <ol>
 *   <li><b>Urgent before normal.</b> HIGH payments come first; within a priority, queue order (FIFO,
 *       which the participant can change by moving a payment up).</li>
 *   <li><b>Urgent payments are strictly FIFO.</b> If the first urgent payment does not fit, no later payment
 *       of any priority settles: a big urgent payment is not overtaken by smaller ones.</li>
 *   <li><b>Normal payments may bypass (FIFO-bypassing).</b> A normal payment that does not fit waits, and a
 *       smaller one behind it may settle: liquidity is used rather than left idle.</li>
 *   <li><b>Warehoused</b> payments (a later settlement date) wait for their day.</li>
 * </ol>
 * Liquidity is the account's available balance; there is no intraday credit and no gridlock resolution
 * (offsetting of queued payments between two participants), both documented in ADR-008.
 */
public final class QueuePolicy {

    private QueuePolicy() {
    }

    public record Item(UUID id, String priority, long seq, BigDecimal amount, LocalDate settlementDate) {
        boolean urgent() {
            return "HIGH".equals(priority);
        }
    }

    /** @param settle in settlement order; @param waiting why each other payment waits */
    public record Plan(List<UUID> settle, Map<UUID, String> waiting) {
    }

    public static final Comparator<Item> ORDER = Comparator.comparing((Item i) -> !i.urgent()).thenComparingLong(Item::seq);

    public static Plan plan(List<Item> queue, BigDecimal available, LocalDate today, String currency) {
        List<Item> ordered = new ArrayList<>(queue);
        ordered.sort(ORDER);
        BigDecimal left = available;
        boolean blocked = false;
        List<UUID> settle = new ArrayList<>();
        Map<UUID, String> waiting = new LinkedHashMap<>();
        for (Item i : ordered) {
            if (i.settlementDate().isAfter(today)) {
                waiting.put(i.id(), "Warehoused until " + i.settlementDate());
            } else if (blocked) {
                waiting.put(i.id(), i.urgent() ? "Behind an earlier urgent payment (urgent payments are FIFO)" : "Behind urgent payments");
            } else if (i.amount().compareTo(left) <= 0) {
                settle.add(i.id());
                left = left.subtract(i.amount());
            } else {
                waiting.put(i.id(), "Insufficient liquidity: needs " + currency + " " + i.amount().toPlainString()
                        + ", available " + left.toPlainString());
                blocked = i.urgent();
            }
        }
        return new Plan(settle, waiting);
    }
}
