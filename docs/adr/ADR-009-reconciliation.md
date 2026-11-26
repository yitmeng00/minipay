# ADR-009: Reconciliation — our nostros against the servicers' camt.053 statements

- **Status:** Accepted
- **Date:** 2026-11-27
- **Milestone:** M8
- **Amends:** ADR-005 (camt.053 entries name their payments; batch bookings; reading a received statement),
  ADR-008 (the central bank sends statements of the participants' accounts)

## Context

Every account a bank holds at another bank exists twice:

- **at the servicer**, as a vostro (or a participant's account at the central bank): its books, the truth;
- **at the owner**, as a nostro: our mirror, which we book when we send and receive.

Until M8 nothing checked that the two agree. In the real world, every bank checks every nostro every
day against the servicer's end-of-day statement (camt.053, formerly MT950). That check is **nostro
reconciliation**. What does not agree is a **break**. Unresolved breaks are an operational risk:
money paid twice, a charge never booked, or a payment the correspondent never made.

The simulator now produces exactly the differences reconciliation exists for:

- **Timing:** we book a payment today; the servicer books it after its cut, or on its own date (Kuala
  Lumpur is a day ahead of London for eight hours a day).
- **Held payments:** a payment held for compliance at the correspondent, booked by us and not by it.
- **In-flight payments:** a payment queued in T2 or waiting in a DNS cycle.
- **Gross versus net:** a DNS cycle settles one net amount against our many gross payments.
- **The servicer's own bookings,** such as a charge we did not know about.

## Decisions

### 1. Two roles in one module

| Role | Who | Does |
|---|---|---|
| **Servicer** | the bank keeping the account: Bank B for Bank A and Bank C, Bank C for Bank A, the central bank for Banks B and C | sends a camt.053 per account and day to the owner, over the network |
| **Owner** | the bank holding the nostro | matches each statement against its own ledger lines on the mirror account, and works the breaks |

Every bank plays both roles; the central bank only the first. The `recon` module depends on the
ledger, messaging, `paymenthub.api` (payment references, and the outbox: `NetworkOutbox`) and
`settlement.api` (the central bank's transfers).

**Nostro master data** says which of our accounts is kept by whom, under which number. Real banks keep
exactly this. In the simulator it is the profile's `minipay.recon.nostros`:

```yaml
- { account: 1101-EUR-MNPB, servicer: MNPBDEFFXXX, servicer-account: DE87500100600778812003 }
```

A statement for an account that is not in it is stored and logged, not reconciled. Bank A sends
statements of the MYR vostros it keeps for Bank B and Bank C, and they hold no MYR nostro.

### 2. The statement: once per account and day, naming every payment

**Sending:**

- A servicer sends **yesterday's** statements after midnight in its own time zone. A timer runs every
  5 minutes, and each statement is sent once.
- The statement is issued under the key `camt053:<account>:<date>` and put on the outbox in the **same
  transaction**: issued and sent, or neither. Publishing again for that date replays and sends nothing.
- `POST /api/recon/statements/publish` sends a date by hand. Sending **today** cuts the day short: what is
  booked later that day will be on no statement. Its later effect shows on the next statement's opening
  balance, which reconciliation reports as an unexplained difference. That is how a real statement gap
  shows up. The console offers it for demos only.

**Each entry names the payment behind it** (`NtryDtls/TxDtls/Refs`: UETR, EndToEndId, InstrId). The
ledger does not know those references, so the issuer supplies them (amends ADR-005):

- **A bank** looks up the hub's payment from the line's payment id.
- **The central bank** looks up its transfers: the one transfer for a T2 or instant booking, and **every
  payment of the cycle that concerns the account holder** for a DNS net booking.
- **A batch booking** (`NtryDtls/Btch`, several TxDtls, each with its own amount and direction) is how a
  real ACH settlement report itemises a net amount.

### 3. Matching works on open items, not on one day

Every run takes **everything not matched yet, on both sides, since the first statement**:

- our ledger lines on the nostro;
- every received statement's entries.

A payment we booked on the 26th may be on the servicer's statement of the 27th. The timing break it
causes **clears by itself** when that statement arrives. Nobody has to touch it.

**Point of view.** Every amount is signed from the owner's point of view: + is money in.

- A ledger **Dr** on our nostro (an asset grows) is the servicer's **CRDT** (it owes us more).
- A ledger **Cr** is a **DBIT**.

After signing, a match has equal amounts on both sides.

**Rules, strongest first** (`Matcher`, a pure function, unit-tested):

| Rule | Matches | Example |
|---|---|---|
| `TAKE_ON` | the opening balances booked when the account was set up, on both sides, allowing for what each side held before the first statement | the seed's opening entries; the central bank opening Bank B's T2 account in one booking against Bank B's older history |
| `UETR` | the same UETR and amount: unique per payment, the strongest key | almost everything |
| `BATCH` | one statement entry against several of our lines: every transaction it lists matches one of our lines by UETR and amount, and together they make the entry | a DNS cycle: 1 net entry, 4 gross payments |
| `E2E` | the same end-to-end id and amount (never `NOTPROVIDED`) | a servicer that reports no UETR |
| `REFERENCE` | our line names the servicer's reference (AcctSvcrRef), with the same amount | our adjustment for its charge |
| `CONTRA` | on one side only: a booking and its exact opposite for the same payment | we sent a payment and reversed it after a rejection; T2 settled a payment and its return |
| `ADJUSTMENT` | a break resolved by booking the difference (§5) | |
| `MANUAL` | anything a person matches by hand: the amounts must add up, with a comment of 10+ characters | |

**An item is matched once, ever** (the primary key on `match_item`). One account is reconciled at a time
(an advisory lock).

### 4. Breaks

Whatever is left is a break, typed by what is wrong. The **difference** is statement − ledger, signed:

| Break | Meaning | Suggested resolution |
|---|---|---|
| `MISSING_IN_STATEMENT` | on our books only: timing, a payment held at the correspondent, a payment queued in T2 | carry forward; it clears when it arrives |
| `MISSING_IN_LEDGER` | on the statement only: a charge the servicer took, a payment we never booked | book an adjustment, or raise a claim |
| `AMOUNT_MISMATCH` | the same UETR with another amount: a charge deducted on the way | book the difference, or claim it back |
| `DUPLICATE` | the same UETR and amount as an entry already matched: the servicer booked it twice | raise a claim; when it is reversed, match the two entries by hand |

**Lifecycle:**

- A break **clears** when a later match takes its item (status CLEARED, pointing at the match).
- It stays OPEN until then, unless someone **resolves** it (§5).
- Only its status and resolution change; a trigger keeps the rest.

### 5. Resolving a break

Each action carries an Idempotency-Key (rule 5), who acted (the operator picked in the console), and
a comment of 10 to 500 characters. The comment is the audit trail.

| Action | For | Does |
|---|---|---|
| `CARRY_FORWARD` | missing in statement | marks it as expected later; it still clears by matching |
| `ADJUSTMENT` | missing in ledger, amount mismatch | **books the difference** on our nostro against the bank charges and differences account (`minipay.recon.adjustment-accounts`, e.g. `6110-EUR-BANKCHG`), naming the servicer's reference, and records the match |
| `CLAIM_RAISED` | duplicate, missing in ledger, amount mismatch | records that we asked the servicer to correct it (the camt.027/camt.087 exchange is not modelled) |

**Undo:** carry-forward and claim can be undone (the break reopens). An adjustment is booked, so it stays;
if it was wrong, it is reversed in the ledger.

The adjustment is posted outside the recon transaction, because the ledger is its own system (ADR-003
§3). Its key, `recon:adj:<break>`, makes a retry replay instead of booking twice.

### 6. The balance proof

For a statement date D:

- **difference** = the statement's closing balance − our nostro's closing balance at D.
- **explained** = the open items up to D:
  - every statement entry and ledger line up to D that is not matched within D;
  - the difference between the two balances before the first statement, less what the take-on match
    accounts for.

When the books and the statements tell one story, **difference = explained**, and the console shows
"Balances". Two things show as **unexplained**: a gap in the statements (a day missing, or a statement
cut short), and an opening difference the take-on does not account for. The dev databases show the
second honestly: Bank A's nostro had drifted from Bank B's vostro by EUR 393,688.75 in earlier
milestones' demos, before the first statement. The proof reports it as the opening difference.

### 7. The screen

The console's **Reconciliation** screen follows `design/Reconciliation.dc.html`, per nostro and
statement date:

- the statement and the matching rules;
- the figures (entries, matched, match rate, open breaks, unmatched value) and the balance proof;
- the matching table, ledger ↔ statement, with the selected camt.053 entry's XML and why it matched;
- ticking unmatched items to match them by hand;
- the breaks with their suggestions and actions.

The nav shows the number of open breaks.

## Testing

| Test | What it shows |
|---|---|
| `MatcherTest` (10) | Dr ↔ DBIT on UETR; E2E, never NOTPROVIDED; a DNS net entry against its gross payments; an amount mismatch with its difference; a duplicate, in the same run and against an earlier one; a contra pair; timing and a charge with signed differences; an adjustment matched by reference; take-on, with and without history before the first statement |
| `StatementRoundTripTest` | a camt.053 written with one payment and one batch and read back: balances, references, each transaction's direction, each entry's own XML |
| `NetworkE2EIT` (+1) | after every flow of M5–M7, Banks B and C and the central bank send their statements. All seven nostros reconcile with no break, through take-on, UETR, BATCH (the DNS cycle) and CONTRA (reversals, a return through T2), with nothing unexplained. The one exception is a maintenance charge Bank B booked on Bank A's account: a MISSING_IN_LEDGER break of −25.00, which Bank A resolves by an adjustment, after which its nostro equals Bank B's vostro to the cent. |

## Consequences

- Every nostro of every bank is checked against its servicer's books every day, by the payment's own
  keys. The breaks that remain are the ones people must look at. Timing differences resolve themselves.
- The simulator's earlier milestones are now verified from the outside: correspondent banking (M5),
  compliance holds (M6) and the three settlement systems (M7) all leave both sides of every account in
  agreement, or say exactly why not.
- **Not modelled:**
  - intraday statements (camt.052) and debit/credit notifications as matching input;
  - claims and investigations as messages (camt.027, camt.087, camt.029);
  - auto-escalation of ageing breaks, and four eyes on adjustments;
  - matching tolerances (amount ± x, date windows) and many-to-many matches other than by hand;
  - reconciling the vostros we keep (the other side's job);
  - currencies without a statement on the network (MYR at the central bank of Malaysia, GBP at CHAPS).
