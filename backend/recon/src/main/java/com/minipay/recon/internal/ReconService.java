package com.minipay.recon.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.common.id.Bic;
import com.minipay.common.money.Money;
import com.minipay.ledger.api.AccountView;
import com.minipay.ledger.api.Direction;
import com.minipay.ledger.api.EntryType;
import com.minipay.ledger.api.JournalEntryView;
import com.minipay.ledger.api.LedgerApi;
import com.minipay.ledger.api.LedgerException;
import com.minipay.ledger.api.PostEntryCommand;
import com.minipay.ledger.api.PostingLine;
import com.minipay.ledger.api.PostingView;
import com.minipay.ledger.api.StatementLine;
import com.minipay.messaging.api.BankStatement;
import com.minipay.messaging.api.InboundMessage;
import com.minipay.messaging.api.MessageType;
import com.minipay.messaging.api.MessagingApi;
import com.minipay.messaging.api.StatementTx;
import com.minipay.paymenthub.api.PaymentHubApi;
import com.minipay.paymenthub.api.PaymentView;
import com.minipay.recon.api.ReconApi;
import com.minipay.recon.api.ReconError;
import com.minipay.recon.api.ReconException;
import com.minipay.recon.api.ReconViews;
import com.minipay.recon.internal.config.ReconProperties;
import com.minipay.recon.internal.domain.Matcher;
import com.minipay.recon.internal.persistence.ReconRepository;
import com.minipay.recon.internal.persistence.ReconRepository.BreakRow;
import com.minipay.recon.internal.persistence.ReconRepository.EntryRow;
import com.minipay.recon.internal.persistence.ReconRepository.ItemRow;
import com.minipay.recon.internal.persistence.ReconRepository.MatchItemRow;
import com.minipay.recon.internal.persistence.ReconRepository.MatchRow;
import com.minipay.recon.internal.persistence.ReconRepository.StatementRow;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The account owner's side of nostro reconciliation (ADR-009): receive the servicer's camt.053, match it against our
 * ledger's lines on the mirror account, and keep the breaks until they clear or people resolve them.
 *
 * <p>Matching works on <b>open items</b>, not on one day: a payment we booked today may be on the servicer's statement
 * tomorrow (it booked after its cut, or in its own time zone's next day). So every run takes everything not matched
 * yet on both sides, since the first statement, and a timing break clears by itself when the other side arrives.
 */
@Service
public class ReconService implements ReconApi {

    private static final Logger log = LoggerFactory.getLogger(ReconService.class);
    static final String SYSTEM = "svc/recon";

    private final ReconRepository repo;
    private final LedgerApi ledger;
    private final MessagingApi messaging;
    private final PaymentHubApi hub;
    private final ReconProperties props;
    private final StatementPublisher publisher;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;
    private final ZoneId bankZone;

    ReconService(ReconRepository repo, LedgerApi ledger, MessagingApi messaging, PaymentHubApi hub, ReconProperties props,
                 StatementPublisher publisher, TransactionTemplate tx, ObjectMapper json, ObjectProvider<Clock> clock,
                 @Value("${minipay.bank.time-zone:UTC}") ZoneId bankZone) {
        this.repo = repo;
        this.ledger = ledger;
        this.messaging = messaging;
        this.hub = hub;
        this.props = props;
        this.publisher = publisher;
        this.tx = tx;
        this.json = json;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
        this.bankZone = bankZone;
    }

    // ------------------------------------------------------------------ receiving

    @Override
    public ReconViews.Receipt receiveStatement(String raw) {
        InboundMessage in = messaging.receive(raw); // stored first, whatever happens next (rule 8)
        if (!in.valid() || in.message().type() != MessageType.CAMT_053) {
            throw new ReconException(ReconError.INVALID_MESSAGE, "Message stored (" + in.message().id() + ") but it is not a valid camt.053: "
                    + String.join("; ", in.message().errors()));
        }
        Optional<StatementRow> known = repo.statementByMessage(in.message().id());
        if (known.isPresent()) {
            return receipt(known.get(), true);
        }
        BankStatement s = messaging.bankStatement(in.message().id());
        ReconProperties.Nostro nostro = props.nostros().stream()
                .filter(n -> sameBank(n.servicer(), s.servicerBic()) && n.servicerAccount().equals(s.accountNumber()))
                .findFirst().orElseThrow(() -> new ReconException(ReconError.UNKNOWN_ACCOUNT, "camt.053 stored (" + in.message().id()
                        + "): " + s.servicerBic() + " account " + s.accountNumber() + " is not one of our nostros"));
        StatementRow row = new StatementRow(UUID.randomUUID(), in.message().id(), nostro.account(), Bic.parse(s.servicerBic()).value(),
                s.accountNumber(), s.statementId(), in.message().msgId(), s.date(), s.currency(), s.openingBooked().amount(),
                s.closingBooked().amount(), clock.instant());
        tx.executeWithoutResult(st -> {
            if (!repo.insertStatement(row)) {
                return; // a concurrent delivery stored it
            }
            int seq = 0;
            for (BankStatement.Entry e : s.entries()) {
                StatementTx single = e.txs().size() == 1 ? e.txs().get(0) : null;
                repo.insertEntry(new EntryRow(0, row.id(), ++seq, e.entryRef(), signed(e.amount(), e.credit()), e.bookedAt(),
                        e.valueDate(), e.servicerRef(), e.reversal(), trim(e.info(), 500), e.bankTxCode(),
                        single != null ? single.uetr() : null, single != null ? trim(single.endToEndId(), 35) : null,
                        writeTxs(e.txs()), e.xml(), s.date()));
            }
        });
        log.info("camt.053 {} for {} ({} entries, {}) from {}", s.statementId(), nostro.account(), s.entries().size(), s.date(), s.servicerBic());
        reconcile(nostro.account());
        return receipt(repo.statementByMessage(in.message().id()).orElseThrow(), false);
    }

    private ReconViews.Receipt receipt(StatementRow s, boolean replayed) {
        ReconViews.Reconciliation r = reconciliation(s.account(), s.date());
        return new ReconViews.Receipt(s.id(), s.account(), s.date(), r.statementEntries(), r.matched(), r.openBreaks(), replayed);
    }

    // ------------------------------------------------------------------ matching

    /** Takes our new ledger lines, matches every open item on both sides, and keeps the breaks in step. */
    void reconcile(String account) {
        List<StatementRow> statements = repo.statements(account);
        if (statements.isEmpty()) {
            return; // reconciliation starts with the first statement
        }
        AccountView acct = ledger.findAccountByNumber(account).orElseThrow();
        LocalDate start = statements.get(0).date();
        LocalDate today = LocalDate.now(clock.withZone(bankZone));
        LocalDate last = statements.get(statements.size() - 1).date();
        // A day of slack after: the servicer's date is in its time zone, ours in ours (Kuala Lumpur is a day ahead of
        // London for eight hours of every day).
        LocalDate until = (last.isAfter(today) ? last : today).plusDays(1);
        // Snapshot our lines with the payment references the ledger does not keep (outside the lock: ledger reads only).
        Map<UUID, PaymentView> payments = new HashMap<>();
        List<ItemRow> fresh = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(until); d = d.plusDays(1)) {
            for (StatementLine l : ledger.statement(acct.id(), d).lines()) {
                if (repo.hasItem(l.postingId())) {
                    continue;
                }
                PaymentView p = l.paymentId() == null ? null : payments.computeIfAbsent(l.paymentId(), this::payment);
                BigDecimal signed = l.debit() != null ? l.debit().amount() : l.credit().amount().negate();
                fresh.add(new ItemRow(l.postingId(), account, l.journalEntryId(), d, l.bookedAt(), signed, l.entryType().name(),
                        l.paymentId(), p == null ? null : p.uetr(), p == null ? null : trim(p.endToEndId(), 35), trim(l.narrative(), 500)));
            }
        }
        tx.executeWithoutResult(st -> {
            repo.lockAccount(account);
            fresh.forEach(repo::insertItem);
            List<ItemRow> items = repo.unmatchedItems(account);
            List<EntryRow> entries = repo.unmatchedEntries(account);
            Set<String> seen = repo.matchedStatementPayments(account).stream()
                    .map(x -> Matcher.key(x[0], new BigDecimal(x[1]))).collect(Collectors.toSet());
            Matcher.Result result = Matcher.match(items.stream().map(ReconService::ledgerItem).toList(),
                    entries.stream().map(this::statementItem).toList(), seen);
            Map<Long, BigDecimal> itemAmounts = items.stream().collect(Collectors.toMap(ItemRow::postingId, ItemRow::signed));
            Map<Long, BigDecimal> entryAmounts = entries.stream().collect(Collectors.toMap(EntryRow::id, EntryRow::signed));
            Instant now = clock.instant();
            List<BreakRow> active = repo.activeBreaks(account);
            for (Matcher.Match m : result.matches()) {
                long id = repo.insertMatch(account, m.rule().name(), SYSTEM, null, now);
                m.ledger().forEach(i -> repo.insertMatchItem(id, "LEDGER", i, itemAmounts.get(i)));
                m.statement().forEach(i -> repo.insertMatchItem(id, "STATEMENT", i, entryAmounts.get(i)));
                clearBreaksOf(active, m.ledger(), m.statement(), id);
            }
            // Breaks: keep the ones still true, open the new ones. One whose items changed (e.g. a timing break that
            // became an amount mismatch) is superseded: the new one is opened, the old is left to its people.
            Set<String> current = new HashSet<>();
            for (Matcher.Break b : result.breaks()) {
                String key = b.type() + "|" + b.ledgerId() + "|" + b.statementId();
                current.add(key);
                boolean exists = active.stream().anyMatch(x -> key.equals(x.type() + "|" + x.ledgerItemId() + "|" + x.statementEntryId()));
                if (!exists) {
                    repo.insertBreak(account, b.type().name(), b.ledgerId(), b.statementId(), b.difference(), now);
                }
            }
        });
        log.info("Reconciled {}", account);
    }

    private void clearBreaksOf(List<BreakRow> active, List<Long> ledgerIds, List<Long> statementIds, long matchId) {
        for (BreakRow b : active) {
            if ((b.ledgerItemId() != null && ledgerIds.contains(b.ledgerItemId()))
                    || (b.statementEntryId() != null && statementIds.contains(b.statementEntryId()))) {
                if (!"CLEARED".equals(b.status())) {
                    repo.clear(b.id(), matchId);
                }
            }
        }
    }

    private PaymentView payment(UUID id) {
        try {
            return hub.get(id.toString()).payment();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Matcher.LedgerItem ledgerItem(ItemRow i) {
        return new Matcher.LedgerItem(i.postingId(), i.date(), i.bookedAt(), i.signed(), i.uetr(), i.endToEndId(), i.narrative());
    }

    private Matcher.StatementItem statementItem(EntryRow e) {
        List<Matcher.Tx> txs = readTxs(e.txs()).stream()
                .map(t -> new Matcher.Tx(t.uetr(), t.endToEndId(), signed(t.amount(), t.credit()))).toList();
        return new Matcher.StatementItem(e.id(), e.statementDate(), e.bookedAt(), e.signed(), e.servicerRef(), e.info(), txs);
    }

    // ------------------------------------------------------------------ commands

    @Override
    public ReconViews.Published publishStatements(String key, LocalDate date) {
        LocalDate day = date != null ? date : LocalDate.now(clock.withZone(bankZone));
        String hash = sha256("publish|" + day);
        Optional<ReconRepository.StoredKey> stored = repo.key(key);
        if (stored.isPresent()) {
            requireSame(key, stored.get(), hash);
            return new ReconViews.Published(day, List.of(), true);
        }
        List<String> sent = publisher.publish(day);
        tx.executeWithoutResult(s -> {
            repo.lockKey(key);
            if (repo.key(key).isEmpty()) {
                repo.saveKey(key, hash, null, clock.instant());
            }
        });
        return new ReconViews.Published(day, sent, false);
    }

    @Override
    public ReconViews.Reconciliation rerun(String key, String account) {
        nostro(account);
        String hash = sha256("rerun|" + account);
        Optional<ReconRepository.StoredKey> stored = repo.key(key);
        if (stored.isPresent()) {
            requireSame(key, stored.get(), hash);
        } else {
            reconcile(account);
            tx.executeWithoutResult(s -> {
                repo.lockKey(key);
                if (repo.key(key).isEmpty()) {
                    repo.saveKey(key, hash, null, clock.instant());
                }
            });
        }
        return reconciliation(account, null);
    }

    /** What may be done about each kind of break. */
    static List<String> actions(String type) {
        return switch (type) {
            case "MISSING_IN_STATEMENT" -> List.of("CARRY_FORWARD");
            case "MISSING_IN_LEDGER", "AMOUNT_MISMATCH" -> List.of("ADJUSTMENT", "CLAIM_RAISED");
            case "DUPLICATE" -> List.of("CLAIM_RAISED");
            default -> List.of();
        };
    }

    @Override
    public ReconViews.BreakView resolve(String key, String breakId, ReconViews.Resolve c) {
        if (c == null || c.action() == null || c.by() == null || c.by().isBlank()) {
            throw new ReconException(ReconError.INVALID_REQUEST, "An action and who resolves it are required");
        }
        if (c.comment() == null || c.comment().trim().length() < 10 || c.comment().length() > 500) {
            throw new ReconException(ReconError.INVALID_REQUEST, "A comment of 10 to 500 characters is required: it is the audit trail");
        }
        String hash = sha256("resolve|" + breakId + "|" + c.action() + "|" + c.comment() + "|" + c.by());
        Optional<ReconRepository.StoredKey> stored = repo.key(key);
        if (stored.isPresent()) {
            requireSame(key, stored.get(), hash);
            return breakView(repo.findBreak(breakId).orElseThrow());
        }
        BreakRow b = repo.findBreak(breakId).orElseThrow(() -> new ReconException(ReconError.NOT_FOUND, "No break " + breakId));
        if (!"OPEN".equals(b.status())) {
            throw new ReconException(ReconError.ILLEGAL_ACTION, breakId + " is " + b.status());
        }
        if (!actions(b.type()).contains(c.action())) {
            throw new ReconException(ReconError.ILLEGAL_ACTION, c.action() + " does not resolve a " + b.type() + " break; possible: "
                    + actions(b.type()));
        }
        // An adjustment books the difference on our nostro, against the account for bank charges and differences,
        // naming the servicer's reference. Outside the recon transaction: the ledger is its own system, and its key
        // makes a retry replay instead of booking twice.
        Long adjustmentPosting = "ADJUSTMENT".equals(c.action()) ? postAdjustment(b) : null;
        tx.executeWithoutResult(s -> {
            repo.lockKey(key);
            if (repo.key(key).isPresent()) {
                return;
            }
            repo.lockAccount(b.account());
            BreakRow locked = repo.lockBreak(breakId).orElseThrow();
            if (!"OPEN".equals(locked.status())) {
                throw new ReconException(ReconError.ILLEGAL_ACTION, breakId + " is " + locked.status());
            }
            repo.resolve(breakId, c.action(), c.comment().trim(), c.by().trim(), clock.instant());
            if (adjustmentPosting != null) {
                long m = repo.insertMatch(b.account(), "ADJUSTMENT", c.by().trim(), c.comment().trim(), clock.instant());
                repo.insertMatchItem(m, "LEDGER", adjustmentPosting, b.difference());
                if (b.ledgerItemId() != null) {
                    repo.insertMatchItem(m, "LEDGER", b.ledgerItemId(), itemSigned(b.account(), b.ledgerItemId()));
                }
                if (b.statementEntryId() != null) {
                    repo.insertMatchItem(m, "STATEMENT", b.statementEntryId(), entrySigned(b.account(), b.statementEntryId()));
                }
            }
            repo.saveKey(key, hash, breakId, clock.instant());
        });
        return breakView(repo.findBreak(breakId).orElseThrow());
    }

    private Long postAdjustment(BreakRow b) {
        AccountView nostro = ledger.findAccountByNumber(b.account()).orElseThrow();
        String adjNumber = props.adjustmentAccounts().get(nostro.currency());
        AccountView adj = adjNumber == null ? null : ledger.findAccountByNumber(adjNumber).orElse(null);
        if (adj == null) {
            throw new ReconException(ReconError.ILLEGAL_ACTION, "No account for reconciliation adjustments in " + nostro.currency()
                    + " (minipay.recon.adjustment-accounts)");
        }
        EntryRow e = b.statementEntryId() == null ? null
                : repo.entries(b.account()).stream().filter(x -> x.id() == b.statementEntryId()).findFirst().orElse(null);
        Money diff = Money.of(b.difference().abs(), nostro.currency());
        boolean in = b.difference().signum() > 0; // the servicer shows more money than we do: debit our nostro
        String narrative = "Recon adjustment " + b.id() + (e != null && e.servicerRef() != null ? " · " + e.servicerRef() : "")
                + (e != null && e.info() != null ? " · " + e.info() : "");
        JournalEntryView entry;
        try {
            entry = ledger.post(new PostEntryCommand("recon:adj:" + b.id(), EntryType.SETTLE, null, e != null ? e.valueDate() : null,
                    trim(narrative, 500), List.of(new PostingLine(nostro.id(), in ? Direction.D : Direction.C, diff),
                    new PostingLine(adj.id(), in ? Direction.C : Direction.D, diff)))).value();
        } catch (LedgerException ex) {
            throw new ReconException(ReconError.ILLEGAL_ACTION, "Cannot book the adjustment: " + ex.getMessage());
        }
        PostingView p = entry.postings().stream().filter(x -> x.accountId() == nostro.id()).findFirst().orElseThrow();
        StatementLine line = new StatementLine(p.id(), entry.createdAt(), entry.id(), entry.entryType(), null, entry.valueDate(),
                narrative, adj.accountNumber(), in ? diff : null, in ? null : diff, null);
        tx.executeWithoutResult(s -> repo.insertItem(new ItemRow(line.postingId(), b.account(), entry.id(),
                LocalDate.ofInstant(entry.createdAt(), bankZone), entry.createdAt(), in ? diff.amount() : diff.amount().negate(),
                entry.entryType().name(), null, null, null, trim(narrative, 500))));
        return p.id();
    }

    @Override
    public ReconViews.BreakView reopen(String key, String breakId, String by) {
        String hash = sha256("reopen|" + breakId + "|" + by);
        Optional<ReconRepository.StoredKey> stored = repo.key(key);
        if (stored.isPresent()) {
            requireSame(key, stored.get(), hash);
            return breakView(repo.findBreak(breakId).orElseThrow());
        }
        tx.executeWithoutResult(s -> {
            repo.lockKey(key);
            if (repo.key(key).isPresent()) {
                return;
            }
            BreakRow b = repo.lockBreak(breakId).orElseThrow(() -> new ReconException(ReconError.NOT_FOUND, "No break " + breakId));
            if (!"RESOLVED".equals(b.status()) || "ADJUSTMENT".equals(b.resolution())) {
                throw new ReconException(ReconError.ILLEGAL_ACTION, "Only a carried-forward break or a raised claim can be reopened; "
                        + "an adjustment is booked and stays (reverse it in the ledger if it was wrong)");
            }
            repo.reopen(breakId);
            repo.saveKey(key, hash, breakId, clock.instant());
        });
        return breakView(repo.findBreak(breakId).orElseThrow());
    }

    @Override
    public ReconViews.Reconciliation manualMatch(String key, ReconViews.ManualMatch c) {
        nostro(c.account());
        List<Long> l = ReconRepository.safe(c.ledgerPostingIds());
        List<Long> s = ReconRepository.safe(c.statementEntryIds());
        if (l.size() + s.size() < 2 || c.by() == null || c.by().isBlank() || c.comment() == null || c.comment().trim().length() < 10) {
            throw new ReconException(ReconError.INVALID_REQUEST, "A manual match needs at least two items, who matched them, and a comment of 10+ characters");
        }
        String hash = sha256("manual|" + c.account() + "|" + l + "|" + s + "|" + c.comment() + "|" + c.by());
        Optional<ReconRepository.StoredKey> stored = repo.key(key);
        if (stored.isPresent()) {
            requireSame(key, stored.get(), hash);
            return reconciliation(c.account(), null);
        }
        tx.executeWithoutResult(st -> {
            repo.lockKey(key);
            if (repo.key(key).isPresent()) {
                return;
            }
            repo.lockAccount(c.account());
            Map<Long, BigDecimal> items = repo.unmatchedItems(c.account()).stream().collect(Collectors.toMap(ItemRow::postingId, ItemRow::signed));
            Map<Long, BigDecimal> entries = repo.unmatchedEntries(c.account()).stream().collect(Collectors.toMap(EntryRow::id, EntryRow::signed));
            if (!items.keySet().containsAll(l) || !entries.keySet().containsAll(s)) {
                throw new ReconException(ReconError.ILLEGAL_ACTION, "Every item must be on this account and not matched yet");
            }
            BigDecimal ls = l.stream().map(items::get).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal ss = s.stream().map(entries::get).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (ls.compareTo(ss) != 0) {
                throw new ReconException(ReconError.ILLEGAL_ACTION, "The two sides do not add up: ledger " + ls.toPlainString()
                        + ", statement " + ss.toPlainString() + " (a difference is a break to resolve, not to match away)");
            }
            long m = repo.insertMatch(c.account(), "MANUAL", c.by().trim(), c.comment().trim(), clock.instant());
            l.forEach(i -> repo.insertMatchItem(m, "LEDGER", i, items.get(i)));
            s.forEach(i -> repo.insertMatchItem(m, "STATEMENT", i, entries.get(i)));
            clearBreaksOf(repo.activeBreaks(c.account()), l, s, m);
            repo.saveKey(key, hash, Long.toString(m), clock.instant());
        });
        return reconciliation(c.account(), null);
    }

    // ------------------------------------------------------------------ views

    @Override
    public List<ReconViews.Nostro> nostros() {
        return props.nostros().stream().map(n -> {
            AccountView a = ledger.findAccountByNumber(n.account()).orElse(null);
            List<StatementRow> st = repo.statements(n.account());
            int open = (int) repo.activeBreaks(n.account()).stream().filter(b -> "OPEN".equals(b.status())).count();
            return new ReconViews.Nostro(n.account(), a == null ? n.account() : a.ownerName(), a == null ? null : a.currency(),
                    Bic.parse(n.servicer()).value(), n.servicerAccount(), st.isEmpty() ? null : st.get(st.size() - 1).date(), open,
                    a == null ? null : a.booked());
        }).toList();
    }

    @Override
    public ReconViews.Reconciliation reconciliation(String account, LocalDate date) {
        ReconProperties.Nostro n = nostro(account);
        AccountView acct = ledger.findAccountByNumber(account).orElseThrow(() -> new ReconException(ReconError.UNKNOWN_ACCOUNT,
                "No ledger account " + account));
        String ccy = acct.currency();
        List<StatementRow> statements = repo.statements(account);
        List<LocalDate> dates = statements.stream().map(StatementRow::date).distinct().sorted(java.util.Comparator.reverseOrder()).toList();
        if (statements.isEmpty()) {
            return new ReconViews.Reconciliation(account, acct.ownerName(), ccy, Bic.parse(n.servicer()).value(), n.servicerAccount(),
                    List.of(), null, 0, 0, 0, 0, 0, Money.zero(ccy), null, List.of(), List.of());
        }
        StatementRow sel = statements.stream().filter(s -> date == null || s.date().equals(date))
                .reduce((a, b) -> b).orElseThrow(() -> new ReconException(ReconError.NOT_FOUND, "No statement for " + account + " on " + date));
        LocalDate d = sel.date();
        List<ItemRow> items = repo.items(account);
        List<EntryRow> entries = repo.entries(account);
        Map<Long, ItemRow> itemById = items.stream().collect(Collectors.toMap(ItemRow::postingId, Function.identity()));
        Map<Long, EntryRow> entryById = entries.stream().collect(Collectors.toMap(EntryRow::id, Function.identity()));
        List<MatchRow> matches = repo.matches(account);
        Map<Long, List<MatchItemRow>> itemsOf = repo.matchItems(account).stream().collect(Collectors.groupingBy(MatchItemRow::matchId,
                LinkedHashMap::new, Collectors.toList()));
        List<BreakRow> breaks = repo.breaks(account);

        // The rows of the selected date: matches and breaks touching an item of that date.
        Predicate2 onDate = (side, id) -> "LEDGER".equals(side) ? itemById.containsKey(id) && itemById.get(id).date().equals(d)
                : entryById.containsKey(id) && entryById.get(id).statementId().equals(sel.id());
        List<ReconViews.Row> rows = new ArrayList<>();
        int matched = 0;
        int manual = 0;
        for (MatchRow m : matches) {
            List<MatchItemRow> its = itemsOf.getOrDefault(m.id(), List.of());
            if (its.stream().noneMatch(i -> onDate.test(i.side(), i.itemId()))) {
                continue;
            }
            rows.add(new ReconViews.Row(m.id(), m.rule(), m.matchedBy(), m.comment(), null, null, null,
                    its.stream().filter(i -> "LEDGER".equals(i.side())).map(i -> line(itemById.get(i.itemId()), ccy)).toList(),
                    its.stream().filter(i -> "STATEMENT".equals(i.side())).map(i -> line(entryById.get(i.itemId()), ccy)).toList()));
            matched += (int) its.stream().filter(i -> "STATEMENT".equals(i.side()) && onDate.test(i.side(), i.itemId())).count();
            if ("MANUAL".equals(m.rule()) || "ADJUSTMENT".equals(m.rule())) {
                manual++;
            }
        }
        List<ReconViews.BreakView> breakViews = new ArrayList<>();
        int open = 0;
        BigDecimal unmatched = BigDecimal.ZERO;
        for (BreakRow b : breaks) {
            boolean here = (b.ledgerItemId() != null && onDate.test("LEDGER", b.ledgerItemId()))
                    || (b.statementEntryId() != null && onDate.test("STATEMENT", b.statementEntryId()));
            boolean outstanding = !"CLEARED".equals(b.status());
            if (!here && !outstanding) {
                continue;
            }
            breakViews.add(breakView(b, itemById, entryById, ccy));
            if (here && !"CLEARED".equals(b.status()) && b.clearedByMatch() == null && !matchedAny(b, itemsOf)) {
                rows.add(new ReconViews.Row(null, null, null, b.comment(), b.id(), b.type(), b.status(),
                        b.ledgerItemId() == null ? List.of() : List.of(line(itemById.get(b.ledgerItemId()), ccy)),
                        b.statementEntryId() == null ? List.of() : List.of(line(entryById.get(b.statementEntryId()), ccy))));
            }
            if ("OPEN".equals(b.status())) {
                open++;
                unmatched = unmatched.add(b.difference().abs());
            }
        }

        // The proof: does the gap between the two closing balances consist of the open items, and nothing else?
        StatementRow first = statements.get(0);
        BigDecimal ledgerBefore = ledger.statement(acct.id(), first.date().minusDays(1)).closingBooked().amount();
        BigDecimal openingDiff = first.opening().subtract(ledgerBefore);
        BigDecimal ledgerClosing = ledger.statement(acct.id(), d).closingBooked().amount();
        Set<UUID> upTo = statements.stream().filter(s -> !s.date().isAfter(d)).map(StatementRow::id).collect(Collectors.toSet());
        Map<Long, Long> matchOfLedger = new HashMap<>();
        Map<Long, Long> matchOfEntry = new HashMap<>();
        itemsOf.forEach((m, its) -> its.forEach(i -> ("LEDGER".equals(i.side()) ? matchOfLedger : matchOfEntry).put(i.itemId(), m)));
        BigDecimal explained = openingDiff;
        for (EntryRow e : entries) {
            if (upTo.contains(e.statementId()) && !closedWithin(matchOfEntry.get(e.id()), itemsOf, itemById, entryById, d, upTo)) {
                explained = explained.add(e.signed());
            }
        }
        for (ItemRow i : items) {
            if (!i.date().isAfter(d) && !closedWithin(matchOfLedger.get(i.postingId()), itemsOf, itemById, entryById, d, upTo)) {
                explained = explained.subtract(i.signed());
            }
        }
        BigDecimal difference = sel.closing().subtract(ledgerClosing);
        ReconViews.Proof proof = new ReconViews.Proof(money(sel.closing(), ccy), money(ledgerClosing, ccy), money(difference, ccy),
                money(openingDiff, ccy), money(explained, ccy), money(difference.subtract(explained), ccy));
        int ledgerEntries = (int) items.stream().filter(i -> i.date().equals(d)).count();
        int stmtEntries = (int) entries.stream().filter(e -> e.statementId().equals(sel.id())).count();
        ReconViews.StatementInfo info = new ReconViews.StatementInfo(sel.id(), sel.messageId(), sel.msgId(), sel.statementRef(),
                sel.servicerBic(), sel.servicerAccount(), d, sel.receivedAt(), money(sel.opening(), ccy), money(sel.closing(), ccy), stmtEntries);
        return new ReconViews.Reconciliation(account, acct.ownerName(), ccy, sel.servicerBic(), sel.servicerAccount(), dates, info,
                ledgerEntries, stmtEntries, matched, manual, open, money(unmatched, ccy), proof, rows, breakViews);
    }

    @FunctionalInterface
    private interface Predicate2 {
        boolean test(String side, long id);
    }

    private static boolean matchedAny(BreakRow b, Map<Long, List<MatchItemRow>> itemsOf) {
        return itemsOf.values().stream().flatMap(List::stream).anyMatch(i ->
                ("LEDGER".equals(i.side()) && b.ledgerItemId() != null && i.itemId() == b.ledgerItemId())
                        || ("STATEMENT".equals(i.side()) && b.statementEntryId() != null && i.itemId() == b.statementEntryId()));
    }

    /** Is the item's match complete within the window (every item of it dated on or before d)? */
    private static boolean closedWithin(Long matchId, Map<Long, List<MatchItemRow>> itemsOf, Map<Long, ItemRow> items,
                                        Map<Long, EntryRow> entries, LocalDate d, Set<UUID> upTo) {
        if (matchId == null) {
            return false;
        }
        return itemsOf.getOrDefault(matchId, List.of()).stream().allMatch(i -> "LEDGER".equals(i.side())
                ? items.containsKey(i.itemId()) && !items.get(i.itemId()).date().isAfter(d)
                : entries.containsKey(i.itemId()) && upTo.contains(entries.get(i.itemId()).statementId()));
    }

    private ReconViews.BreakView breakView(BreakRow b) {
        AccountView acct = ledger.findAccountByNumber(b.account()).orElseThrow();
        Map<Long, ItemRow> items = repo.items(b.account()).stream().collect(Collectors.toMap(ItemRow::postingId, Function.identity()));
        Map<Long, EntryRow> entries = repo.entries(b.account()).stream().collect(Collectors.toMap(EntryRow::id, Function.identity()));
        return breakView(b, items, entries, acct.currency());
    }

    private ReconViews.BreakView breakView(BreakRow b, Map<Long, ItemRow> items, Map<Long, EntryRow> entries, String ccy) {
        ItemRow i = b.ledgerItemId() == null ? null : items.get(b.ledgerItemId());
        EntryRow e = b.statementEntryId() == null ? null : entries.get(b.statementEntryId());
        String ref = e != null ? firstNonNull(e.endToEndId(), e.servicerRef()) : i != null ? firstNonNull(i.endToEndId(), i.narrative()) : null;
        return new ReconViews.BreakView(b.id(), b.account(), b.type(), b.status(), money(b.difference(), ccy), b.openedAt(), ref,
                i == null ? null : line(i, ccy), e == null ? null : line(e, ccy), suggestion(b, i, e), actions(b.type()),
                b.resolution(), b.comment(), b.resolvedBy(), b.resolvedAt());
    }

    /** What an experienced reconciler would do first (a hint, never a decision). */
    static String suggestion(BreakRow b, ItemRow i, EntryRow e) {
        return switch (b.type()) {
            case "MISSING_IN_STATEMENT" -> "Timing difference if it was booked after the servicer's cut, or in its next day: carry it "
                    + "forward; it clears by itself when the next statement shows it. If it never comes, the servicer did not book it: investigate.";
            case "MISSING_IN_LEDGER" -> e != null && e.bankTxCode() != null && e.bankTxCode().contains("CHRG")
                    || e != null && e.info() != null && e.info().toUpperCase().contains("FEE")
                    ? "A charge the servicer took: book it on our side (adjustment against bank charges)."
                    : "The servicer booked something we did not: find the payment; if it is not ours, raise a claim.";
            case "AMOUNT_MISMATCH" -> "The same payment with another amount: often a charge deducted on the way. Book the difference "
                    + "as an adjustment, or claim it back if it should not have been taken.";
            case "DUPLICATE" -> "The servicer booked this payment twice. Raise a claim for the reversal; when it is reversed, match the "
                    + "two statement entries by hand.";
            default -> "";
        };
    }

    private static ReconViews.LedgerLine line(ItemRow i, String ccy) {
        return i == null ? null : new ReconViews.LedgerLine(i.postingId(), i.journalEntryId(), i.date(), i.bookedAt(), money(i.signed(), ccy),
                i.uetr(), i.endToEndId(), i.narrative());
    }

    private ReconViews.StatementLineView line(EntryRow e, String ccy) {
        return e == null ? null : new ReconViews.StatementLineView(e.id(), e.entryRef(), e.statementDate(), e.bookedAt(), money(e.signed(), ccy),
                e.servicerRef(), e.uetr(), e.endToEndId(), e.info(), e.bankTxCode(), e.reversal(), readTxs(e.txs()).size(), e.xml());
    }

    // ------------------------------------------------------------------ helpers

    private ReconProperties.Nostro nostro(String account) {
        return props.nostros().stream().filter(n -> n.account().equals(account)).findFirst()
                .orElseThrow(() -> new ReconException(ReconError.UNKNOWN_ACCOUNT, account + " is not one of our nostros"));
    }

    private BigDecimal itemSigned(String account, long id) {
        return repo.items(account).stream().filter(i -> i.postingId() == id).findFirst().orElseThrow().signed();
    }

    private BigDecimal entrySigned(String account, long id) {
        return repo.entries(account).stream().filter(i -> i.id() == id).findFirst().orElseThrow().signed();
    }

    private static void requireSame(String key, ReconRepository.StoredKey stored, String hash) {
        if (!stored.hash().equals(hash)) {
            throw new ReconException(ReconError.IDEMPOTENCY_CONFLICT, "Idempotency-Key " + key + " was used for a different request");
        }
    }

    private static BigDecimal signed(Money m, boolean credit) {
        return credit ? m.amount() : m.amount().negate();
    }

    private static Money money(BigDecimal v, String ccy) {
        return Money.rounded(v, com.minipay.common.money.CurrencyUnits.currency(ccy));
    }

    private String writeTxs(List<StatementTx> txs) {
        try {
            return json.writeValueAsString(txs.stream().map(t -> new TxJson(t.uetr(), t.endToEndId(), t.instructionId(),
                    t.amount().amount().toPlainString(), t.amount().currencyCode(), t.credit())).toList());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<StatementTx> readTxs(String text) {
        try {
            return json.readValue(text == null ? "[]" : text, new TypeReference<List<TxJson>>() { }).stream()
                    .map(t -> new StatementTx(t.uetr(), t.endToEndId(), t.instructionId(), Money.of(t.amount(), t.currency()), t.credit()))
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    record TxJson(String uetr, String endToEndId, String instructionId, String amount, String currency, boolean credit) {
    }

    private static String firstNonNull(String a, String b) {
        return a != null && !"NOTPROVIDED".equals(a) ? a : b;
    }

    private static String trim(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    private static boolean sameBank(String a, String b) {
        return a != null && b != null && Bic.isValid(a) && Bic.isValid(b) && Bic.parse(a).bic8().equals(Bic.parse(b).bic8());
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
