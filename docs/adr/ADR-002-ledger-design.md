# ADR-002: Ledger design

- **Status:** Accepted
- **Date:** 2026-11-24
- **Milestone:** M1

## Context

The ledger is the bank's book of record: what it owes, what it owns and what is in flight. Everything else (payments, settlement, reconciliation) eventually becomes ledger entries. It has to:

- never create or lose money;
- keep a complete, unchangeable history;
- stay correct under heavy concurrency, for example many payments debiting one corporate account or one nostro;
- support **holds**, because a payment reserves funds long before it is booked;
- be safe to retry: networks time out and clients resend requests.

## Decisions

### 1. Double entry with a normal side per account type

Every **journal entry** (one business event) has two or more **postings** (debit or credit lines). For each currency and each balance type, total debits must equal total credits.

Each account type has a *normal side*, the side on which its balance grows:

| Type | What it is | Balance-sheet class | Normal side |
|---|---|---|---|
| `CUSTOMER` | A client's account: money **we owe** the client | Liability | Credit |
| `NOSTRO` | "Ours, with you": our account at another bank | Asset | Debit |
| `VOSTRO` | "Yours, with us": another bank's account here | Liability | Credit |
| `FEE_INCOME` | Fees earned | Income | Credit |
| `SUSPENSE` | Technical control accounts | (Asset-like) | Debit |

**Balances are stored in the normal direction**, so a healthy balance is positive for every account. Example: an outgoing payment of EUR 100 settled through our nostro at Bank B.

```
D  Customer 514087234412   100.00   (liability goes down: we owe the customer less)
C  Nostro 1101-EUR-MNPB    100.00   (asset goes down: less of our money at Bank B)
```

Bank B records the mirror image on its vostro for us. That mirror is what nostro reconciliation (M8) checks.

### 2. Append-only postings; corrections are reversals

- Postings and journal entries are never updated or deleted. `BEFORE UPDATE OR DELETE` triggers reject any attempt, and journal entries also reject `TRUNCATE`.
- A mistake is corrected with a **REVERSAL** entry: an exact mirror that references the original through `reverses_entry_id`.
  - A partial unique index allows at most one reversal per entry, which also closes the race between two concurrent reversal requests.
  - The reversal keeps the original **value date**, so interest calculations come out neutral.
  - Reversals skip the funds check. Correcting a wrong booking can legitimately push an account into overdraft, and the bank must not leave a wrong booking in place to avoid that.
- `account_balance` *is* updated. It is derived data: a cache of the sum of postings, always re-derivable and checked at end of day.

### 3. Balancing is enforced twice

1. **In the domain.** `JournalEntryDraft` cannot be constructed unless every (currency, balance type) group balances and there are at least two postings.
2. **In PostgreSQL.** `DEFERRABLE INITIALLY DEFERRED` constraint triggers on `posting` and `journal_entry` re-check each touched entry **at commit**. Deferral is essential: half-way through writing an entry, it is legitimately unbalanced.

A `BEFORE INSERT` trigger also checks that each posting's currency matches its account's currency.

### 4. Reservation (hold) model

**The problem.** A payment reaches `FUNDS_RESERVED` long before it is booked. The money must stop being spendable at that point, but it has not left the account.

| Option | Verdict |
|---|---|
| A. Just a `reserved` column, updated directly | Simple, but there is no audit trail and nothing proves the reserved figure is right |
| B. Single-sided "memo" postings | Breaks rule 3: every entry must balance |
| **C. Balanced postings on a separate RESERVED balance** | **Chosen** |

How it works:

- A **RESERVE** entry posts only to the RESERVED balance type: debit the customer, credit a per-currency **holds control** account (`HOLDS-EUR`, type `SUSPENSE`, created automatically).
  - The customer's `reserved` balance rises and `available = booked − reserved` falls.
  - `booked` is unchanged. A hold is not a booking and never appears on the customer's statement.
- **RELEASE** (the payment failed or was cancelled) is the mirror image of RESERVE.
- **SETTLE from a hold** = RELEASE + SETTLE (a BOOKED debit to the customer, a credit to the nostro or vostro), written in **one transaction**.
  - Available is the same before and after settlement, because it already dropped at reservation.
  - There is never a moment where the money is neither held nor booked.
- The `reservation` table tracks each hold's state: `ACTIVE → RELEASED | SETTLED`. Its row is locked during release and settle, so a release racing a settle has exactly one winner.

Consequences:

- **The holds control account mirrors all holds.** Its `reserved` balance always equals the sum of active holds in that currency, and the integrity check verifies this. Its "available" is therefore negative. That number is meaningless for a control account, which has `allow_overdraft = true`.
- **The holds control account is a hot row.** Every reservation in a currency locks the same holds control balance. That is fine at simulator volumes. Real banks avoid it by:
  - not maintaining a running balance on control accounts;
  - sharding the control account into N sub-accounts;
  - aggregating asynchronously.
- **Partial settlement of a hold is not supported.** A different amount means release the hold and post a new entry.

### 5. Concurrency: pessimistic row locks in a global order

Every write follows the same steps in one transaction:

1. Check idempotency.
2. Lock the balance rows: `SELECT … FROM account_balance WHERE account_id IN (…) ORDER BY account_id FOR UPDATE`.
3. Apply the postings in memory and run the business checks.
4. Insert the entry and its postings, then update the balances.

- **Why pessimistic?** One corporate account or nostro can receive hundreds of debits at once. Under optimistic locking almost all of them would fail and retry. Row locks queue them instead.
  - `version` is still increased on every write.
  - The balance update includes `WHERE version = :expected`. Under the lock this always succeeds, but it is a free tripwire if code ever bypasses the lock.
- **Why order by account id?** If A→B locks A then B while B→A locks B then A, each transaction waits for the other forever: a deadlock. If every transaction locks in the same global order, one simply waits.
  - PostgreSQL locks rows as they leave the sort, so `ORDER BY … FOR UPDATE` acquires the locks in that order.
- **Tests.** `LedgerConcurrencyIT` covers:
  - 100 concurrent debits: exactly 50 of 100 succeed, and the account is never overdrawn;
  - 100 concurrent holds;
  - opposite transfers in parallel (no deadlock);
  - a release racing a settle on the same hold;
  - a random workload followed by the integrity check.

  Removing `FOR UPDATE` makes 4 of these tests fail. Locking in request order makes the transfer test fail with `deadlock detected`.

### 6. Funds and account-status checks

These run after the postings are applied to the locked balances, for every account whose *available* balance went down:

| Condition | Result | ISO code |
|---|---|---|
| Available < 0 and `allow_overdraft = false` | Refused | AM04 InsufficientFunds |
| Account `BLOCKED` (credits are still allowed) | Refused | AC06 BlockedAccount |
| Account `CLOSED` (any posting) | Refused | AC04 ClosedAccountNumber |
| Currency differs from the account | Refused | AM03 NotAllowedCurrency |
| Amount ≤ 0 | Refused | AM12 InvalidAmount |
| Unknown account | Not found | AC01 IncorrectAccountNumber |

When a hold is settled, the held account is exempt: its funds were guaranteed by the hold. The credited nostro *is* checked, because the bank cannot send money it does not have at the correspondent.

### 7. Idempotency

- `journal_entry.idempotency_key` is UNIQUE, and `request_hash` stores the SHA-256 of the *parsed* request. So `"10"` and `"10.00"`, or different JSON key order, count as the same request.
- The same key with the same hash replays the stored result. The same key with a different hash is refused (`IDEMPOTENCY_KEY_REUSED`, HTTP 422).
- **Race.** Two requests may arrive with the same new key at the same moment. The second one's INSERT waits on the first one's unique key and fails once the first commits. The service then retries once, finds the key and replays.
- A settle writes two entries. The RELEASE entry uses the derived key `"{key}/release"`, which is why client keys may not contain `/`.

### 8. Storage details

- **Postings are partitioned by month on `created_at`.** That keeps indexes small, lets old months be archived, and means a statement day reads one partition.
  - Partitions exist for 2026–2028, plus a `DEFAULT` partition as a safety net. `create_posting_partition()` is ready for a scheduled job.
  - On PostgreSQL 16 a partitioned table needs `PRIMARY KEY (id, created_at)` and a `BIGSERIAL` id; identity columns are not supported there.
- **Amounts are `NUMERIC(19,4)`.** This covers every ISO 4217 currency (at most 4 decimals). The application writes amounts at the currency's own scale.
- **Data access uses JDBC (`JdbcClient`), not JPA**, because:
  - the hot path needs exact control over the lock statement and statement order;
  - postings have no entity lifecycle;
  - statement and integrity queries are clearer as hand-written SQL.

### 9. Booking date and value date; statements

- **`created_at` is the booking time.** It is the transaction start, so all postings of an entry share it. A statement for business date D shows BOOKED postings whose booking time falls on D *in the bank's time zone*, with a running balance from the opening balance.
- **`value_date`** is when the money is worth being moved, which matters for interest and settlement. It defaults to today's business date.

### 10. End-of-day integrity check

`GET /api/ledger/integrity` runs four checks in **one REPEATABLE READ snapshot**, so concurrent traffic cannot make a correct ledger look broken half-way through:

1. Every entry balances per currency and balance type, and has at least two postings.
2. Every stored balance equals the sum of its postings.
3. Each customer's reserved balance equals its active holds, and each holds control account's reserved balance equals all active holds in its currency.
4. The trial balance shows total debits equal to total credits per currency and balance type.

## Consequences

- The database refuses unbalanced or edited history even if application code is bypassed.
- Every balance can be rebuilt from postings, and the integrity check proves it daily.
- Throughput on a single hot account is limited by row-lock serialisation. That is a deliberate trade-off for correctness, and it is documented together with the standard mitigations.
- The holds control account shows a negative "available". The UI explains it, and the integrity check uses it as a control figure.
- Not in M1: FX, interest accrual, partial settlement of holds, posting-partition maintenance job, and fee calculation (fees are posted explicitly).
