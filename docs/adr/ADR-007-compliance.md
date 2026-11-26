# ADR-007: Compliance — sanctions screening, cases and four-eyes release

- **Status:** Accepted
- **Date:** 2026-11-26
- **Milestone:** M6
- **Amends:** ADR-003 §1 (HELD_COMPLIANCE is now real, incoming release paths); ADR-006 §6 (the tracker reads CdtrAgt)

## Context

Until M6 the payment hub "screened" through a pass-through that said so in the audit trail. Real banks
may not move money for a sanctioned party: every payment is screened against the sanctions lists that
apply to the bank, and a possible match stops the payment until a person decides. A decision that lets
a payment go must be explainable to a regulator years later: what was screened, against which version
of which list, what matched, who decided, and why.

M6 adds the `compliance` module: simulated sanctions lists, a screening engine, cases for hits, and
decisions with four-eyes release. The hub holds payments while their case is open.

## Decisions

### 1. Every bank screens every payment, before any money moves

- **Outgoing:** after validation and before the funds reservation. A held payment has reserved
  nothing, so a rejection needs no unwinding.
- **Incoming:** after validation and before anything is booked. This applies in every role (ADR-006 §2):
  - creditor's bank;
  - intermediary;
  - cover correspondent.
- **No bank relies on another's screening.** A correspondent that let a payment through because the
  debtor's bank had cleared it would be breaking the sanctions law of its own jurisdiction. So a payment
  can be held at Bank A, then at Bank B, then at Bank C, each releasing it on its own. `NetworkE2EIT`
  shows exactly that.
- **Cover payments:** the cover correspondent screens the parties of the underlying customer payment
  (`UndrlygCstmrCdtTrf/Dbtr/Nm`, `…/Cdtr/Nm`). This is why CBPR+ makes the pacs.009 COV carry them:
  before 2009 a correspondent moving cover money could not see whose money it was moving.

### 2. What is screened

| Item | Field | How |
|---|---|---|
| Debtor's and creditor's names | `Dbtr/Nm`, `Cdtr/Nm` (or under `UndrlygCstmrCdtTrf` for a COV) | fuzzy name match against names and aliases |
| Every bank the message names | `DbtrAgt`, `CdtrAgt`, `InstgAgt`, `InstdAgt`, `InstgRmbrsmntAgt` | exact BIC match on the institution (first 8 characters), so every branch of a listed bank is caught |

**Not screened:**

- **The next agent of an outgoing payment.** It is chosen later, at routing, from our own
  correspondents, and correspondent relationships are vetted when they are opened.
- **Remittance information and addresses.** Our messages carry no addresses, and free-text screening
  is noisy.
- **Countries:** embargo rules are not modelled.
- **A warehoused payment on its execution date:** it is screened once, when it is accepted.

### 3. Sanctions lists are effective-dated reference data

The lists are simulated, and every entry is fictional:

- **Lists:** "OFAC SDN (sim)" and "EU consolidated (sim)".
- **Entries:** names, addresses and registration numbers are invented, and every reference contains "SIM".

They imitate the shape of the real lists, not their content. They follow rule 9 like ADR-004's data:

- **Effective-dated:** an entry is valid from the day it was listed until the day it was delisted,
  `[listed, delisted)`, with an EXCLUDE constraint and an end-dating-only trigger. A delisting
  end-dates the row; nothing is edited or deleted.
- **Screened as of a date:** a payment is screened against the entries in force on the bank's business
  date. The screening records that date and the list snapshots used, e.g. "OFAC 2026-11-20, EU
  2026-11-20".
- **Earlier questions stay answerable.** "Would this payment have hit on 13 March?" has an answer. In
  the seed, Oakridge was listed on 14 March 2026, and Meridian Salt was delisted on 1 October 2025.
- **Shared by all banks:** loaded by Flyway, like the BIC directory. A bank can limit itself to some
  lists (`minipay.compliance.lists`).

### 4. Matching and scoring are explainable

A score runs from 0 to 100, per party and list entry.

1. **Names become comparable words.**
   - Transliterated to A–Z: Müller → MULLER, Tromsø → TROMSO.
   - Upper case, punctuation dropped.
   - Legal forms and articles removed (LTD, GMBH, SDN BHD, FZE, THE …).
   - Words split differently are aligned: NORTHBRIDGE ≈ NORTH BRIDGE.
2. **Each word is matched to its most similar word in the other name** by Jaro–Winkler.
   - Below 0.7 the words are unrelated and count 0: two random words still score about 0.5.
   - **Generic words weigh 0.3.** These are business words such as TRADING, CAPITAL, MARINE and
     SHIPPING. Without this, "Pacific Trading" would match "ORI Trading".
3. **Coverage both ways, combined by harmonic mean.** The name A is covered by B, and the other way
   round; the score is the harmonic mean of the two coverages. Both must be high, so a one-word name
   ("DELTA") does not match a three-word entry.
4. **The best of the entry's name and its aliases is kept.**
5. **The same country is +5.** The party's country comes from its IBAN, or else from its bank's BIC.
   A *different* country never lowers the score: a listed party can bank anywhere. The difference is
   shown to the analyst instead.
6. **An agent's BIC equal to a listed bank's scores 100.**

**Thresholds:**

- **Hold:** a score ≥ `hold-threshold` (75) holds the payment.
- **Four eyes:** a case whose best score is ≥ `four-eyes-threshold` (90) needs two people to release it.

**What the analyst sees.** Each hit stores its explanation with it:

- **The factors:** name similarity, token overlap, country, alias.
- **A side-by-side comparison** of the payment's party and the list entry: aliases, country, address,
  registration or date of birth, entity type, account/agent, listing.
- **The matching words,** highlighted.
- **A rule-based hint,** for example "Likely false positive — name overlap only; the country differs".
  The hint says what to check. It never decides.

The demo's examples score as an analyst would rank them: "Oakridge Interiors Ltd" ≈ OAKRIDGE
INTERIORS TRADING FZE scores 93 (four eyes), and "Halvorsen Shipping AS" ≈ HALVORSEN MARITIME AS
scores 77.

### 5. A screening is evidence, written once

- **What is stored:** for each payment, one `screening` row with:
  - what was screened (every name and BIC, as JSON);
  - the date and list snapshots;
  - the threshold in force;
  - the best score and the outcome.
- **Hits:** `screening_hit` rows point at the exact version of the list entry that matched.
- **Append-only.**
- **Re-runs read it back.** A re-run of the step after a crash reads the stored screening, and so does
  a second worker racing on it (`ON CONFLICT (payment_id) DO NOTHING`, then read). A held payment can
  never be cleared by running the step again, even if the input or the list changed in between.

### 6. Cases and decisions

A screening with hits opens one case:

```
OPEN ──RELEASE (best score < 90)─────────────────────→ RELEASED
 │  └─RELEASE (best score ≥ 90)→ PENDING_APPROVAL ─APPROVE (someone else)→ RELEASED
 │                                 ├─DECLINE (someone else) → OPEN
 │                                 └─WITHDRAW (the releaser) → OPEN
 ├──REJECT ───────────────────────────────────────────→ REJECTED
 └──ESCALATE → ESCALATED (to the MLRO; decided like OPEN)
```

- **Reason and comment.** Each action needs its reason:
  - RELEASE: false positive;
  - REJECT: true match;
  - ESCALATE: insufficient information.

  Every decision also needs a comment of 20–500 characters. The comment is the audit trail, so "ok"
  is not enough.
- **Four eyes applies to releases only.** Letting a possible match go is the risky direction, and the
  second approver is asked for only there. Rejecting is the conservative outcome, so one person
  may reject.
- **The database enforces it too.** A trigger refuses an APPROVE by the person who released, so no
  code path can bypass it. Decisions are append-only, a closed case cannot be changed, and a case's
  status moves only together with a decision.
- **Concurrent decisions take turns.** A decision locks the case row (`FOR UPDATE`), checks that the
  action is possible in the current status, and appends the decision. Two officers releasing the same
  case at once: one release is recorded, and the other is told the case is already waiting for
  approval. This is proved by mutation: without the lock, both releases are recorded, both "from OPEN".
- **Idempotency-Key** on every decision (rule 5). The key is stored with the decision, so a replay
  returns the case and a different request under the same key gets a 422.
- **Who decides:** the simulator has no login. The console picks a (fictional) member of the bank's
  staff, and the decision records that user id. In a bank this would be the authenticated user, and
  the MLRO role would be enforced.

### 7. The payment waits in HELD_COMPLIANCE, and the decision resumes it

- **Nothing moves while held:**
  - outgoing: nothing reserved;
  - incoming: nothing booked, and no answer sent to the sender yet.
- **Released:** the payment continues exactly where screening would have taken it. Outgoing, that is
  the funds reservation; incoming, it is credit, forward, AWAITING_COVER or the cover leg.
  - The history entry of that next status starts with the release, for example "Compliance: released
    by n.rahman, approved by j.tan (false positive): “…”".
  - Its actor is `compliance/n.rahman+j.tan`.
- **Rejected:** REJECTED with **RR04** (Regulatory Reason). Incoming, the sender gets a pacs.002 RJCT
  RR04 whose AddtlInf says only "Regulatory reason". Telling the sender, and through it the debtor,
  that the payment matched a sanctions list could tip off the listed party, so the details stay in our
  case file. An intermediary's RJCT travels back and is reversed hop by hop (ADR-006 §3).
- **How the hub learns of the decision:**
  - Compliance publishes `ComplianceDecisionMade` when a decision closes a case. It does this after the
    commit and outside any transaction.
  - The hub listens and resumes the payment in its own transactions, as always; the ledger is never
    joined to compliance's transaction.
  - If resuming fails, or the process dies between commit and event, the recovery sweeper finds the
    stale HELD_COMPLIANCE payment and resumes it from the committed decision. The decision, not the
    event, is the source of truth. This is proved by mutation: without HELD_COMPLIANCE among the
    sweeper's statuses, a released payment stays held for ever.
- **Compliance depends only on `common`.** The hub calls it; it never calls the hub, ledger or network
  (ArchUnit).

### 8. State machine changes (amends ADR-003 §1)

- **Incoming HELD_COMPLIANCE** may now go to SETTLED, SENT, AWAITING_COVER or REJECTED: the same as
  SCREENED, plus the rejection.
- **Screening is VALIDATED → SCREENED → HELD_COMPLIANCE in both directions.** Incoming screening used
  to go from VALIDATED straight to HELD_COMPLIANCE, which the table did not allow. The pass-through
  never produced a hit, so nothing had noticed.
- **HELD_COMPLIANCE never goes back to SCREENED.**

### 9. The tracker reads the creditor's bank from the pacs.008 (amends ADR-006 §6)

- **The bug:** the tracker took the creditor's bank to be the receiver of the last pacs.008. That is
  right once the payment has arrived, but wrong while it waits at an intermediary. A payment held at
  Bank B looked like a direct payment to Bank B.
- **The fix:**
  - The tracker now records `CdtrAgt` from each pacs.008 and derives the creditor's bank and the method
    from it.
  - The Payment route card shows the creditor's bank as "not reached yet".

## Consequences

- Every payment, in every role, at every bank, is screened against dated lists. The record of what
  was screened and why it was let through can be read back.
- A held payment costs nothing to reject: nothing was reserved or booked.
- **Not modelled:**
  - **Blocking.** OFAC requires the property of a listed party to be *blocked*: frozen in a blocked
    account and reported within 10 business days. We reject instead.
  - **Telling the sender that a payment is pending.** We do not send a gpi pending status.
  - **Requests for information to other banks** (camt.026 / MT199).
  - **Re-screening:**
    - of payments in flight when a list changes;
    - of warehoused payments at execution.
  - **Loading lists through an API.**
  - **Other screening:**
    - addresses, remittance information and countries (embargoes);
    - PEPs and adverse media;
    - transaction monitoring (AML), which is a different discipline from sanctions screening.
  - **Real authentication and roles.**
