package com.minipay.ledger.internal;

import com.minipay.common.id.Bic;
import com.minipay.common.money.CurrencyUnits;
import com.minipay.common.money.Money;
import com.minipay.ledger.api.AccountStatus;
import com.minipay.ledger.api.AccountType;
import com.minipay.ledger.api.AccountView;
import com.minipay.ledger.api.BalanceType;
import com.minipay.ledger.api.Direction;
import com.minipay.ledger.api.EntryType;
import com.minipay.ledger.api.Idempotent;
import com.minipay.ledger.api.IntegrityReport;
import com.minipay.ledger.api.JournalEntryView;
import com.minipay.ledger.api.LedgerApi;
import com.minipay.ledger.api.LedgerError;
import com.minipay.ledger.api.LedgerException;
import com.minipay.ledger.api.OpenAccountCommand;
import com.minipay.ledger.api.PostEntryCommand;
import com.minipay.ledger.api.PostingLine;
import com.minipay.ledger.api.ReleaseReservationCommand;
import com.minipay.ledger.api.ReservationStatus;
import com.minipay.ledger.api.ReservationView;
import com.minipay.ledger.api.ReserveFundsCommand;
import com.minipay.ledger.api.ReverseEntryCommand;
import com.minipay.ledger.api.SettleReservationCommand;
import com.minipay.ledger.api.SettlementLeg;
import com.minipay.ledger.api.StatementLine;
import com.minipay.ledger.api.StatementView;
import com.minipay.ledger.internal.domain.AccountBalance;
import com.minipay.ledger.internal.domain.JournalEntryDraft;
import com.minipay.ledger.internal.domain.NewPosting;
import com.minipay.ledger.internal.domain.RequestFingerprint;
import com.minipay.ledger.internal.persistence.LedgerRepository;
import com.minipay.ledger.internal.persistence.LedgerRepository.ReservationEntryColumn;
import com.minipay.ledger.internal.persistence.LedgerRepository.StatementRow;
import com.minipay.ledger.internal.persistence.LedgerRepository.StoredKey;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The ledger's write and read paths. Every money-moving method follows the same recipe:
 *
 * <ol>
 *   <li><b>Idempotency check</b> — has this Idempotency-Key been seen? Same request → return the
 *       stored result; different request → 422.</li>
 *   <li><b>Lock</b> the balance rows of every account involved, in ascending id order.</li>
 *   <li><b>Build</b> a {@link JournalEntryDraft} — which refuses to exist unless it balances.</li>
 *   <li><b>Apply</b> the postings to the locked balances in memory and check business rules
 *       (currency, account status, sufficient funds).</li>
 *   <li><b>Write</b> the entry, its postings and the new balances — all in one transaction.</li>
 * </ol>
 * Steps 2–5 run in one database transaction, so either everything is written or nothing is.
 */
@Service
public class LedgerService implements LedgerApi {

    /** Client keys: printable, no '/', ≤ 100 chars. We derive internal keys like "{key}/release". */
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,100}$");
    private static final Pattern ACCOUNT_NUMBER = Pattern.compile("^[A-Z0-9-]{1,34}$");
    private static final String IDEMPOTENCY_CONSTRAINT = "journal_entry_idempotency_key_key";
    private static final String REVERSED_ONCE_CONSTRAINT = "journal_entry_reversed_once";
    private static final String ACCOUNT_NUMBER_CONSTRAINT = "account_account_number_key";

    private final LedgerRepository repo;
    private final TransactionTemplate tx;
    private final TransactionTemplate readSnapshotTx;
    private final Clock clock;
    private final ZoneId bankZone;

    public LedgerService(LedgerRepository repo,
                         PlatformTransactionManager txManager,
                         ObjectProvider<Clock> clock,
                         @Value("${minipay.bank.time-zone:UTC}") ZoneId bankZone) {
        this.repo = repo;
        this.tx = new TransactionTemplate(txManager);
        // The integrity check reads many tables; REPEATABLE READ gives it one consistent snapshot so
        // concurrent payments cannot make a correct ledger look broken half-way through the check.
        this.readSnapshotTx = new TransactionTemplate(txManager);
        this.readSnapshotTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.readSnapshotTx.setReadOnly(true);
        this.clock = clock.getIfAvailable(Clock::systemUTC);
        this.bankZone = bankZone;
    }

    // =================================================================== accounts

    @Override
    public AccountView openAccount(OpenAccountCommand c) {
        if (!ACCOUNT_NUMBER.matcher(c.accountNumber()).matches()) {
            throw new IllegalArgumentException("Account number must be 1-34 chars of A-Z, 0-9 or '-': " + c.accountNumber());
        }
        CurrencyUnits.currency(c.currency());
        if (c.counterpartyBic() != null) {
            Bic.parse(c.counterpartyBic());
        }
        if ((c.type() == AccountType.NOSTRO || c.type() == AccountType.VOSTRO) && c.counterpartyBic() == null) {
            throw new IllegalArgumentException(c.type() + " account needs the counterparty bank's BIC");
        }
        try {
            return tx.execute(s -> {
                long id = repo.insertAccount(c);
                // Every currency we hold accounts in needs a holds control account (ADR-002).
                repo.ensureHoldsControlAccount(c.currency());
                return repo.findAccount(id).orElseThrow();
            });
        } catch (DuplicateKeyException e) {
            if (violated(e, ACCOUNT_NUMBER_CONSTRAINT)) {
                throw new LedgerException(LedgerError.DUPLICATE_ACCOUNT, "Account " + c.accountNumber() + " already exists");
            }
            throw e;
        }
    }

    @Override
    public List<AccountView> listAccounts(AccountType type) {
        return repo.listAccounts(type);
    }

    @Override
    public AccountView getAccount(long accountId) {
        return repo.findAccount(accountId).orElseThrow(() -> accountNotFound(accountId));
    }

    @Override
    public Optional<AccountView> findAccountByNumber(String accountNumber) {
        return repo.findAccountByNumber(accountNumber);
    }

    // =================================================================== reservations

    /**
     * Places a hold: RESERVE entry = debit the customer's RESERVED balance, credit the holds control
     * account's RESERVED balance. Booked money does not move; available drops by the amount.
     */
    @Override
    public Idempotent<ReservationView> reserve(ReserveFundsCommand c) {
        requirePositive(c.amount());
        String hash = RequestFingerprint.of("reserve", c.accountId(), c.amount(), c.paymentId(), c.reason());
        return idempotent(c.idempotencyKey(), hash,
                entryId -> repo.findReservationByEntry(ReservationEntryColumn.RESERVE, entryId).orElseThrow(),
                () -> {
                    AccountView account = getAccount(c.accountId());
                    requireCurrency(account, c.amount());
                    long holds = holdsControlId(account.currency());
                    Map<Long, AccountBalance> locked = lock(List.of(account.id(), holds));

                    JournalEntryDraft entry = new JournalEntryDraft(UUID.randomUUID(), EntryType.RESERVE,
                            c.idempotencyKey(), hash, c.paymentId(), null, businessDate(),
                            c.reason(),
                            List.of(new NewPosting(account.id(), Direction.D, c.amount(), BalanceType.RESERVED),
                                    new NewPosting(holds, Direction.C, c.amount(), BalanceType.RESERVED)));
                    apply(entry, locked, FundsCheck.ENFORCE, Set.of());

                    UUID reservationId = UUID.randomUUID();
                    repo.insertReservation(reservationId, account.id(), c.amount(), c.paymentId(), c.reason(), entry.id());
                    return repo.findReservation(reservationId).orElseThrow();
                });
    }

    /** Removes a hold without moving money (payment rejected/cancelled): the mirror of the RESERVE entry. */
    @Override
    public Idempotent<ReservationView> release(ReleaseReservationCommand c) {
        String hash = RequestFingerprint.of("release", c.reservationId(), c.reason());
        return idempotent(c.idempotencyKey(), hash,
                entryId -> repo.findReservationByEntry(ReservationEntryColumn.RELEASE, entryId).orElseThrow(),
                () -> {
                    ReservationView r = lockActiveReservation(c.reservationId());
                    long holds = holdsControlId(r.amount().currencyCode());
                    Map<Long, AccountBalance> locked = lock(List.of(r.accountId(), holds));

                    JournalEntryDraft entry = releaseEntry(r, holds, c.idempotencyKey(), hash,
                            c.reason() != null ? c.reason() : "Hold released");
                    apply(entry, locked, FundsCheck.SKIP, Set.of());
                    repo.markReservation(r.id(), ReservationStatus.RELEASED, entry.id(), null);
                    return repo.findReservation(r.id()).orElseThrow();
                });
    }

    /**
     * Books a held payment: RELEASE the hold, then one entry per {@link SettlementLeg} (debit the held
     * account's BOOKED balance, credit the leg's account) — all atomically. The held account's available
     * balance is the same before and after: it already dropped when the funds were reserved.
     *
     * <p>Idempotency keys: the first leg uses the client's key (that entry is the reservation's
     * {@code settle_entry_id}); the release uses {@code key/release}, further legs {@code key/2}, {@code key/3}.
     */
    @Override
    public Idempotent<ReservationView> settle(SettleReservationCommand c) {
        String hash = RequestFingerprint.of("settle", c.reservationId(), c.valueDate(),
                c.legs().stream().map(l -> l.creditAccountId() + ":" + l.amount() + ":" + l.entryType() + ":" + l.narrative()).toList());
        return idempotent(c.idempotencyKey(), hash,
                entryId -> repo.findReservationByEntry(ReservationEntryColumn.SETTLE, entryId).orElseThrow(),
                () -> {
                    ReservationView r = lockActiveReservation(c.reservationId());
                    List<Money> amounts = legAmounts(r, c.legs());
                    List<Long> accounts = new ArrayList<>(List.of(r.accountId(), holdsControlId(r.amount().currencyCode())));
                    for (var leg : c.legs()) {
                        AccountView credit = getAccount(leg.creditAccountId());
                        if (credit.id() == r.accountId()) {
                            throw new LedgerException(LedgerError.INVALID_ENTRY, "Cannot settle a hold into the same account");
                        }
                        requireCurrency(credit, r.amount());
                        accounts.add(credit.id());
                    }
                    Map<Long, AccountBalance> locked = lock(accounts);
                    LocalDate valueDate = c.valueDate() != null ? c.valueDate() : businessDate();

                    JournalEntryDraft release = releaseEntry(r, accounts.get(1), c.idempotencyKey() + "/release", hash,
                            "Hold released for settlement");
                    apply(release, locked, FundsCheck.SKIP, Set.of());

                    UUID firstEntry = null;
                    for (int i = 0; i < c.legs().size(); i++) {
                        var leg = c.legs().get(i);
                        Money amount = amounts.get(i);
                        JournalEntryDraft entry = new JournalEntryDraft(UUID.randomUUID(), leg.entryType(),
                                i == 0 ? c.idempotencyKey() : c.idempotencyKey() + "/" + (i + 1), hash,
                                r.paymentId(), null, valueDate,
                                leg.narrative() != null ? leg.narrative() : r.reason(),
                                List.of(new NewPosting(r.accountId(), Direction.D, amount, BalanceType.BOOKED),
                                        new NewPosting(leg.creditAccountId(), Direction.C, amount, BalanceType.BOOKED)));
                        // The held account's funds were guaranteed by the hold; check everyone else
                        // (e.g. crediting a nostro reduces it — we cannot send money we do not have there).
                        apply(entry, locked, FundsCheck.ENFORCE, Set.of(r.accountId()));
                        if (firstEntry == null) {
                            firstEntry = entry.id();
                        }
                    }
                    repo.markReservation(r.id(), ReservationStatus.SETTLED, release.id(), firstEntry);
                    return repo.findReservation(r.id()).orElseThrow();
                });
    }

    /** Resolves each leg's amount; the legs must add up to exactly the held amount. */
    private static List<Money> legAmounts(ReservationView r, List<SettlementLeg> legs) {
        Money explicit = Money.zero(r.amount().currency());
        for (var leg : legs) {
            if (leg.amount() != null) {
                requirePositive(leg.amount());
                if (!leg.amount().currency().equals(r.amount().currency())) {
                    throw new LedgerException(LedgerError.CURRENCY_NOT_ALLOWED,
                            "Leg in " + leg.amount().currencyCode() + " for a hold in " + r.amount().currencyCode());
                }
                explicit = explicit.plus(leg.amount());
            }
        }
        Money remainder = r.amount().minus(explicit);
        boolean hasRemainderLeg = legs.stream().anyMatch(l -> l.amount() == null);
        if (hasRemainderLeg ? !remainder.isPositive() : !remainder.isZero()) {
            throw new LedgerException(LedgerError.UNBALANCED_ENTRY, "Settlement legs (" + explicit
                    + ") do not add up to the held amount " + r.amount());
        }
        return legs.stream().map(l -> l.amount() != null ? l.amount() : remainder).toList();
    }

    @Override
    public List<JournalEntryView> entriesForPayment(UUID paymentId) {
        return repo.entryIdsForPayment(paymentId).stream().map(id -> repo.findEntry(id).orElseThrow()).toList();
    }

    @Override
    public ReservationView getReservation(UUID reservationId) {
        return repo.findReservation(reservationId).orElseThrow(() -> new LedgerException(
                LedgerError.RESERVATION_NOT_FOUND, "Reservation " + reservationId + " not found"));
    }

    // =================================================================== direct postings & reversals

    @Override
    public Idempotent<JournalEntryView> post(PostEntryCommand c) {
        if (c.entryType() != EntryType.SETTLE && c.entryType() != EntryType.FEE) {
            throw new LedgerException(LedgerError.INVALID_ENTRY,
                    "Only SETTLE or FEE entries can be posted directly; use reservations or reversal for "
                            + c.entryType());
        }
        c.lines().forEach(l -> requirePositive(l.amount()));
        String hash = RequestFingerprint.of("post", c.entryType(), c.paymentId(), c.valueDate(), c.narrative(),
                c.lines().stream().map(l -> l.accountId() + ":" + l.direction() + ":" + l.amount()).toList());
        return idempotent(c.idempotencyKey(), hash,
                entryId -> repo.findEntry(entryId).orElseThrow(),
                () -> {
                    List<NewPosting> postings = new ArrayList<>();
                    for (PostingLine l : c.lines()) {
                        postings.add(new NewPosting(l.accountId(), l.direction(), l.amount(), BalanceType.BOOKED));
                    }
                    JournalEntryDraft entry = new JournalEntryDraft(UUID.randomUUID(), c.entryType(),
                            c.idempotencyKey(), hash, c.paymentId(), null,
                            c.valueDate() != null ? c.valueDate() : businessDate(), c.narrative(), postings);
                    Map<Long, AccountBalance> locked = lock(postings.stream().map(NewPosting::accountId).toList());
                    apply(entry, locked, FundsCheck.ENFORCE, Set.of());
                    return repo.findEntry(entry.id()).orElseThrow();
                });
    }

    /**
     * Reverses a SETTLE or FEE entry by posting its exact mirror image. The original entry and its
     * postings remain — the audit trail shows both the mistake and its correction.
     *
     * <p>A reversal keeps the original value date (so interest is neutral) and is not subject to the
     * funds check: correcting an error may legitimately push an account into overdraft — the bank then
     * deals with it as a credit matter, it does not leave the wrong booking in place.
     */
    @Override
    public Idempotent<JournalEntryView> reverse(ReverseEntryCommand c) {
        String hash = RequestFingerprint.of("reverse", c.entryId(), c.narrative());
        try {
            return idempotent(c.idempotencyKey(), hash,
                    entryId -> repo.findEntry(entryId).orElseThrow(),
                    () -> {
                        JournalEntryView original = getEntry(c.entryId());
                        if (original.entryType() != EntryType.SETTLE && original.entryType() != EntryType.FEE) {
                            throw new LedgerException(LedgerError.ENTRY_NOT_REVERSIBLE,
                                    original.entryType() + " entries cannot be reversed; holds are released instead");
                        }
                        if (repo.isReversed(original.id())) {
                            throw new LedgerException(LedgerError.ENTRY_ALREADY_REVERSED,
                                    "Entry " + original.id() + " has already been reversed");
                        }
                        List<NewPosting> mirrored = original.postings().stream()
                                .map(p -> new NewPosting(p.accountId(), p.direction(), p.amount(), p.balanceType()).mirrored())
                                .toList();
                        JournalEntryDraft entry = new JournalEntryDraft(UUID.randomUUID(), EntryType.REVERSAL,
                                c.idempotencyKey(), hash, original.paymentId(), original.id(), original.valueDate(),
                                c.narrative() != null ? c.narrative() : "Reversal of " + original.id(), mirrored);
                        Map<Long, AccountBalance> locked = lock(mirrored.stream().map(NewPosting::accountId).toList());
                        apply(entry, locked, FundsCheck.SKIP, Set.of());
                        return repo.findEntry(entry.id()).orElseThrow();
                    });
        } catch (DataIntegrityViolationException e) {
            // Two concurrent reversals of the same entry: the partial unique index lets only one win.
            if (violated(e, REVERSED_ONCE_CONSTRAINT)) {
                throw new LedgerException(LedgerError.ENTRY_ALREADY_REVERSED,
                        "Entry " + c.entryId() + " has already been reversed");
            }
            throw e;
        }
    }

    @Override
    public JournalEntryView getEntry(UUID entryId) {
        return repo.findEntry(entryId).orElseThrow(() -> new LedgerException(
                LedgerError.ENTRY_NOT_FOUND, "Journal entry " + entryId + " not found"));
    }

    // =================================================================== statement & integrity

    @Override
    public StatementView statement(long accountId, LocalDate date) {
        return readSnapshotTx.execute(s -> {
            AccountView account = getAccount(accountId);
            String ccy = account.currency();
            Direction normal = account.type().normalSide();
            LocalDate day = date != null ? date : businessDate();
            Instant from = day.atStartOfDay(bankZone).toInstant();
            Instant to = day.plusDays(1).atStartOfDay(bankZone).toInstant();

            Money opening = Money.of(repo.bookedBalanceBefore(accountId, normal, from), ccy);
            Money running = opening;
            Money debits = Money.zero(ccy);
            Money credits = Money.zero(ccy);
            int debitCount = 0;
            int creditCount = 0;
            List<StatementLine> lines = new ArrayList<>();
            for (StatementRow row : repo.bookedPostings(accountId, from, to)) {
                Money amount = Money.of(row.amount(), ccy);
                running = running.plus(AccountBalance.bookedDelta(account.type(), row.direction(), amount));
                boolean debit = row.direction() == Direction.D;
                if (debit) {
                    debits = debits.plus(amount);
                    debitCount++;
                } else {
                    credits = credits.plus(amount);
                    creditCount++;
                }
                lines.add(new StatementLine(row.postingId(), row.bookedAt(), row.entryId(), row.entryType(),
                        row.paymentId(), row.valueDate(), row.narrative(), row.contra(),
                        debit ? amount : null, debit ? null : amount, running));
            }
            return new StatementView(account, day, bankZone.getId(), opening, debits, debitCount, credits, creditCount,
                    running, lines, repo.activeReservations(accountId));
        });
    }

    @Override
    public IntegrityReport checkIntegrity() {
        return readSnapshotTx.execute(s -> {
            var unbalanced = repo.unbalancedEntries();
            var mismatches = repo.balanceMismatches();
            var holdMismatches = repo.holdMismatches();
            var trialBalance = repo.trialBalance();
            boolean ok = unbalanced.isEmpty() && mismatches.isEmpty() && holdMismatches.isEmpty()
                    && trialBalance.stream().allMatch(IntegrityReport.TrialBalanceLine::balanced);
            return new IntegrityReport(clock.instant(), ok, repo.countEntries(), repo.countPostings(),
                    unbalanced, mismatches, holdMismatches, trialBalance);
        });
    }

    // =================================================================== internals

    private enum FundsCheck { ENFORCE, SKIP }

    /**
     * Applies an entry's postings to already-locked balances, enforces the business rules, and writes
     * entry, postings and balances. Must run inside the caller's transaction.
     */
    private void apply(JournalEntryDraft entry, Map<Long, AccountBalance> locked, FundsCheck funds,
                       Set<Long> fundsExempt) {
        Map<Long, Money> availableBefore = new HashMap<>();
        for (NewPosting p : entry.postings()) {
            AccountBalance b = locked.get(p.accountId());
            if (b == null) {
                throw accountNotFound(p.accountId());
            }
            if (!b.currency().equals(p.amount().currencyCode())) {
                throw new LedgerException(LedgerError.CURRENCY_NOT_ALLOWED, "Account " + b.accountNumber()
                        + " is in " + b.currency() + ", posting is in " + p.amount().currencyCode());
            }
            if (b.status() == AccountStatus.CLOSED) {
                throw new LedgerException(LedgerError.ACCOUNT_CLOSED, "Account " + b.accountNumber() + " is closed");
            }
            availableBefore.putIfAbsent(b.accountId(), b.available());
            b.apply(p);
        }
        for (Map.Entry<Long, Money> e : availableBefore.entrySet()) {
            AccountBalance b = locked.get(e.getKey());
            boolean reducesAvailable = b.available().isLessThan(e.getValue());
            if (!reducesAvailable) {
                continue;
            }
            // Blocked accounts can receive money but not pay out. Releases and reversals are corrections, not payments.
            if (b.status() == AccountStatus.BLOCKED
                    && entry.entryType() != EntryType.RELEASE && entry.entryType() != EntryType.REVERSAL) {
                throw new LedgerException(LedgerError.ACCOUNT_BLOCKED, "Account " + b.accountNumber() + " is blocked");
            }
            if (funds == FundsCheck.ENFORCE && !b.allowOverdraft() && !fundsExempt.contains(b.accountId())
                    && b.available().isNegative()) {
                throw new LedgerException(LedgerError.INSUFFICIENT_FUNDS, "Insufficient funds on " + b.accountNumber()
                        + ": available " + e.getValue() + ", requested " + e.getValue().minus(b.available()));
            }
        }
        repo.insertEntry(entry);
        for (Long id : availableBefore.keySet()) {
            repo.updateBalance(locked.get(id));
        }
    }

    /**
     * The idempotency wrapper. Runs {@code work} in a transaction unless the key was used before.
     *
     * <p>Race: two requests with the same new key arrive together. Both see "not used", both do the work;
     * the second one's INSERT of the journal entry blocks on the first's unique key and fails once the
     * first commits. We then retry once — now the key exists — and return the stored result.
     */
    private <T> Idempotent<T> idempotent(String key, String hash, Function<UUID, T> replay, Supplier<T> work) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Idempotency-Key must be 1-100 chars of A-Z a-z 0-9 . _ : -");
        }
        for (int attempt = 0; ; attempt++) {
            try {
                return tx.execute(s -> {
                    Optional<StoredKey> existing = repo.findByIdempotencyKey(key);
                    if (existing.isPresent()) {
                        if (!existing.get().requestHash().equals(hash)) {
                            throw new LedgerException(LedgerError.IDEMPOTENCY_KEY_REUSED,
                                    "Idempotency-Key '" + key + "' was already used for a different request");
                        }
                        return Idempotent.replayed(replay.apply(existing.get().entryId()));
                    }
                    return Idempotent.created(work.get());
                });
            } catch (DuplicateKeyException e) {
                if (attempt > 0 || !violated(e, IDEMPOTENCY_CONSTRAINT)) {
                    throw e;
                }
            }
        }
    }

    private Map<Long, AccountBalance> lock(Collection<Long> accountIds) {
        return repo.lockBalances(new TreeSet<>(accountIds));
    }

    private ReservationView lockActiveReservation(UUID id) {
        ReservationView r = repo.lockReservation(id).orElseThrow(() -> new LedgerException(
                LedgerError.RESERVATION_NOT_FOUND, "Reservation " + id + " not found"));
        if (r.status() != ReservationStatus.ACTIVE) {
            throw new LedgerException(LedgerError.RESERVATION_NOT_ACTIVE,
                    "Reservation " + id + " is already " + r.status());
        }
        return r;
    }

    private JournalEntryDraft releaseEntry(ReservationView r, long holds, String key, String hash, String narrative) {
        return new JournalEntryDraft(UUID.randomUUID(), EntryType.RELEASE, key, hash, r.paymentId(), null,
                businessDate(), narrative,
                List.of(new NewPosting(r.accountId(), Direction.C, r.amount(), BalanceType.RESERVED),
                        new NewPosting(holds, Direction.D, r.amount(), BalanceType.RESERVED)));
    }

    private long holdsControlId(String currency) {
        return repo.findAccountByNumber(LedgerRepository.HOLDS_PREFIX + currency)
                .map(AccountView::id)
                .orElseThrow(() -> new IllegalStateException("No holds control account for " + currency));
    }

    /** Today's date where the bank is — the default value date. */
    private LocalDate businessDate() {
        return LocalDate.now(clock.withZone(bankZone));
    }

    private static void requirePositive(Money amount) {
        if (!amount.isPositive()) {
            throw new LedgerException(LedgerError.INVALID_AMOUNT, "Amount must be positive, got " + amount);
        }
    }

    private static void requireCurrency(AccountView account, Money amount) {
        if (!account.currency().equals(amount.currencyCode())) {
            throw new LedgerException(LedgerError.CURRENCY_NOT_ALLOWED, "Account " + account.accountNumber()
                    + " is in " + account.currency() + ", amount is in " + amount.currencyCode());
        }
    }

    private static LedgerException accountNotFound(long id) {
        return new LedgerException(LedgerError.ACCOUNT_NOT_FOUND, "Account " + id + " not found");
    }

    private static boolean violated(Exception e, String constraint) {
        Throwable t = e;
        while (t != null) {
            if (t.getMessage() != null && t.getMessage().contains(constraint)) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
}
