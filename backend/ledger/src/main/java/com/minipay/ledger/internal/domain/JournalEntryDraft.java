package com.minipay.ledger.internal.domain;

import com.minipay.common.money.Money;
import com.minipay.ledger.api.BalanceType;
import com.minipay.ledger.api.Direction;
import com.minipay.ledger.api.EntryType;
import com.minipay.ledger.api.LedgerError;
import com.minipay.ledger.api.LedgerException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A journal entry that has passed every domain rule and is ready to be written.
 *
 * <p>The constructor is the gatekeeper for the core double-entry invariant: <b>for every currency
 * and balance type, total debits equal total credits.</b> An unbalanced entry cannot be created, so
 * it can never reach the database. (The database checks again at commit — see V1__ledger_schema.sql.)
 *
 * <p>Additional rules by type:
 * <ul>
 *   <li>RESERVE / RELEASE move only RESERVED balances — a hold never changes booked money.</li>
 *   <li>SETTLE / FEE move only BOOKED balances.</li>
 *   <li>REVERSAL must point at the entry it reverses, and only a REVERSAL may.</li>
 * </ul>
 */
public record JournalEntryDraft(
        UUID id,
        EntryType entryType,
        String idempotencyKey,
        String requestHash,
        UUID paymentId,
        UUID reversesEntryId,
        LocalDate valueDate,
        String narrative,
        List<NewPosting> postings) {

    public JournalEntryDraft {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(entryType, "entryType");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(valueDate, "valueDate");
        postings = List.copyOf(Objects.requireNonNull(postings, "postings"));

        if (postings.size() < 2) {
            throw new LedgerException(LedgerError.UNBALANCED_ENTRY,
                    "A journal entry needs at least two postings (double entry), got " + postings.size());
        }
        if ((entryType == EntryType.REVERSAL) != (reversesEntryId != null)) {
            throw new LedgerException(LedgerError.INVALID_ENTRY,
                    "Only a REVERSAL entry references a reversed entry");
        }
        for (NewPosting p : postings) {
            BalanceType required = switch (entryType) {
                case RESERVE, RELEASE -> BalanceType.RESERVED;
                case SETTLE, FEE -> BalanceType.BOOKED;
                case REVERSAL -> null;
            };
            if (required != null && p.balanceType() != required) {
                throw new LedgerException(LedgerError.INVALID_ENTRY,
                        entryType + " entries may only post to " + required + " balances");
            }
        }
        assertBalanced(postings);
    }

    /** Debits = credits for each (currency, balance type) group. */
    static void assertBalanced(List<NewPosting> postings) {
        record Group(String currency, BalanceType balanceType) {
        }
        Map<Group, Money> net = new LinkedHashMap<>();
        for (NewPosting p : postings) {
            Money signed = p.direction() == Direction.D ? p.amount() : p.amount().negate();
            net.merge(new Group(p.amount().currencyCode(), p.balanceType()), signed, Money::plus);
        }
        List<String> problems = new ArrayList<>();
        net.forEach((g, diff) -> {
            if (!diff.isZero()) {
                problems.add(g.currency() + " " + g.balanceType() + " debits - credits = " + diff.amount().toPlainString());
            }
        });
        if (!problems.isEmpty()) {
            throw new LedgerException(LedgerError.UNBALANCED_ENTRY, "Unbalanced journal entry: " + String.join("; ", problems));
        }
    }
}
