# ADR-003: Payment hub — lifecycle, idempotency, outbox

- **Status:** Accepted
- **Date:** 2026-11-24
- **Milestone:** M2
- **Amended by:** ADR-004 (M3): reference-data validation, routing rules, execution and value dates;
  ADR-005 (M4): the pacs.008 is issued when sending, and network outcomes arrive as pacs.002 / pacs.004;
  ADR-006 (M5): one state machine table per direction, incoming payments, outcomes after SENT (RJCT, early returns);
  ADR-007 (M6): real screening; HELD_COMPLIANCE released or rejected (RR04) by compliance decisions, both directions

## Context

The payment hub owns a customer credit transfer from the moment a channel hands it over until its
final outcome. It must:

- move each payment through an explicit lifecycle, and never skip a step that guards money (funds reservation);
- keep a complete audit trail of every status change;
- never send a payment twice, even when clients retry or the process crashes mid-way;
- tell the rest of the bank (and later the network) what happened, without losing or inventing events;
- do this while its ledger calls are not in the same transaction as its own state. That is how a hub talks to a core-banking ledger in a real bank, and it keeps the module independent.

## Decisions

### 1. Explicit state machine — *per direction since M5, ADR-006 §5*

```
RECEIVED → VALIDATED → SCREENED → FUNDS_RESERVED → SENT → ACKED → SETTLED → RETURNED
                           └→ HELD_COMPLIANCE ─┘ (→ FUNDS_RESERVED | REJECTED)
REJECTED is reachable from every state before SENT. REJECTED and RETURNED are final.
```

`PaymentStateMachine` is the single transition table, and every change goes through it. Illegal
transitions throw (`ILLEGAL_TRANSITION`, HTTP 409). The unit test checks all 100 (from, to) pairs.

Two rules come straight from how payments work:

- **No REJECTED after SENT.** Once the pacs.008 has left the bank, we can no longer refuse the
  payment. It can only come back as a **return** (pacs.004), and only after it settled. A rejection
  by the *next* bank before settlement (pacs.002 RJCT) arrives with the network simulator (M5), and
  will be modelled then.
- **HELD_COMPLIANCE is a waiting room off SCREENED.** Only a compliance decision moves the payment on
  (M6, ADR-007 §7): release to FUNDS_RESERVED, or refuse to REJECTED (RR04).

### 2. Every status change writes history and an outbox event in one transaction

`PaymentTransitions.transition(id, expectedStatus, change)` performs these steps in one transaction:

1. re-load the payment;
2. check that its status is still `expectedStatus`;
3. apply the change through the state machine;
4. insert a `payment_state_history` row;
5. insert an `outbox_event` row;
6. flush (the `@Version` check).

Either all of it happens or none of it does.

- `payment_state_history` is append-only (trigger). Its `occurred_at` uses `clock_timestamp()`,
  the real time of the change rather than the transaction start.
- `payment` uses JPA `@Version` optimistic locking, which is the opposite of the ledger's pessimistic
  locks (ADR-002 §5). Two writers on the same payment are rare. When it happens, the loser simply
  loses the race; the expected-status check usually stops it before the version check does.

### 3. Each pipeline step: side effect first, status second, idempotent everywhere

Each step does its work (validation, screening, a ledger call) **outside** the payment
transaction. It then records the outcome with a transition. The ledger call and the status change
are therefore *not* atomic. That is safe because every ledger call carries a key derived from the
payment and the step:

```
pay:{paymentId}:reserve   pay:{paymentId}:settle   pay:{paymentId}:release   pay:{paymentId}:return
```

| Crash point | What the sweeper finds | Outcome on re-run |
|---|---|---|
| Before the ledger call | Old status | The step runs normally |
| After the ledger commit, before the status change | Old status | Ledger **replays** the stored result → status recorded |
| After the status change | New status | The next step runs |

The `RecoverySweeper` periodically re-runs payments that have been stuck before SENT for longer
than `minipay.payments.recovery-stale-after`, and warehoused payments whose date has arrived.

`CrashSafetyIT` covers:

- a crash in the middle of the pipeline: the payment is stored, and the sweeper finishes it;
- **ten workers racing on the same payment at the ledger booking step**: the ledger shows exactly RESERVE, RELEASE, SETTLE and FEE, and the history shows exactly one row per transition.

The racing test found a real gap in the ledger: two requests with the same key could both see it
unused. The ledger now serialises same-key requests with a transaction-scoped advisory lock
(ADR-002 §7). Mutation checks confirm the test fails if either the derived keys or the advisory lock
is removed.

Why not one transaction across hub and ledger? It would work today, because both modules are in the
same database. But it would hide the problem the moment the ledger became a separate system, and
`LedgerException` inside a joined transaction marks the whole transaction rollback-only. Idempotent
steps are the general solution: a saga without compensation logic, because every step can safely be
repeated.

### 4. Idempotency at the API

- `payment.idempotency_key` stores the key, the SHA-256 of the **parsed** request, the operation and
  the payment it produced. The request is normalised first: IBAN spaces are removed, BICs are
  upper-cased, and amounts are compared as `Money`.
- Same key + same request → **200**, `Idempotent-Replayed: true` and the payment's current state.
- Same key + different request → **422**.
- Two identical requests at the same moment → the primary key on the idempotency row lets exactly
  one of them win; the other replays.
- The ops console creates one key per filled-in form and reuses it on retry. A double click therefore cannot send two payments.

### 5. Malformed versus unacceptable

- **Malformed** requests are refused with **400**, and no payment is created. That covers a missing
  field, text longer than ISO limits (Max35Text, Max140Text) and an amount with too many decimals.
  This is the equivalent of a schema-invalid file.
- **Well-formed but unacceptable** payments are **created and then REJECTED** with an ISO 20022
  `ExternalStatusReason1Code`. The refusal stays on record and can be reported back (pain.002 / pacs.002).

| Check (M2) | Code |
|---|---|
| Debtor account unknown / closed / blocked | AC01 / AC04 / AC06 |
| Debtor account is not a customer account | AG01 |
| Currency differs from the debtor account | AM03 |
| Creditor agent BIC malformed | RC01 |
| Creditor agent is this bank (book transfer) | AG03 |
| Creditor account malformed | AC03 |
| Requested date before today's business date | DT01 |
| Insufficient funds for amount + fee | AM04 |
| Amount does not cover our fee under CRED | AM12 |
| No route in this currency | CNOR |

**From M3 (ADR-004 §3–§6)** validation also uses reference data, each check as of the payment's
execution date:

| Check (M3) | Code |
|---|---|
| Currency has no settlement scheme (we do not settle it) | AM03 |
| Creditor agent not in the BIC directory on the execution date | RC01 |
| IBAN fails the IBAN registry's checks, belongs to another bank, or is missing for an IBAN country | AC03 |
| No usable routing rule on the execution date | CNOR (before any hold) |

### 6. Charges (who pays our fee)

`minipay.payments.outward-fees.<CCY>` defines a flat fee per bank and currency. That is a deliberate
simplification: real tariffs depend on the customer, channel and amount.

| Charge bearer | Hold on debtor | Interbank settlement amount | Our fee |
|---|---|---|---|
| DEBT (OUR) | amount + fee | amount | charged on top |
| SHAR (SHA) | amount + fee | amount | charged on top; later banks deduct theirs |
| CRED (BEN) | amount | amount − fee | deducted from what is sent |

At SENT, the one hold is settled into **two ledger entries** in one transaction:

- SETTLE: debtor → nostro/vostro, for the interbank amount;
- FEE: debtor → fee income, for our fee.

This needed the ledger's new multi-leg settle (ADR-002 §4). The payment stores `amount` (pacs.008
`InstdAmt`), `interbank_settlement_amount` (`IntrBkSttlmAmt`) and `charges_amount` separately,
because they genuinely differ.

Not yet modelled: under DEBT, the debtor should also prepay the intermediaries' charges. That needs
the route's tariffs, which come with the network simulator.

### 7. Interim routing (until M3) — *replaced by routing rules, ADR-004 §6*

1. The creditor agent holds a **vostro** with us in this currency → credit it (we pay them on our books).
2. We hold a **nostro** at the creditor agent → credit it, and send directly.
3. Otherwise our single nostro in that currency is our correspondent → credit it, and send serially via that bank.
4. Otherwise → REJECTED **CNOR**, and the hold is released.

The chosen next bank is stored as `instructed_agent_bic` (pacs.008 `InstdAgt`). Since M3 the
choice comes from effective-dated routing rules evaluated on the value date, and the rule id is
stored as `routing_rule_id`.

### 8. Warehousing

A future `requested_exec_date` puts the payment on hold at FUNDS_RESERVED: the funds are reserved
but it is not sent. The sweeper sends it on that business date (bank time zone).

Since M3 (ADR-004 §5) the payment waits for its **execution date**: the requested date rolled to the
currency's next business day. When it is sent, its **value date** is the first business day whose
cut-off, in the scheme's own time zone, has not passed. The sweeper picks up only reserved payments
that are due, so payments waiting for a later date no longer take a place in every sweep.

### 9. Transactional outbox + relay → Kafka

- **Writer:** `OutboxWriter.append` uses `Propagation.MANDATORY`, so calling it outside a business
  transaction fails fast. A rolled-back business transaction leaves no event.
- **Relay** (the only Kafka-aware class; ArchUnit enforces this), one batch at a time:
  1. lock the oldest unpublished rows with `ORDER BY seq … FOR UPDATE SKIP LOCKED`;
  2. send each with an idempotent producer (`acks=all`, `enable.idempotence=true`);
  3. **wait for the broker ack**;
  4. mark the rows `published_at` and commit.

  On the first failure the batch stops, so later events of the same payment never overtake an
  earlier one. The failure and the attempt count are recorded on the row.
- **Delivery is at least once.** A crash between the broker ack and the commit re-sends the batch. Consumers
  **must de-duplicate by the `event-id` header**. `CrashSafetyIT` simulates exactly this crash, and
  also a Kafka outage: payments keep flowing, events queue in the outbox, and they are delivered in
  order when the broker returns.
- **Topic and key:** the topic is `minipay.<bank BIC>.payment-events`, keyed by UETR so all of one
  payment's events land on one partition, in order. Topics are created by the application (Compose
  disables auto-creation, so a typo can't create a topic).
- **Event:** `payment.status-changed` carries `from`, `to`, the reason code and a full payment
  snapshot, so consumers never need to call back.

### 10. Persistence style

The hub uses **Spring Data JPA**, the project default; Hibernate *validates* the mapping against
Flyway's schema. The relay's `FOR UPDATE SKIP LOCKED` query is plain SQL. The ledger stays on JDBC
for its explicit lock ordering (ADR-002 §8). One project with two styles is intentional: each one
matches its module's concurrency profile.

### 11. Network outcomes before the network exists — *messages since M4, ADR-005 §5*

`POST /api/payments/{uetr}/network-events` (ACCEPTED, SETTLED, RETURNED) stands in for pacs.002,
settlement confirmation and pacs.004 until the network simulator (M5). The actor in the history is
`sim/network`.

A return books the returned amount (which may be less than sent, because intermediaries deduct
charges) from the settlement account back to the debtor. Our own fee is not refunded.

## Consequences

- Payments survive crashes at any point, and nothing is booked or sent twice. This is proven by tests that fail when the safety mechanisms are removed.
- Consumers have to be idempotent: the price of at-least-once delivery, and the industry norm.
- The pipeline currently runs synchronously in the request, with a sweeper as backstop. Moving it
  onto a queue later changes nothing about correctness, because the steps are already idempotent.
- A handful of rules are interim and documented as such: routing (M3), fees (reference data), the
  screening pass-through (M6), and simulated network events (M5).
