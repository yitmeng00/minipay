package com.minipay.recon.internal.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Matches our ledger's lines on a nostro against the servicer's statement entries for the same account (M8, ADR-009).
 *
 * <h2>Point of view</h2>
 * Every amount here is <b>signed from the account owner's point of view</b>: positive = money in. A ledger debit on
 * our nostro (an asset) is money in; on the servicer's statement that same movement is a CRDT, because the servicer
 * books our account as its liability. So a ledger Dr matches a statement CRDT, a ledger Cr a statement DBIT, and
 * after signing both sides a match simply has equal amounts.
 *
 * <h2>Rules, strongest first</h2>
 * <ol>
 *   <li><b>TAKE_ON</b>: the opening balances booked when the account was set up, on both sides, allowing for what each
 *       side already held before the first statement.</li>
 *   <li><b>UETR</b>: the same UETR and amount: unique per payment, the strongest key there is.</li>
 *   <li><b>BATCH</b>: one statement entry for several payments (a DNS cycle's net settlement): every transaction
 *       it lists matches one of our lines by UETR and amount, and they add up to the entry.</li>
 *   <li><b>E2E</b>: the same end-to-end id and amount (for a servicer that does not report UETRs).</li>
 *   <li><b>REFERENCE</b>: our line names the servicer's reference (AcctSvcrRef) and the amount is the same (our
 *       adjustments for a servicer's own bookings, such as a charge).</li>
 *   <li><b>CONTRA</b>: on one side only, a booking and its exact opposite for the same payment (a payment we sent and
 *       reversed after a rejection; a payment the servicer settled and that came back). They cancel out.</li>
 * </ol>
 * What is left is a <b>break</b>, typed by what is wrong.
 */
public final class Matcher {

    private Matcher() {
    }

    public enum Rule { TAKE_ON, UETR, BATCH, E2E, REFERENCE, CONTRA, ADJUSTMENT, MANUAL }

    public enum BreakType {
        /** On our books, not (yet) on the statement: often timing, e.g. booked after the servicer's cut. */
        MISSING_IN_STATEMENT,
        /** On the statement, not on our books: e.g. a charge the servicer took, or a payment we never booked. */
        MISSING_IN_LEDGER,
        /** The same payment on both sides, with different amounts: e.g. a charge deducted on the way. */
        AMOUNT_MISMATCH,
        /** The servicer booked a payment twice. */
        DUPLICATE
    }

    public static final String TAKE_ON_NARRATIVE = "Opening balance";

    public record LedgerItem(long id, LocalDate date, Instant bookedAt, BigDecimal signed, String uetr, String endToEndId,
                             String narrative) {
    }

    public record Tx(String uetr, String endToEndId, BigDecimal signed) {
    }

    public record StatementItem(long id, LocalDate date, Instant bookedAt, BigDecimal signed, String servicerRef, String info,
                                List<Tx> txs) {
        Optional<Tx> single() {
            return txs.size() == 1 ? Optional.of(txs.get(0)) : Optional.empty();
        }
    }

    public record Match(Rule rule, List<Long> ledger, List<Long> statement) {
    }

    /** @param difference statement − ledger, owner's view: what this break adds to the gap between the balances */
    public record Break(BreakType type, Long ledgerId, Long statementId, BigDecimal difference) {
    }

    public record Result(List<Match> matches, List<Break> breaks) {
    }

    /**
     * @param ledger            our unmatched lines
     * @param statement         the statements' unmatched entries
     * @param matchedStatement  UETR + amount of statement entries matched earlier (to recognise a duplicate)
     */
    public static Result match(List<LedgerItem> ledger, List<StatementItem> statement, Set<String> matchedStatement) {
        return match(ledger, statement, matchedStatement, BigDecimal.ZERO);
    }

    /**
     * @param openingDiff the first statement's opening balance minus our balance the day before. Taking on an account
     *                    is right when both sides end up with the same balance: our balance before plus our opening
     *                    lines equals the statement's opening plus its opening entries.
     */
    public static Result match(List<LedgerItem> ledger, List<StatementItem> statement, Set<String> matchedStatement,
                               BigDecimal openingDiff) {
        List<LedgerItem> l = new ArrayList<>(ledger);
        List<StatementItem> s = new ArrayList<>(statement);
        l.sort(Comparator.comparing(LedgerItem::bookedAt, Comparator.nullsLast(Comparator.naturalOrder())).thenComparingLong(LedgerItem::id));
        s.sort(Comparator.comparing(StatementItem::bookedAt, Comparator.nullsLast(Comparator.naturalOrder())).thenComparingLong(StatementItem::id));
        List<Match> matches = new ArrayList<>();
        Set<String> seen = new HashSet<>(matchedStatement);

        // TAKE_ON: everything booked as an opening balance on either side, if the totals agree.
        List<LedgerItem> lo = l.stream().filter(x -> TAKE_ON_NARRATIVE.equals(x.narrative())).toList();
        List<StatementItem> so = s.stream().filter(x -> TAKE_ON_NARRATIVE.equals(x.info())).toList();
        if (!lo.isEmpty() && !so.isEmpty() && sum(lo.stream().map(LedgerItem::signed)).subtract(sum(so.stream().map(StatementItem::signed)))
                .compareTo(openingDiff) == 0) {
            matches.add(new Match(Rule.TAKE_ON, lo.stream().map(LedgerItem::id).toList(), so.stream().map(StatementItem::id).toList()));
            l.removeAll(lo);
            s.removeAll(so);
        }

        // UETR, then E2E, then REFERENCE: one ledger line for one statement entry.
        oneToOne(l, s, matches, seen, Rule.UETR, (st, li) -> st.single().map(t -> t.uetr() != null && t.uetr().equals(li.uetr())).orElse(false));
        batch(l, s, matches, seen);
        oneToOne(l, s, matches, seen, Rule.E2E, (st, li) -> st.single().map(t -> t.endToEndId() != null
                && !"NOTPROVIDED".equals(t.endToEndId()) && t.endToEndId().equals(li.endToEndId())).orElse(false));
        oneToOne(l, s, matches, seen, Rule.REFERENCE, (st, li) -> st.servicerRef() != null && li.narrative() != null
                && li.narrative().contains(st.servicerRef()));

        // CONTRA: a booking and its exact opposite for the same payment, on one side.
        contra(l, LedgerItem::uetr, LedgerItem::signed, LedgerItem::id).forEach(p -> {
            matches.add(new Match(Rule.CONTRA, p, List.of()));
            l.removeIf(x -> p.contains(x.id()));
        });
        contra(s, x -> x.single().map(Tx::uetr).orElse(null), StatementItem::signed, StatementItem::id).forEach(p -> {
            matches.add(new Match(Rule.CONTRA, List.of(), p));
            s.removeIf(x -> p.contains(x.id()));
        });

        // Breaks.
        List<Break> breaks = new ArrayList<>();
        for (StatementItem st : new ArrayList<>(s)) {
            String uetr = st.single().map(Tx::uetr).orElse(null);
            if (uetr == null) {
                continue;
            }
            if (seen.contains(key(uetr, st.signed()))) {
                breaks.add(new Break(BreakType.DUPLICATE, null, st.id(), st.signed()));
                s.remove(st);
                continue;
            }
            Optional<LedgerItem> same = l.stream().filter(x -> uetr.equals(x.uetr()) && x.signed().signum() == st.signed().signum()).findFirst();
            if (same.isPresent()) {
                breaks.add(new Break(BreakType.AMOUNT_MISMATCH, same.get().id(), st.id(), st.signed().subtract(same.get().signed())));
                s.remove(st);
                l.remove(same.get());
            }
        }
        l.forEach(x -> breaks.add(new Break(BreakType.MISSING_IN_STATEMENT, x.id(), null, x.signed().negate())));
        s.forEach(x -> breaks.add(new Break(BreakType.MISSING_IN_LEDGER, null, x.id(), x.signed())));
        return new Result(matches, breaks);
    }

    private static void oneToOne(List<LedgerItem> l, List<StatementItem> s, List<Match> out, Set<String> seen, Rule rule,
                                 java.util.function.BiPredicate<StatementItem, LedgerItem> key) {
        for (StatementItem st : new ArrayList<>(s)) {
            Predicate<LedgerItem> fits = li -> li.signed().compareTo(st.signed()) == 0 && key.test(st, li);
            Optional<LedgerItem> hit = l.stream().filter(fits).findFirst();
            if (hit.isPresent()) {
                out.add(new Match(rule, List.of(hit.get().id()), List.of(st.id())));
                st.single().map(Tx::uetr).ifPresent(u -> seen.add(key(u, st.signed())));
                l.remove(hit.get());
                s.remove(st);
            }
        }
    }

    private static void batch(List<LedgerItem> l, List<StatementItem> s, List<Match> out, Set<String> seen) {
        for (StatementItem st : new ArrayList<>(s)) {
            if (st.txs().size() < 2 || sum(st.txs().stream().map(Tx::signed)).compareTo(st.signed()) != 0) {
                continue;
            }
            List<LedgerItem> found = new ArrayList<>();
            for (Tx t : st.txs()) {
                l.stream().filter(li -> !found.contains(li) && t.uetr() != null && t.uetr().equals(li.uetr())
                        && li.signed().compareTo(t.signed()) == 0).findFirst().ifPresent(found::add);
            }
            if (found.size() == st.txs().size()) {
                out.add(new Match(Rule.BATCH, found.stream().map(LedgerItem::id).toList(), List.of(st.id())));
                l.removeAll(found);
                s.remove(st);
                st.txs().forEach(t -> seen.add(key(t.uetr(), t.signed())));
            }
        }
    }

    private static <T> List<List<Long>> contra(List<T> items, java.util.function.Function<T, String> uetr,
                                               java.util.function.Function<T, BigDecimal> signed,
                                               java.util.function.ToLongFunction<T> id) {
        Map<String, List<T>> byUetr = new LinkedHashMap<>();
        items.stream().filter(x -> uetr.apply(x) != null).forEach(x -> byUetr.computeIfAbsent(uetr.apply(x), k -> new ArrayList<>()).add(x));
        List<List<Long>> pairs = new ArrayList<>();
        for (List<T> group : byUetr.values()) {
            List<T> left = new ArrayList<>(group);
            for (T a : group) {
                if (!left.contains(a)) {
                    continue;
                }
                Optional<T> b = left.stream().filter(x -> x != a && signed.apply(x).compareTo(signed.apply(a).negate()) == 0).findFirst();
                if (b.isPresent()) {
                    pairs.add(List.of(id.applyAsLong(a), id.applyAsLong(b.get())));
                    left.remove(a);
                    left.remove(b.get());
                }
            }
        }
        return pairs;
    }

    public static String key(String uetr, BigDecimal signed) {
        return uetr + "|" + signed.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal sum(java.util.stream.Stream<BigDecimal> s) {
        return s.reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
