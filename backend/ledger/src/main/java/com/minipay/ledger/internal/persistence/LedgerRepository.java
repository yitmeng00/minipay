package com.minipay.ledger.internal.persistence;

import com.minipay.common.money.Money;
import com.minipay.ledger.api.AccountStatus;
import com.minipay.ledger.api.AccountType;
import com.minipay.ledger.api.AccountView;
import com.minipay.ledger.api.BalanceType;
import com.minipay.ledger.api.Direction;
import com.minipay.ledger.api.EntryType;
import com.minipay.ledger.api.IntegrityReport.BalanceMismatch;
import com.minipay.ledger.api.IntegrityReport.HoldMismatch;
import com.minipay.ledger.api.IntegrityReport.TrialBalanceLine;
import com.minipay.ledger.api.IntegrityReport.UnbalancedEntry;
import com.minipay.ledger.api.JournalEntryView;
import com.minipay.ledger.api.OpenAccountCommand;
import com.minipay.ledger.api.PostingView;
import com.minipay.ledger.api.ReservationStatus;
import com.minipay.ledger.api.ReservationView;
import com.minipay.ledger.internal.domain.AccountBalance;
import com.minipay.ledger.internal.domain.JournalEntryDraft;
import com.minipay.ledger.internal.domain.NewPosting;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * All SQL for the ledger. Plain JDBC rather than JPA, on purpose:
 * <ul>
 *   <li>The hot path needs <b>exact control of locking</b> ({@code SELECT ... ORDER BY account_id
 *       FOR UPDATE}) and of statement order. An ORM's dirty checking and flush ordering hide both.</li>
 *   <li>Postings are insert-only; there is no entity lifecycle to manage.</li>
 *   <li>Statement and integrity queries are aggregate SQL that is clearer written by hand.</li>
 * </ul>
 * See ADR-002.
 */
@Repository
@DependsOn("ledgerFlyway")
public class LedgerRepository {

    /** Holds control account per currency, e.g. {@code HOLDS-EUR}. See ADR-002 "reservation model". */
    public static final String HOLDS_PREFIX = "HOLDS-";

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ accounts

    public long insertAccount(OpenAccountCommand c) {
        long id = jdbc.sql("""
                        INSERT INTO ledger.account
                            (account_number, account_type, currency, owner_name, counterparty_bic, allow_overdraft)
                        VALUES (:number, :type, :ccy, :owner, :bic, :overdraft)
                        RETURNING id
                        """)
                .param("number", c.accountNumber())
                .param("type", c.type().name())
                .param("ccy", c.currency())
                .param("owner", c.ownerName())
                .param("bic", c.counterpartyBic())
                .param("overdraft", c.allowOverdraft())
                .query(Long.class)
                .single();
        jdbc.sql("INSERT INTO ledger.account_balance (account_id, currency) VALUES (:id, :ccy)")
                .param("id", id)
                .param("ccy", c.currency())
                .update();
        return id;
    }

    /** Creates the holds control account for a currency if it does not exist yet. */
    public void ensureHoldsControlAccount(String currency) {
        String number = HOLDS_PREFIX + currency;
        List<Long> created = jdbc.sql("""
                        INSERT INTO ledger.account (account_number, account_type, currency, owner_name, allow_overdraft)
                        VALUES (:number, 'SUSPENSE', :ccy, :owner, TRUE)
                        ON CONFLICT (account_number) DO NOTHING
                        RETURNING id
                        """)
                .param("number", number)
                .param("ccy", currency)
                .param("owner", "Holds control " + currency)
                .query(Long.class)
                .list();
        created.forEach(id -> jdbc.sql("INSERT INTO ledger.account_balance (account_id, currency) VALUES (:id, :ccy)")
                .param("id", id)
                .param("ccy", currency)
                .update());
    }

    private static final String ACCOUNT_SELECT = """
            SELECT a.id, a.account_number, a.account_type, a.currency, a.owner_name, a.counterparty_bic,
                   a.status, a.allow_overdraft, b.booked, b.reserved, b.available, b.version
              FROM ledger.account a
              JOIN ledger.account_balance b ON b.account_id = a.id
            """;

    public Optional<AccountView> findAccount(long id) {
        return jdbc.sql(ACCOUNT_SELECT + " WHERE a.id = :id").param("id", id).query(this::mapAccount).optional();
    }

    public Optional<AccountView> findAccountByNumber(String number) {
        return jdbc.sql(ACCOUNT_SELECT + " WHERE a.account_number = :n").param("n", number).query(this::mapAccount).optional();
    }

    public List<AccountView> listAccounts(AccountType type) {
        String where = type == null ? "" : " WHERE a.account_type = :type";
        var spec = jdbc.sql(ACCOUNT_SELECT + where + " ORDER BY a.account_type, a.currency, a.account_number");
        if (type != null) {
            spec = spec.param("type", type.name());
        }
        return spec.query(this::mapAccount).list();
    }

    private AccountView mapAccount(ResultSet rs, int row) throws SQLException {
        String ccy = rs.getString("currency");
        return new AccountView(
                rs.getLong("id"),
                rs.getString("account_number"),
                AccountType.valueOf(rs.getString("account_type")),
                ccy,
                rs.getString("owner_name"),
                rs.getString("counterparty_bic"),
                AccountStatus.valueOf(rs.getString("status")),
                rs.getBoolean("allow_overdraft"),
                Money.of(rs.getBigDecimal("booked"), ccy),
                Money.of(rs.getBigDecimal("reserved"), ccy),
                Money.of(rs.getBigDecimal("available"), ccy),
                rs.getLong("version"));
    }

    // ------------------------------------------------------------------ balances (hot path)

    /**
     * Locks the balance rows of the given accounts, <b>always in ascending account id order</b>.
     *
     * <p>Why the order matters: transfer A→B locks A then B; a concurrent transfer B→A would lock B
     * then A — each waits for the other forever (deadlock). If every transaction locks in the same
     * global order (by id), the second one simply waits for the first to finish.
     * PostgreSQL applies {@code FOR UPDATE} to rows as they come out of the sort, so
     * {@code ORDER BY ... FOR UPDATE} acquires the locks in that order.
     */
    public Map<Long, AccountBalance> lockBalances(Collection<Long> accountIds) {
        List<AccountBalance> rows = jdbc.sql("""
                        SELECT a.id, a.account_number, a.account_type, a.status, a.allow_overdraft,
                               b.currency, b.booked, b.reserved, b.version
                          FROM ledger.account_balance b
                          JOIN ledger.account a ON a.id = b.account_id
                         WHERE b.account_id IN (:ids)
                         ORDER BY b.account_id
                           FOR UPDATE OF b
                        """)
                .param("ids", accountIds)
                .query((rs, n) -> {
                    String ccy = rs.getString("currency");
                    return new AccountBalance(
                            rs.getLong("id"),
                            rs.getString("account_number"),
                            AccountType.valueOf(rs.getString("account_type")),
                            AccountStatus.valueOf(rs.getString("status")),
                            rs.getBoolean("allow_overdraft"),
                            Money.of(rs.getBigDecimal("booked"), ccy),
                            Money.of(rs.getBigDecimal("reserved"), ccy),
                            rs.getLong("version"));
                })
                .list();
        Map<Long, AccountBalance> byId = new LinkedHashMap<>();
        rows.forEach(r -> byId.put(r.accountId(), r));
        return byId;
    }

    public void updateBalance(AccountBalance b) {
        int updated = jdbc.sql("""
                        UPDATE ledger.account_balance
                           SET booked = :booked, reserved = :reserved, version = version + 1, updated_at = now()
                         WHERE account_id = :id AND version = :version
                        """)
                .param("booked", b.booked().amount())
                .param("reserved", b.reserved().amount())
                .param("id", b.accountId())
                .param("version", b.version())
                .update();
        if (updated != 1) {
            // Cannot happen while we hold the row lock; if it does, something bypassed the lock.
            throw new IllegalStateException("Balance row for account " + b.accountId() + " changed under lock");
        }
        b.persisted();
    }

    // ------------------------------------------------------------------ journal entries & postings

    public record StoredKey(UUID entryId, String requestHash, EntryType entryType) {
    }

    public Optional<StoredKey> findByIdempotencyKey(String key) {
        return jdbc.sql("SELECT id, request_hash, entry_type FROM ledger.journal_entry WHERE idempotency_key = :k")
                .param("k", key)
                .query((rs, n) -> new StoredKey(
                        rs.getObject("id", UUID.class),
                        rs.getString("request_hash"),
                        EntryType.valueOf(rs.getString("entry_type"))))
                .optional();
    }

    public void insertEntry(JournalEntryDraft e) {
        jdbc.sql("""
                        INSERT INTO ledger.journal_entry
                            (id, idempotency_key, request_hash, payment_id, entry_type, reverses_entry_id, value_date, narrative)
                        VALUES (:id, :key, :hash, :payment, :type, :reverses, :valueDate, :narrative)
                        """)
                .param("id", e.id())
                .param("key", e.idempotencyKey())
                .param("hash", e.requestHash())
                .param("payment", e.paymentId())
                .param("type", e.entryType().name())
                .param("reverses", e.reversesEntryId())
                .param("valueDate", e.valueDate())
                .param("narrative", e.narrative())
                .update();
        for (NewPosting p : e.postings()) {
            jdbc.sql("""
                            INSERT INTO ledger.posting (journal_entry_id, account_id, direction, amount, currency, balance_type)
                            VALUES (:entry, :account, :dir, :amount, :ccy, :bt)
                            """)
                    .param("entry", e.id())
                    .param("account", p.accountId())
                    .param("dir", p.direction().name())
                    .param("amount", p.amount().amount())
                    .param("ccy", p.amount().currencyCode())
                    .param("bt", p.balanceType().name())
                    .update();
        }
    }

    public Optional<JournalEntryView> findEntry(UUID id) {
        Optional<JournalEntryView> head = jdbc.sql("""
                        SELECT id, entry_type, payment_id, reverses_entry_id, value_date, narrative, created_at
                          FROM ledger.journal_entry WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, n) -> new JournalEntryView(
                        rs.getObject("id", UUID.class),
                        EntryType.valueOf(rs.getString("entry_type")),
                        rs.getObject("payment_id", UUID.class),
                        rs.getObject("reverses_entry_id", UUID.class),
                        rs.getObject("value_date", LocalDate.class),
                        rs.getString("narrative"),
                        instant(rs, "created_at"),
                        List.of()))
                .optional();
        return head.map(h -> new JournalEntryView(h.id(), h.entryType(), h.paymentId(), h.reversesEntryId(),
                h.valueDate(), h.narrative(), h.createdAt(), postingsOf(id)));
    }

    private List<PostingView> postingsOf(UUID entryId) {
        return jdbc.sql("""
                        SELECT p.id, p.account_id, a.account_number, p.direction, p.amount, p.currency, p.balance_type
                          FROM ledger.posting p JOIN ledger.account a ON a.id = p.account_id
                         WHERE p.journal_entry_id = :id
                         ORDER BY p.id
                        """)
                .param("id", entryId)
                .query((rs, n) -> new PostingView(
                        rs.getLong("id"),
                        rs.getLong("account_id"),
                        rs.getString("account_number"),
                        Direction.valueOf(rs.getString("direction")),
                        Money.of(rs.getBigDecimal("amount"), rs.getString("currency")),
                        BalanceType.valueOf(rs.getString("balance_type"))))
                .list();
    }

    public List<UUID> entryIdsForPayment(UUID paymentId) {
        // Entries of one transaction share created_at; the first posting id gives true insertion order.
        return jdbc.sql("""
                        SELECT e.id FROM ledger.journal_entry e
                         WHERE e.payment_id = :p
                         ORDER BY e.created_at,
                                  (SELECT min(p.id) FROM ledger.posting p WHERE p.journal_entry_id = e.id)
                        """)
                .param("p", paymentId)
                .query(UUID.class)
                .list();
    }

    public boolean isReversed(UUID entryId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM ledger.journal_entry WHERE reverses_entry_id = :id)")
                .param("id", entryId)
                .query(Boolean.class)
                .single();
    }

    // ------------------------------------------------------------------ reservations

    public void insertReservation(UUID id, long accountId, Money amount, UUID paymentId, String reason, UUID reserveEntryId) {
        jdbc.sql("""
                        INSERT INTO ledger.reservation (id, account_id, amount, currency, payment_id, reason, status, reserve_entry_id)
                        VALUES (:id, :account, :amount, :ccy, :payment, :reason, 'ACTIVE', :entry)
                        """)
                .param("id", id)
                .param("account", accountId)
                .param("amount", amount.amount())
                .param("ccy", amount.currencyCode())
                .param("payment", paymentId)
                .param("reason", reason)
                .param("entry", reserveEntryId)
                .update();
    }

    private static final String RESERVATION_SELECT = """
            SELECT id, account_id, amount, currency, payment_id, reason, status,
                   reserve_entry_id, release_entry_id, settle_entry_id, created_at, updated_at
              FROM ledger.reservation
            """;

    public Optional<ReservationView> findReservation(UUID id) {
        return jdbc.sql(RESERVATION_SELECT + " WHERE id = :id").param("id", id).query(this::mapReservation).optional();
    }

    /** Locks the reservation row so two release/settle calls on the same hold serialise. */
    public Optional<ReservationView> lockReservation(UUID id) {
        return jdbc.sql(RESERVATION_SELECT + " WHERE id = :id FOR UPDATE").param("id", id).query(this::mapReservation).optional();
    }

    public enum ReservationEntryColumn {
        RESERVE("reserve_entry_id"), RELEASE("release_entry_id"), SETTLE("settle_entry_id");

        final String column;

        ReservationEntryColumn(String column) {
            this.column = column;
        }
    }

    public Optional<ReservationView> findReservationByEntry(ReservationEntryColumn column, UUID entryId) {
        return jdbc.sql(RESERVATION_SELECT + " WHERE " + column.column + " = :id")
                .param("id", entryId)
                .query(this::mapReservation)
                .optional();
    }

    public List<ReservationView> activeReservations(long accountId) {
        return jdbc.sql(RESERVATION_SELECT + " WHERE account_id = :id AND status = 'ACTIVE' ORDER BY created_at")
                .param("id", accountId)
                .query(this::mapReservation)
                .list();
    }

    public void markReservation(UUID id, ReservationStatus status, UUID releaseEntryId, UUID settleEntryId) {
        jdbc.sql("""
                        UPDATE ledger.reservation
                           SET status = :status, release_entry_id = :release, settle_entry_id = :settle, updated_at = now()
                         WHERE id = :id AND status = 'ACTIVE'
                        """)
                .param("status", status.name())
                .param("release", releaseEntryId)
                .param("settle", settleEntryId)
                .param("id", id)
                .update();
    }

    private ReservationView mapReservation(ResultSet rs, int row) throws SQLException {
        return new ReservationView(
                rs.getObject("id", UUID.class),
                rs.getLong("account_id"),
                Money.of(rs.getBigDecimal("amount"), rs.getString("currency")),
                rs.getObject("payment_id", UUID.class),
                rs.getString("reason"),
                ReservationStatus.valueOf(rs.getString("status")),
                rs.getObject("reserve_entry_id", UUID.class),
                rs.getObject("release_entry_id", UUID.class),
                rs.getObject("settle_entry_id", UUID.class),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    // ------------------------------------------------------------------ statement

    public record StatementRow(long postingId, Instant bookedAt, UUID entryId, EntryType entryType, UUID paymentId,
                               LocalDate valueDate, String narrative, String contra, Direction direction,
                               BigDecimal amount) {
    }

    /** Booked balance (in the account's normal direction) from all BOOKED postings before {@code before}. */
    public BigDecimal bookedBalanceBefore(long accountId, Direction normalSide, Instant before) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(CASE WHEN direction = :normal THEN amount ELSE -amount END), 0)
                          FROM ledger.posting
                         WHERE account_id = :id AND balance_type = 'BOOKED' AND created_at < :before
                        """)
                .param("normal", normalSide.name())
                .param("id", accountId)
                .param("before", utc(before))
                .query(BigDecimal.class)
                .single();
    }

    public List<StatementRow> bookedPostings(long accountId, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT p.id, p.created_at, p.direction, p.amount,
                               e.id AS entry_id, e.entry_type, e.payment_id, e.value_date, e.narrative,
                               (SELECT string_agg(DISTINCT a2.account_number, ', ')
                                  FROM ledger.posting p2 JOIN ledger.account a2 ON a2.id = p2.account_id
                                 WHERE p2.journal_entry_id = p.journal_entry_id
                                   AND p2.balance_type = 'BOOKED'
                                   AND p2.direction <> p.direction) AS contra
                          FROM ledger.posting p
                          JOIN ledger.journal_entry e ON e.id = p.journal_entry_id
                         WHERE p.account_id = :id AND p.balance_type = 'BOOKED'
                           AND p.created_at >= :from AND p.created_at < :to
                         ORDER BY p.created_at, p.id
                        """)
                .param("id", accountId)
                .param("from", utc(from))
                .param("to", utc(to))
                .query((rs, n) -> new StatementRow(
                        rs.getLong("id"),
                        instant(rs, "created_at"),
                        rs.getObject("entry_id", UUID.class),
                        EntryType.valueOf(rs.getString("entry_type")),
                        rs.getObject("payment_id", UUID.class),
                        rs.getObject("value_date", LocalDate.class),
                        rs.getString("narrative"),
                        rs.getString("contra"),
                        Direction.valueOf(rs.getString("direction")),
                        rs.getBigDecimal("amount")))
                .list();
    }

    // ------------------------------------------------------------------ integrity (end of day)

    public long countEntries() {
        return jdbc.sql("SELECT count(*) FROM ledger.journal_entry").query(Long.class).single();
    }

    public long countPostings() {
        return jdbc.sql("SELECT count(*) FROM ledger.posting").query(Long.class).single();
    }

    public List<UnbalancedEntry> unbalancedEntries() {
        List<UnbalancedEntry> unbalanced = new java.util.ArrayList<>(jdbc.sql("""
                        SELECT journal_entry_id, currency, balance_type,
                               SUM(CASE direction WHEN 'D' THEN amount ELSE -amount END) AS diff
                          FROM ledger.posting
                         GROUP BY journal_entry_id, currency, balance_type
                        HAVING SUM(CASE direction WHEN 'D' THEN amount ELSE -amount END) <> 0
                        """)
                .query((rs, n) -> new UnbalancedEntry(
                        rs.getObject("journal_entry_id", UUID.class),
                        rs.getString("currency"),
                        BalanceType.valueOf(rs.getString("balance_type")),
                        rs.getBigDecimal("diff")))
                .list());
        // Entries with fewer than two postings are broken too, even if they "balance".
        unbalanced.addAll(jdbc.sql("""
                        SELECT e.id FROM ledger.journal_entry e
                          LEFT JOIN ledger.posting p ON p.journal_entry_id = e.id
                         GROUP BY e.id HAVING count(p.id) < 2
                        """)
                .query((rs, n) -> new UnbalancedEntry(rs.getObject("id", UUID.class), null, null, BigDecimal.ZERO))
                .list());
        return unbalanced;
    }

    /** Stored balances that differ from what the postings say. */
    public List<BalanceMismatch> balanceMismatches() {
        List<String> debitNormal = Arrays.stream(AccountType.values())
                .filter(t -> t.normalSide() == Direction.D).map(Enum::name).toList();
        return jdbc.sql("""
                        WITH derived AS (
                            SELECT a.id, a.account_number, b.booked, b.reserved,
                                   COALESCE(SUM(CASE WHEN p.balance_type = 'BOOKED'
                                                     THEN CASE WHEN p.direction = n.side THEN p.amount ELSE -p.amount END
                                                END), 0) AS derived_booked,
                                   COALESCE(SUM(CASE WHEN p.balance_type = 'RESERVED'
                                                     THEN CASE WHEN p.direction = n.side THEN -p.amount ELSE p.amount END
                                                END), 0) AS derived_reserved
                              FROM ledger.account a
                              JOIN ledger.account_balance b ON b.account_id = a.id
                             CROSS JOIN LATERAL (SELECT CASE WHEN a.account_type IN (:debitNormal) THEN 'D' ELSE 'C' END AS side) n
                              LEFT JOIN ledger.posting p ON p.account_id = a.id
                             GROUP BY a.id, a.account_number, b.booked, b.reserved
                        )
                        SELECT * FROM derived
                         WHERE booked <> derived_booked OR reserved <> derived_reserved
                         ORDER BY id
                        """)
                .param("debitNormal", debitNormal)
                .query((rs, n) -> new BalanceMismatch(
                        rs.getLong("id"),
                        rs.getString("account_number"),
                        rs.getBigDecimal("booked"),
                        rs.getBigDecimal("derived_booked"),
                        rs.getBigDecimal("reserved"),
                        rs.getBigDecimal("derived_reserved")))
                .list();
    }

    /**
     * Reserved balances that differ from the active holds behind them. A customer's reserved balance
     * must equal its own active holds; a holds control account's must equal all active holds in its
     * currency.
     */
    public List<HoldMismatch> holdMismatches() {
        return jdbc.sql("""
                        SELECT a.id, a.account_number, b.reserved,
                               CASE WHEN a.account_type = 'SUSPENSE' AND a.account_number = :prefix || a.currency
                                    THEN (SELECT COALESCE(SUM(r.amount), 0) FROM ledger.reservation r
                                           WHERE r.status = 'ACTIVE' AND r.currency = a.currency)
                                    ELSE (SELECT COALESCE(SUM(r.amount), 0) FROM ledger.reservation r
                                           WHERE r.status = 'ACTIVE' AND r.account_id = a.id)
                               END AS holds
                          FROM ledger.account a
                          JOIN ledger.account_balance b ON b.account_id = a.id
                         ORDER BY a.id
                        """)
                .param("prefix", HOLDS_PREFIX)
                .query((rs, n) -> new HoldMismatch(
                        rs.getLong("id"), rs.getString("account_number"), rs.getBigDecimal("reserved"), rs.getBigDecimal("holds")))
                .list()
                .stream()
                .filter(m -> m.reserved().compareTo(m.activeHolds()) != 0)
                .toList();
    }

    public List<TrialBalanceLine> trialBalance() {
        return jdbc.sql("""
                        SELECT currency, balance_type,
                               COALESCE(SUM(amount) FILTER (WHERE direction = 'D'), 0) AS debits,
                               COALESCE(SUM(amount) FILTER (WHERE direction = 'C'), 0) AS credits
                          FROM ledger.posting
                         GROUP BY currency, balance_type
                         ORDER BY currency, balance_type
                        """)
                .query((rs, n) -> {
                    BigDecimal d = rs.getBigDecimal("debits");
                    BigDecimal c = rs.getBigDecimal("credits");
                    return new TrialBalanceLine(rs.getString("currency"), BalanceType.valueOf(rs.getString("balance_type")),
                            d, c, d.compareTo(c) == 0);
                })
                .list();
    }

    // ------------------------------------------------------------------ helpers

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
