# ADR-005: Messaging — ISO 20022 business messages, the raw store, and MT103 translation

- **Status:** Accepted
- **Date:** 2026-11-25
- **Milestone:** M4
- **Amends:** ADR-003 §11 (network outcomes are now real messages)
- **Amended by:** ADR-006 (M5): pacs.009 COV and camt.054; every sent message goes on the network; RJCT and
  inbound pacs.008 are acted on;
  ADR-009 (M8): camt.053 entries name their payments (TxDtls UETR, EndToEndId) and itemise batch bookings; received
  statements are read for reconciliation

## Context

Until M3 a payment that was SENT existed only in our own tables, and the "network" answered through
a JSON endpoint. Real banks talk in messages:

- the payment itself is a **pacs.008** (FI-to-FI customer credit transfer);
- the next bank answers with a **pacs.002** (status report) and, if the money comes back, a
  **pacs.004** (payment return);
- account holders get a **camt.053** (end-of-day statement).

Under CBPR+, cross-border payments on SWIFT have used these ISO 20022 (MX) messages since 2023. MT103
is still everywhere at the edges, so translation between the two is part of the job.

Rule 8 says every message is stored exactly as sent or received, next to the fields parsed from it. A
message is evidence. When a customer, a correspondent or an auditor asks "what did you send?", the
answer is the bytes, not a reconstruction.

## Decisions

### 1. A messaging module that knows nothing about payments

`messaging` builds, parses, checks, translates and stores messages. It depends on `common`, and on
`ledger.api` for camt.053; it does not depend on the payment hub. The hub calls it: `issuePacs008`
when sending, and `receive` for anything that comes in. The dependency points one way, as in a real
bank, where the payment engine uses the message gateway and not the other way round.

Messages are built and read with **Prowide** (`pw-iso20022`, Apache 2.0): typed JAXB models for the
ISO 20022 catalogue, and `pw-swift-core` for MT. One consequence: Prowide needs `commons-lang3` ≥ 3.18,
newer than Spring Boot 3.5 manages, so the parent POM overrides `commons-lang3.version`.

### 2. Business messages: AppHdr + Document, in an envelope

Every MX message is a **business message**:

- a Business Application Header (`head.001.001.02`, the version CBPR+ uses) with From, To,
  BizMsgIdr, MsgDefIdr, BizSvc `swift.cbprplus.02` and CreDt;
- the Document.

Both travel inside the ISO business message envelope (`nvlp.001.001.02`), so one stored text holds the
whole message.

| Message | Version | Built by us when | Read by us when |
|---|---|---|---|
| pacs.008 | .001.08 | a payment is sent | (inbound payments: M5) |
| pacs.002 | .001.10 | (as the next bank, in the simulation) | the next bank reports a status |
| pacs.004 | .001.09 | (as the next bank, in the simulation) | the money comes back |
| camt.053 | .001.08 | an account holder's end-of-day statement | (our nostro statements: M8) |

- **CBPR+ allows one transaction per message**, so every message has exactly one.
- **MsgId** is `<BIC4><yyyyMMdd>-<type>-<seq>` (`MNPA20261124-008-000912`): unique per sender, ≤ 35.
- **InstrId** is 16 characters (`MNPA261124000912`), the CBPR+ limit during the MX/MT coexistence period,
  so that it still fits MT103 `:20:` when a bank on the route translates.

### 3. The raw store

`messaging.message` keeps:

- the **raw text**, untouched;
- its **SHA-256**. A CHECK constraint recomputes the hash in PostgreSQL, so a row whose text was
  altered cannot exist;
- the parsed fields used for searching and correlating: type, MsgId, sender, receiver, UETR,
  end-to-end id, InstrId, original MsgId, account, amount, settlement date, status, reason code.

If a parsed field and the raw text ever disagree, the raw text wins. Rows are append-only (trigger).

"The same message" means two different things:

- **Outbound:** a *business key* names what the message is for (`pacs.008:<payment id>`). A pipeline
  step re-run after a crash, or ten workers racing on one payment, get the message that was already
  built: same MsgId, same bytes. A message, once sent, is a fact; it is never rebuilt. (A unique
  constraint on the business key enforces it; the racing test fails without it.)
- **Inbound:** the sender's MsgId identifies the message, because a bank never reuses one. The same
  message received again is a **duplicate**, a resend or a replay. It is stored once (unique index on
  direction, sender and MsgId) and reported as a duplicate, so the hub does not act on it twice.

### 4. Checks, and invalid messages are kept

Without the published XSDs and usage guidelines, the simulator enforces a CBPR+-inspired subset:

- **Header:** an AppHdr with valid BICs, naming the same message type and MsgId as the Document, and
  addressed to us.
- **Content:**
  - one transaction per message;
  - a lower-case UUIDv4 UETR;
  - an InstrId of at most 16 characters;
  - positive amounts with no more decimals than the currency has;
  - real-shaped status codes; RJCT and returns carry a reason;
  - a return can be at most the original amount.

Every failed check is reported, not only the first. An inbound message that fails is **still
stored**, marked INVALID with its errors, and the caller gets a 422. A message we could not accept is
exactly the one we will be asked about later. Unreadable input is stored the same way.

**We check what we send.** Every outbound MX message is read back by the same checks before it is
stored. Sending something we would ourselves reject is a bug, and it fails loudly.

### 5. The hub and messages

- **Sending.** After the ledger booking, the send step issues the pacs.008 (business key), then
  records SENT. The history row names the MsgId. `SttlmMtd` follows the route:
  - our nostro at the next bank → **INDA** (they debit it);
  - their vostro with us → **INGA** (we credit it);
  - a cover route → **COVE**;
  - an RTGS route → **CLRG**.

  Under CRED, `ChrgsInf` names our fee, which explains why `IntrBkSttlmAmt` is less than `InstdAmt`.
- **Receiving** (`POST /api/payments/inbound`, raw XML, with an Idempotency-Key):
  1. the message is **stored first**, whatever happens next;
  2. it is correlated by `OrgnlUETR`;
  3. the sender must be the bank we sent the payment to;
  4. the event is applied:

  | Message | Effect |
  |---|---|
  | pacs.002 ACCP / ACSP | ACKED |
  | pacs.002 ACSC | SETTLED |
  | pacs.004 | RETURNED, with the return booked in the ledger (idempotent key `pay:<id>:return`) |
  | pacs.002 RJCT after SENT | stored, not acted on: a rejection by the next bank arrives with M5 |
  | inbound pacs.008 | stored, not acted on: inbound payments arrive with M5 |

- **Order is not guaranteed on a network.** A duplicate, or a status that arrives after a later one
  (an ACCP after the ACSC), never moves a payment backwards. It is stored, acknowledged, and nothing
  else happens.
- **The simulation is now a message.** Until the network simulator (M5), the console's "simulate
  network" buttons build the message the next bank would send (pacs.002 or pacs.004, *from* the
  instructed agent) and feed it through the same inbound path. Everything that reaches a payment
  arrives as a stored, checked, correlated message.
- **Preview.** `POST /api/payments/preview` builds the pacs.008 a payment would produce now: the same
  fee, value date and route as the pipeline, with nothing created, held or booked. The console's
  Create payment screen shows it live.

### 6. MT103 ↔ pacs.008: translation with a loss report

Translation follows the CBPR+ rules in simplified form, and every translation returns a field-by-field
report: MT tag, MT value, MX path, MX value, and a **fidelity**:

| Fidelity | Example |
|---|---|
| EQUIVALENT | UETR in block 3 `{121:}`, the agents' BICs |
| TRANSFORMED | `:32A:` YYMMDD and a decimal comma; SHAR → SHA; "&" → "AND" (not in the SWIFT X character set) |
| TRUNCATED | a name longer than 3 × 35 characters after the account line; an InstrId over 16 characters |
| IMPLICIT | `:23B: CRED`; the settlement method (no `:53a:` means settlement on the bilateral account) |
| LOST | the end-to-end id and the group MsgId: MT103 has no field for them |

The reverse (MT103 → pacs.008) cannot recover what MT never carried: the end-to-end id becomes
`NOTPROVIDED`, name and address lines can no longer be told apart, and "&" stays "AND". Round-tripping
pacs.008 → MT103 → pacs.008 is therefore deliberately *not* lossless, and the tests say exactly where.

### 7. camt.053

A statement is issued for an account **we keep for someone else**: a customer's account, or another
bank's vostro. For a vostro it is addressed to that bank's BIC; for a customer, to the customer channel.
The statement for *our* nostro comes from the bank that keeps it; reconciling the two is M8.

- **Point of view.** The statement is written from the account holder's side. For these accounts that
  agrees with our ledger, because they are liabilities: a credit is CRDT, a debit DBIT.
- **Balances.** Opening (OPBD) and closing (CLBD) booked.
- **Entries.** Each entry has its booking time and value date, the journal entry id as AcctSvcrRef,
  and an ISO bank transaction code: PMNT/ICDT/XBCT, PMNT/RCDT/XBCT, or PMNT/MDOP/CHRG for charges.
- **ElctrncSeqNb** counts up per account.

Issuing a statement is a write (`POST /api/messages/camt053`) with an Idempotency-Key.

## Consequences

- Every payment that leaves the bank has its pacs.008 on record, and every answer that comes back has
  its pacs.002 or pacs.004. These are byte-identical and hash-checked, and can be downloaded from the
  console.
- The hub reacts only to messages that are well-formed, addressed to us, from the bank we sent to, and
  not seen before.
- The console shows each message as XML, and a pacs.008 next to its MT103 with what translation loses.
- Not done here:
  - XSD and full CBPR+ usage-guideline validation;
  - pacs.009 COV for cover routes (M5);
  - acting on RJCT and inbound pacs.008 (M5);
  - structured addresses (our payments carry names only);
  - UETRs on camt.053 entries (M8 needs them for matching).
