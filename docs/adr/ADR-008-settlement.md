# ADR-008: Settlement — T2, deferred net settlement and instant payments, run by a simulated central bank

- **Status:** Accepted
- **Date:** 2026-11-26
- **Milestone:** M7
- **Amends:** ADR-003 §1 (service levels; incoming ACKED for instant payments), ADR-004 §6 (routing methods
  DNS and INSTANT; RTGS through a clearing system), ADR-006 §1–§2 (a fourth institution on the network;
  payments that arrive settled cannot be rejected, only returned)

## Context

Until M7, "settled" meant booked on a correspondent account: Bank A credits its nostro at Bank B, and
Bank B debits Bank A's vostro. That is how correspondent banking works, but most interbank money does
not move that way. It moves in **settlement systems**, on accounts at a central bank or in a clearing
house. They come in three models:

- **RTGS** (real-time gross settlement, like T2): each payment settles on its own, in central bank
  money, the moment the payer's account covers it. Then it is final. A payment that cannot be covered
  waits in a queue.
- **DNS** (deferred net settlement, like an ACH such as STEP2): payments are collected during a cycle.
  At its end only the net positions settle, so much less liquidity is needed. Nothing is final until
  then, and a participant that cannot pay its position puts everyone's payments at risk.
- **Instant** (like TIPS or RT1): 24/7, on prefunded positions. Each payment is settled within seconds
  or rejected, and neither bank is left unsure for long.

Before M7, an "RTGS" route booked our central bank account and sent the pacs.008 straight to the
creditor's bank. No central bank kept the other side of that account.

## Decisions

### 1. A fourth instance: the central bank that runs the systems

A settlement system is **one set of books**. Every bank sees the same balance on the same account,
because there is only one. The netting of a DNS cycle needs everyone's payments at once. Each bank
cannot run its own copy: the copies would disagree. So the simulator gets a fourth instance:

| | |
|---|---|
| Who | Simulated euro-area central bank, `MNCBDEFFXXX` (already in the BIC directory since M3) |
| How | The same `bank-app`, with profile `operator`: port 8084, its own PostgreSQL (`localhost:5436/operator`) |
| What runs | The `settlement` module (`minipay.settlement.operator=true`). Its network inbox hands every message to the operator instead of a payment hub. The hub, compliance and the rest are present but idle. |
| Its books | Its own ledger. Each participant's accounts are liabilities of the central bank, like a vostro. |

It runs three EUR systems, all in reference data (§3):

| Code | Name | Model | Simulated on |
|---|---|---|---|
| `T2` | T2 (sim) | RTGS, customer cut-off 17:00 Frankfurt | T2 RTGS |
| `MPNET` | MiniPay Net | DNS, cycles at 09:00, 12:00, 15:00 and 17:00 Frankfurt, net positions settled on T2 | STEP2 |
| `MPINST` | MiniPay Instant | Instant, 10 s timeout, prefunded positions in central bank money | TIPS / RT1 |

In reality a private company runs the ACH (EBA CLEARING runs STEP2 and RT1) and settles through T2's
ancillary system interface. One operator keeps the simulator small and changes nothing about how
netting works.

### 2. Who takes part

| Participant | Accounts at the central bank | Why |
|---|---|---|
| **Bank B** (Frankfurt) | `RTGS-MNPBDEFF`, `INST-MNPBDEFF` | A euro-area bank: a direct participant |
| **Bank C** (London) | `RTGS-MNPCGB2L`, `INST-MNPCGB2L` | Treated as if through a euro-area branch, as UK banks have done since Brexit (the same BIC here; documented simplification) |
| **Bank D** (Brussels), **Bank E** (Paris) | both | Banks MiniPay does not run: the operator **plays** them. They accept instant payments by themselves after a short delay (`simulated-answer-delay`), and messages to them go to `minipay.network.external`. |
| **Bank A** (Kuala Lumpur) | none | Not a participant. Its EUR payments reach the systems through Bank B, its correspondent: **indirect participation**. |

**Each participant keeps a mirror of its accounts** on its own ledger: Bank B's `1001-EUR-T2` and
`1002-EUR-INST`, Bank C's `1004-EUR-T2` and `1005-EUR-INST`. The central bank's books are the truth,
and the mirrors are what M8 reconciles against them. In the seed both sides start equal: the
operator's `RTGS-MNPBDEFF` is Bank B's `1001-EUR-T2`, and so on. The counterpart of every balance at
the central bank is `CB-EUR-ASSETS`, a technical account standing for its monetary policy assets.

### 3. Reference data: clearing systems, routing methods and service levels (amends ADR-004 §6)

- **The `clearing_system` table** (effective-dated, like all reference data) holds one system per kind
  and currency on a date. For each: its operator, cut-off, cycle times or timeout.
- **Two new routing methods:** `DNS` and `INSTANT`. `RTGS`, `DNS` and `INSTANT` routes go through the
  currency's system of that kind: our message goes to its operator, who must be CONNECTED. Whether the
  creditor's bank takes part is the operator's check.
- **An RTGS that is not simulated** (MYR, GBP) keeps the M3 behaviour: the pacs.008 goes straight to the
  creditor agent.

A payment now has a **service level**, chosen by the customer:

| Service level | May use | Message |
|---|---|---|
| `NORM` normal | every method except INSTANT. Where the routing table ranks the ACH above T2, that is the cheapest way | InstrPrty NORM on T2 |
| `HIGH` urgent | every method except INSTANT and DNS: a cycle would hold it back | `PmtTpInf/InstrPrty HIGH` |
| `INST` instant | INSTANT only; with no instant route, CNOR before any hold | `PmtTpInf/LclInstrm/Cd INST` |

The routes rank the methods by priority, as in ADR-004. Bank C, for example:

- `R-003` Bank A by Bank B (SERIAL, priority 10);
- `R-004` anyone by MiniPay Net (DNS, 40);
- `R-005` anyone by T2 (RTGS, 50);
- `R-006` anyone by MiniPay Instant (INSTANT, 55).

So a normal payment to Bank B goes through the ACH, an urgent one through T2, an instant one through
MiniPay Instant. Bank B keeps paying Bank C directly on Bank C's vostro (`R-003`, priority 10) for normal
and urgent payments, so the M5 routes are unchanged.

An instant payment is executed and value-dated **today in the scheme's time zone**, whatever the
calendar and the hour: no cut-off, no business-day roll.

### 4. Messages: the V-shape

T2 is a V-shaped service:

1. The payer bank sends its pacs.008 to the system: `AppHdr/To` is the operator, `InstdAgt` stays the
   creditor's bank. `SttlmInf/SttlmMtd` is CLRG and `SttlmInf/ClrSys/Prtry` names the system.
2. The system settles it.
3. It forwards the payer's Document to the payee **unchanged, byte for byte, under its own AppHdr**
   (`Mx.readdressed`, `MessagingApi.relay`). The MsgId stays the payer's.

The operator answers the payer with pacs.002:

| Status | When |
|---|---|
| `ACSP` | DNS: accepted into the cycle, nothing moved yet |
| `ACSC` | settled: final |
| `RJCT` | refused or not settled, with an ISO reason |

The other systems add:

- **Instant:** the payee answers the operator, with ACCP or RJCT. The operator then confirms to both
  banks (ACSC), or rejects to both.
- **Returns:** a payment that settled through a system goes back through it as a pacs.004 (§8).

The receiving bank's hub records the system (`clearing_system`) and its operator (`via_bic`). It
accepts status reports and returns from the operator as it does from the next bank.

### 5. One transaction on the operator, under the payer's lock

The payment hub calls its ledger outside its own transactions, because that ledger stands for a separate
core-banking system (ADR-003 §3). The operator is different: **its ledger is the system's own books**.
So one transaction holds all of it:

- the settlement booking;
- the transfer's new status and history;
- the messages that announce it (through the hub's outbox: `NetworkOutbox`, the only way onto the network, rule 6).

Either the money moved and everybody is told, or neither happened.

**Concurrency:**

- **Every debit of a participant's accounts happens under the lock of its participant row** (`SELECT … FOR
  UPDATE`, in BIC order when several are locked, as a DNS settlement does). "Is there enough?" and
  "take it" can therefore not be separated by another payment from the same bank.
- **The ledger's own funds check stays as the backstop.**
- **Proved by mutation:** two payments of 60 from an account holding 100 arrive together. With the lock,
  one settles and one queues. Without it, both see 100, and the loser's whole arrival fails on the
  ledger's funds check (`SettlementIT.racing_payments_from_one_payer_never_overdraw_it`).

**The operator refuses what it cannot take, with an ISO reason:**

| Reason | When |
|---|---|
| AG01 | the sender is not a participant, or InstgAgt is not the sender |
| CNOR | the creditor's bank is not a participant ("not registered in the CSM") |
| AM03 | wrong currency for the system |
| DT01 | settlement date in the past, or not today for an instant payment |
| TM01 | received after the T2 cut-off for today |
| AM04 | instant: the payer's prefunded position cannot cover it |

### 6. T2: gross settlement, the liquidity queue, and the cut-off

**Settling:** a payment settles on arrival if the payer's account covers it: debit the payer, credit
the payee, forward the payment, ACSC to the payer. Otherwise it **queues**.

**The queue rules** (`QueuePolicy`, as T2 does it):

1. Urgent (HIGH) before normal; within a priority, queue order.
2. **Urgent payments are strictly FIFO.** If the first does not fit, nothing behind it settles, of any
   priority: a big urgent payment is not starved by small ones.
3. **Normal payments may bypass (FIFO-bypassing).** One that does not fit waits; a smaller one behind
   it may settle. Liquidity is used rather than left idle.
4. A payment for a later date waits for its day (warehoused).

**Liquidity flows on:**

- **After a settlement commits,** the queues of the participants that were credited are tried again,
  each in its own transaction. One payment can release another, which releases a third.
- **A timer retries every queue** as the backstop (every 10 s).
- **The payer manages its queue (U2A):** move a payment up, or make it urgent or normal. Each command
  carries an Idempotency-Key, as every write does (rule 5).

**The cut-off:** at 17:00 Frankfurt, whatever is still queued for today is **rejected AM04**, and its
payer reverses its booking. An RTGS carries nothing into the next day.

**A bank's T2 mirror may go below zero.** A bank books its mirror when it sends. If its own books refused
a payment the mirror cannot cover, the queue at T2 would never form: T2 is the one that knows the
balance. So the mirror is allowed an overdraft. Between sending and settling, it differs from T2 by the
payments still queued: an in-flight item, which M8 will reconcile.

### 7. MiniPay Net: cycles, multilateral netting and unwinding

**Collecting:**

- A normal payment joins the open cycle for its settlement date (`MPNET-20261126-01`), and the payer gets
  an ACSP. **Nothing moves** and the payee hears nothing.
- A cycle is opened by its first payment and closes at the next cycle time.
- **The last cycle is at the T2 cut-off (17:00).** The hub's value dating, which follows T2's cut-off,
  therefore never dates a payment into a gap.

**At the close** (`Netting`), under the locks of all participants:

| View | What it adds up | In the E2E test |
|---|---|---|
| **Gross** | every payment on its own | 5,000 |
| **Bilateral net** | per pair, the difference | |
| **Multilateral net** | per bank, everything received minus everything paid: one position per bank, summing to zero | 3,000 |

Only the **net positions settle**: one journal entry on the T2 accounts. Net payers are debited and
net receivers credited. Then, and only then, the payments go on to the payees, and the payers get ACSC.

**Unwinding.** A net payer whose T2 account cannot cover its position breaks the cycle. Everyone
else's position assumed its payments. Without a settlement guarantee the ACH **unwinds** it:

- every payment to and from that bank is taken out, and **rejected AM04**;
- the positions are computed again, biggest shortfall first, until the rest settles.

Taking a bank out can leave another bank short, because a payment it counted on is gone. That knock-on
is the **systemic risk** of deferred net settlement, the lesson of Herstatt (1974) and the Lamfalussy
standards. `NettingTest` shows two banks unwound in a row.

An ACH can run an extra cycle on demand: "close and settle now" (U2A, with an Idempotency-Key).

### 8. MiniPay Instant: prefunded positions and a 10-second timeout

**The happy path:**

1. **The payer's prefunded position is reserved** (a ledger hold on its instant account), and the payment
   is forwarded to the payee with a deadline: arrival + 10 s.
2. **The payee answers the scheme:** pacs.002 ACCP to accept, RJCT to refuse.
3. **Accepted in time:** the reservation settles into the payee's position, final, and **both** banks get
   ACSC.

**Rejected, or no answer in time:** the reservation is released, and both banks get RJCT. A timeout is
**AB05** ("timeout at the creditor agent"). A timer runs four times a second. A late ACCP changes
nothing: the payment stays rejected.

**At the creditor's bank** (amends ADR-003 §1):

- It validates and screens, then accepts to the scheme (`ACKED`, a new incoming transition).
- It credits its customer **only when the scheme confirms settlement**. Crediting on its own ACCP would
  pay out money that might never arrive.
- **A sanctions hit cannot be decided in 10 seconds.** The payment is held, the scheme times out, and
  both banks end with AB05. This is the real trade-off of screening instant payments, and
  `NetworkE2EIT` shows it.

**Instant positions do not go below zero, at the bank or at the scheme.** Prefunding is the point:
liquidity is placed in advance, so the scheme never has to wait for it.

### 9. A payment that arrived settled is final: it can only be returned (amends ADR-006 §2)

Through T2 or MiniPay Net, a payment reaches the creditor's bank **after** it settled: the money is
already on that bank's T2 account. The bank can no longer reject it with a pacs.002. If it cannot apply
the payment (AC01, or RR04 after a compliance rejection), it **returns** it:

1. The bank sends a pacs.004 to T2, having booked nothing.
2. T2 settles the pacs.004 back, gross, like a payment.
3. T2 forwards it to the original payer, which credits its customer (the M5 return booking).

Both banks' books stay in step with the central bank's, because neither booked the payment and T2
moved the money in and out.

### 10. Testing

| Test | What it shows |
|---|---|
| `QueuePolicyTest` (5) | urgent first; urgent FIFO blocks; normal bypass; moving up; warehoused |
| `NettingTest` (4) | the design's matrix (gross 39.80, bilateral 4.20, multilateral 1.90, positions summing to zero); a cycle settling whole; unwinding with its knock-on; an exact cover |
| `RouteSelectorTest` (+3) | instant-only routing, through the operator; a DNS rule with no DNS system is unusable and not skipped; a non-simulated RTGS |
| `MxRoundTripTest` (+2) | ClrSys, InstrPrty and INST; a relayed Document is byte-identical |
| `SettlementIT` (13) | real messages against a real PostgreSQL: gross settlement and the relay; queueing and liquidity flowing on; priority change with idempotency; cut-off rejection and TM01; the racing payments; CNOR and redelivery; a return; a DNS cycle netted and delivered after settlement; unwinding; instant accept, timeout (with a late accept), insufficient position, a simulated payee |
| `NetworkE2EIT` (+5) | four instances, over the network only: urgent through T2; a DNS cycle (5,000 of payments, 3,000 of liquidity); an instant payment accepted; an instant payment held for compliance at Bank C, timed out AB05 and reversed at Bank B; a T2 payment Bank B cannot apply, returned through T2. After each, every bank's mirror equals the central bank's account, to the cent. |

## Consequences

- **Money moves the way it does between real banks:** in central bank money, gross or net, or
  instantly. Every booking at a bank has its counterpart in the central bank's books. M8 can reconcile
  them, including the in-flight items: a payment queued in T2, a payment in a cycle that has not settled.
- **The three models' trade-offs can be watched:**
  - **T2** needs liquidity for every payment, and queues when it lacks it.
  - **MiniPay Net** needs far less, and pays for it in delay and unwinding risk.
  - **MiniPay Instant** needs liquidity placed in advance, and gives an answer within seconds.
- **Not modelled:**
  - Intraday credit (collateralised overdraft at the central bank).
  - Liquidity reservations and bilateral or multilateral limits.
  - T2's gridlock resolution (offsetting queued payments between banks).
  - Revoking a queued payment (camt.056).
  - Liquidity transfers between a bank's T2 and instant accounts (camt.050), so no top-ups: the
    positions are seeded.
  - The ACH's settlement guarantee (STEP2's loss-sharing), and bulk files.
  - Returns of instant payments, and recalls.
  - The originator bank's own instant timer.
  - The ACH as a separate company settling through T2's ancillary system interface.
  - CHAPS, RENTAS and Fedwire as systems (their RTGS routes still go straight to the creditor's bank).
  - A UK bank's euro-area branch with its own BIC.
