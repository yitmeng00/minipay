# ADR-006: The network — delivery, incoming payments, hop-by-hop outcomes, cover, and the UETR tracker

- **Status:** Accepted
- **Date:** 2026-11-26
- **Milestone:** M5
- **Amends:** ADR-003 §1 (state machine), §11 (network outcomes); ADR-005 §5 (receiving)
- **Amended by:** ADR-007 (M6): every bank screens incoming payments; the tracker reads CdtrAgt from the pacs.008;
  ADR-008 (M7): the central bank joins the network as a fourth institution (V-shaped clearing systems); a payment
  that arrived settled through T2 or MiniPay Net is returned (pacs.004 through the system), never rejected

## Context

Until M4 each bank was alone. A pacs.008 was stored but went nowhere, and the "network" was a
console button that played the next bank. M5 connects Bank A, Bank B and Bank C:

- a payment travels from bank to bank as messages;
- every bank books its leg, takes its charge, and answers;
- outcomes (confirmation, rejection, return) find their way back;
- the payment can be followed end to end by its UETR, as SWIFT's gpi Tracker does.

Both settlement methods of correspondent banking must work:

- **Serial:** the payment itself travels through each bank.
- **Cover:** the payment goes straight to the creditor's bank, and the money travels separately
  through correspondents.

## Decisions

### 1. The network is a store-and-forward topic per bank

Each simulated bank has an inbox topic, `minipay.network.<BIC8>`. Banks the simulator does not run
(Paris, Brussels …) share `minipay.network.external`: a message to them has left the bank, but nobody
answers it. For those banks, and only those, the console can still play the receiver (the M4
simulation endpoint).

- **Sending is not done by business code** (rule 6). The message is written to the transactional
  outbox, in the **same transaction as the status change that produces it**, so "SENT" and "a pacs.008
  is on its way" are one fact. The relay publishes it to the receiver's inbox, keyed by UETR. Every
  bank declares every inbox topic, so a bank that is down still receives its messages when it starts.
- **Delivery is at least once.** The receiving bank's inbox (in the `network` module) hands each
  message to its payment hub with the Idempotency-Key `net:<event id>`, so a redelivery replays. A
  *resend* (a new event, the same MsgId) is caught by the message store's (sender, MsgId) uniqueness
  (ADR-005 §3). Ten consumers racing on the same pacs.008 create one payment, credited once; the test
  fails if the replay on the losing insert is removed.
- **Refusals are not retried.** A message the hub refuses (invalid, unknown UETR, out of order) is
  already stored as evidence, so the inbox logs it and moves on. Only technical failures, such as the
  database being briefly unavailable, are retried by the container's error handler.
- **Order is not guaranteed.** Kafka orders one topic's partition, but a pacs.002 and a camt.054 come
  from different banks through different topics. Every rule below works whichever arrives first.

### 2. Incoming payments: three roles

A pacs.008 addressed to us becomes an **INCOMING** payment that runs through the same step pipeline as
outgoing ones (ADR-003 §3). Every ledger call and every message has a key derived from the payment,
so the recovery sweeper can re-run any step.

| Role | When | What we do | Answer to the sender |
|---|---|---|---|
| Creditor's bank | CdtrAgt is us | credit our customer, net of our charge | pacs.002 **ACSC** (stating our charge) |
| Intermediary | CdtrAgt is someone else | book it through, deduct our charge, forward a pacs.008 | pacs.002 **ACSP** now; the final answer later |
| Cover correspondent | a pacs.009 COV | debit the debtor bank's account, credit the creditor bank's | camt.054 to the creditor bank |

**Where the money arrives** depends on the message's settlement method:

| SttlmMtd | Booked on | We debit |
|---|---|---|
| INDA | our books | the sender's vostro with us |
| INGA | the sender's books | our nostro with the sender (our asset there grew) |
| CLRG | the RTGS | our central bank account (the one our routing table uses for RTGS) |
| COVE | the cover agent's books | our nostro with the cover agent, once its camt.054 arrives |

A payment we cannot take is **REJECTED before anything is booked**, and the sender gets a pacs.002
RJCT with the ISO reason:

- **AC01 / AC04 / AC06 / AM03:** creditor account unknown, closed, blocked, or in another currency;
- **CNOR:** no route onwards;
- **AG01:** we hold no account that the settlement method could use;
- **AM04:** the sender's vostro cannot cover it.

**Charges.** Under SHAR and CRED every bank deducts its charge from what it passes on or credits
(`minipay.payments.processing-fees`): Bank B takes EUR 15, Bank C EUR 10.

- **The record of deductions:** each forwarded pacs.008 carries the growing ChrgsInf list and the
  previous agents (PrvsInstgAgt), and the creditor's ACSC states its own deduction. The whole route,
  and the tracker, can therefore see what the creditor received.
- **Under DEBT nothing is deducted.** Intermediaries claiming their charges from the debtor's bank
  afterwards is not modelled.

### 3. Outcomes travel back hop by hop

A bank only ever talks to its neighbours. An outcome therefore travels back the way the payment came:

- **ACSC.** The creditor's bank confirms to its sender; an intermediary that receives the ACSC marks
  its leg SETTLED and relays it, with the creditor's stated charges, to the bank before it.
- **RJCT after sending.** The bank that rejects never booked the payment, so the banks before it undo
  theirs: each **reverses** its booking (REVERSAL entries, never deletions, rule 2) and relays the
  RJCT. The debtor gets back the principal and the debtor's bank's fee.
- **pacs.004 return.** The creditor's bank returns what it credited (its own charge is not refunded)
  and lists every deduction in ChrgsInf. Each intermediary books the return back across the same two
  accounts, adds its own charge to the list, and passes the return on. The debtor's bank credits its
  customer (ADR-003 §11). In the design's example, A sent 48,250, B kept 15 and C kept 10, so 48,225
  comes back.
- **Sender check.** A status or return is accepted only from the bank we sent the payment to, or from
  its cover agent.
- **No moving backwards.** A duplicate, or a status that arrives after a later one, changes nothing
  (ADR-005 §5).

### 4. Cover method

Bank A pays Bank C's Manchester branch by cover (rule R-020) and Bank C's head office serially (R-003).
That makes both methods visible between the same banks.

1. **Bank A sends two messages in the SENT transaction:**
   - a pacs.008 straight to Bank C, with `SttlmMtd COVE` and `InstgRmbrsmntAgt` = Bank B;
   - a pacs.009 COV to Bank B, with the customer payment copied into `UndrlygCstmrCdtTrf` (the CBPR+
     transparency rule: a correspondent must see whose money it moves).
2. **Bank B, the correspondent,** debits Bank A's vostro, credits Bank C's vostro, and sends Bank C a
   **camt.054** credit notification carrying the UETR.
3. **Bank C** holds the pacs.008 in **AWAITING_COVER** and pays nothing out until the camt.054 from the
   cover agent arrives. Paying on the strength of the pacs.008 alone would be an unsecured advance to
   Bank A. Once the cover is there, it debits its nostro at Bank B, credits the customer net of its
   charge, and confirms ACSC **to Bank A**, the sender of the pacs.008.

The camt.054 may arrive first. It is then stored, and matched by UETR when the pacs.008 comes. The
sweeper also re-checks payments waiting for cover.

### 5. State machine: one table per direction

`PaymentStateMachine` now has an OUTGOING and an INCOMING table, and both are tested pair by pair
(242 cases).

- **Outgoing:** after SENT the network decides, so SENT may go to ACKED, SETTLED (an ACSC without an
  acknowledgement first, as from a creditor's bank paid directly), REJECTED (a RJCT from further on,
  with our booking reversed) or RETURNED. A return can come before any confirmation, because with
  INDA/INGA the money moved when we sent.
- **Incoming:**
  - SCREENED → SETTLED (credited), AWAITING_COVER, SENT (passed on) or REJECTED;
  - AWAITING_COVER → SETTLED or REJECTED;
  - after SENT, the same as outgoing.

### 6. The UETR tracker

The `network` module also runs the tracker. Like the gpi Tracker, it is an **observer**:

- it reads every network topic, every bank's inbox and the external one, with its own consumer group;
- it records what each message says (`messaging.inspect`: type, banks, amount, status, deductions),
  keyed by event id so a redelivery is recorded once;
- it never acts on a message and never stores it as received.

Each bank instance keeps its own copy of this read model, because the simulator has no fourth,
central service.

Per UETR it derives the route:

- **Hops:** each pacs.008 is a hop. Its answer is the pacs.002 from the receiver that points at that
  hop's MsgId; the most advanced answer wins.
- **Legs:** the pacs.009 and camt.054 are the cover legs; the pacs.004s are the return path.
- **Deductions and credited amount:** every bank's deduction, and what the creditor received
  (known once the creditor's ACSC states its charge).
- **Status:** ACSC (the creditor's bank confirmed), RJCT (a rejection reached the debtor's bank), RTND
  (a return reached it), else ACSP (on its way).

The console shows it as the design's **Payment route** card on every payment, and on a tracker page
for any UETR.

### 7. Testing the network

`NetworkE2EIT` starts Bank A, Bank B and Bank C as three complete applications, each with its own
PostgreSQL, over one Kafka. It drives five scenarios through the network only:

- **serial:** the creditor gets 1000 − 15 − 10 = 975;
- **cover:** the creditor gets 2000 − 10 = 1990;
- **direct GBP;**
- **a return by Bank C,** which travels back through Bank B;
- **a rejection by Bank C,** reversed at Bank B and at Bank A.

Every nostro must still mirror its vostro at the other bank, and every ledger passes its integrity check.

## Consequences

- Payments now cross banks for real: every booking has its counterpart booking at the other bank, and
  M8 reconciliation will have something real to reconcile.
- Every bank can answer "where is my payment?" by UETR, with each bank's deduction on the way.
- **Not modelled:**
  - an intermediary adding a cover leg;
  - returning a cover payment;
  - the cover leg itself being rejected;
  - DEBT charge claims;
  - pacs.028 status requests;
  - network latency or failure injection;
  - a single central tracker.
