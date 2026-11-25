# ADR-004: Reference data — effective dating, routing, calendars and cut-offs

- **Status:** Accepted
- **Date:** 2026-11-25
- **Milestone:** M3
- **Amends:** ADR-003 §5 (validation), §7 (interim routing), §8 (warehousing)

## Context

Until M2 the payment hub checked only formats. A BIC with the right shape was accepted even if no
such bank existed. Any IBAN that passed mod-97 was accepted. Routing was hard-coded ("a vostro, else
a nostro, else our only correspondent"), and every day was a business day.

Real payment engines answer those questions from **reference data**:

- a BIC directory;
- an IBAN registry with a map from IBAN bank code to BIC;
- settlement calendars and cut-off times;
- the bank's own routing table.

Reference data has one property that ordinary configuration does not: **it changes on a date**. A
bank is renamed on 2 October. A routing change takes effect on 1 December. A BIC disappears at
year end. Next year's holidays are published in October. A payment warehoused today for next week
must be checked against next week's facts, and an investigation into last month's payment needs
last month's facts.

## Decisions

### 1. Everything is effective-dated, and history is never rewritten

Every row carries a PostgreSQL `daterange` called `valid`, half-open: `[from, to)`. A lookup asks "is
this row in force on that date?" with `valid @> :date`.

- **One version per day.** An `EXCLUDE USING gist (key WITH =, valid WITH &&)` constraint makes two
  overlapping versions of the same key impossible: two names for one BIC, or two IBAN formats for one
  country. Plain columns can share that GiST index with the range only through the `btree_gist`
  extension, so the migration installs it.
- **End-dating only.** A trigger allows exactly one change to an existing row: shortening its
  validity, so a row that was open-ended, or ran later, now ends earlier. Any other UPDATE, and every
  DELETE, is refused. Releases, holidays and idempotency records are fully append-only. A change is
  always a new row, and the old row is end-dated. This is the reference-data twin of the ledger's
  "corrections are reversals" (ADR-002).
- **No back-dating.** A new routing rule cannot start before today. Payments routed on earlier days
  used the rules in force then; changing those days afterwards would make history wrong.
- **Inclusive in the API.** The API and the UI show "effective to" as the **last day inclusive**
  (`upper − 1`), which is how operators read it. The half-open range exists only in SQL, and only the
  repository converts between them.

### 2. Releases and the four-eyes principle

Reference data arrives as **releases**, such as `v2026.11.3`. Each release records who published it
and who approved it, and every row records the release that introduced it. The database refuses a
release whose approver is its publisher (`CHECK (approved_by <> published_by)`), and the API reports
that as `FOUR_EYES_VIOLATION`.

Publishing a routing rule through the API creates a new release (`POST /api/refdata/routing-rules`).
The `Idempotency-Key` rules apply as for every other write (rule 5). If the request names a rule it
`supersedes`, that rule is end-dated to the day before the new one starts, in the same transaction,
so there is no gap and no overlap. Rule ids and release numbers are "current maximum + 1"; a
`SHARE ROW EXCLUSIVE` table lock serialises publishers. The concurrency test fails without that lock.

Not modelled: a draft → approve workflow with two separate actions, and checksums of release files.

### 3. The BIC directory, and who we can actually reach

The directory lists each institution's BIC, name, city, the currencies it clears, and its
**connectivity** from our point of view:

| Connectivity | Meaning | Can receive our message? |
|---|---|---|
| CONNECTED | Live on the network, and our relationship (RMA) is authorised | Yes |
| PENDING_RMA | Live, but no authorised relationship with us yet | No, only via another bank |
| NOT_CONNECTED | Not on the network at all (a "non-connected BIC") | No, only via a correspondent |

Validation requires the creditor agent to **exist** in the directory on the execution date; otherwise
the payment is rejected **RC01**. Routing requires whoever **receives our message** to be CONNECTED
(§6). So a non-connected bank can still be paid, by a serial route through a connected correspondent.

The seed includes examples of each case:

- a branch renamed on 2026-10-02 (both names stay on record);
- a BIC that leaves the directory after 2026-12-31;
- a bank whose RMA is still pending.

### 4. IBANs: the registry, and which bank an IBAN belongs to

`common.Iban` holds only the arithmetic that is the same in every country: normalisation, the
character set and ISO 7064 mod-97. Everything that differs per country is reference data in
`refdata.iban_format`: the length, and the BBAN structure in the registry's notation (`4!a 6!n 8!n`
for GB). `refdata.iban_bank_id` maps a national bank code to a BIC, which is what SWIFTRef's IBAN Plus
directory provides.

Validation reports **every** check, not just the first failure:

1. country supported;
2. character set;
3. length;
4. BBAN structure (each part has the right kind of characters);
5. check digits;
6. bank code known.

A bank code missing from the map is a **warning**, because a perfectly valid IBAN can belong to a
bank the map does not list. National check digits inside the BBAN (Belgium's last two digits,
France's RIB key) are shown as parts but not verified.

The payment hub rejects with **AC03** (invalid creditor account) when:

- the IBAN fails any check;
- the IBAN's bank code maps to a different institution than the creditor agent. One of the two is
  wrong, and we cannot know which; sending anyway risks paying the wrong bank;
- the creditor agent is in an IBAN country but the account is not an IBAN.

Malaysia, the US and Singapore do not use IBANs; plain account numbers are accepted there.

### 5. Calendars, execution date, value date and cut-offs

Each currency has a **settlement scheme**: T2 for EUR, CHAPS for GBP, RENTAS for MYR, Fedwire for
USD. Each scheme has a calendar of non-business days (weekends plus listed holidays) and a
customer-payment **cut-off**. Cut-off times are simulator values modelled on each scheme's published
cut-off for customer payments.

- **The cut-off is a wall-clock time in the scheme's time zone** (T2: 17:00 Europe/Berlin), not in the
  bank's. It is turned into an instant for each date at the moment of use. Daylight saving is
  therefore always right: for Bank A in Kuala Lumpur, T2 closes at 00:00 the next day in winter and
  23:00 in summer. Storing a converted time would be wrong for half the year. "Today", business dates
  and warehousing still use the bank's time zone (rule 10). Only the cut-off belongs to the scheme.
- **Execution date** (validation, stored on the payment): the requested date, rolled forward to the
  next business day of the currency's calendar. This is the "following" convention, the one banks
  apply to a customer's requested execution date. A payment requested for Saturday executes on
  Monday. Warehousing waits for this date, not the requested one.
- **Value date** (at send time): the first business day on or after the execution date whose cut-off
  has not yet passed. A payment sent after the cut-off still leaves now, with tomorrow's value date
  (forward-valued); it is not held back. The history row says why it moved, e.g. "RENTAS cut-off 18:00
  Asia/Kuala_Lumpur on 2026-11-24 has passed".
- A currency with **no scheme** (e.g. JPY here) is one we do not settle. The payment is rejected
  **AM03** (not allowed currency) at validation, before any hold.

Holidays seeded:

| Calendar | Holidays |
|---|---|
| TARGET | 2026 and 2027 |
| England & Wales | 2026 and 2027, including substitute days |
| US Federal Reserve | 2026 and 2027. A Saturday holiday is not moved to Friday, because the Fed stays open. |
| Kuala Lumpur | Fixed-date holidays only |

Kuala Lumpur's moveable holidays (Chinese New Year, Hari Raya, Deepavali …) follow lunar or Islamic
calendars and are gazetted year by year. A real bank receives them in each year's calendar release.

### 6. Routing rules

A rule maps (destination, currency) to a method, an optional next hop, our settlement account and a
priority, over a validity range:

- **Destination:** `XXXXXXXX*` for one institution (BIC8, any branch), or `*` for anyone.
- **Methods:**

| Method | Our message (InstdAgt) goes to | We settle on |
|---|---|---|
| DIRECT | the creditor agent | our bilateral account with it (nostro, or its vostro) |
| SERIAL | the next hop, who passes it on | our account with the next hop |
| COVER | the creditor agent (pacs.008), with the money via the next hop (pacs.009 COV, M5) | our account with the next hop |
| RTGS | the creditor agent, through the domestic RTGS | our central bank account |

Selection:

1. Candidates are the rules in the payment's currency that cover the creditor agent.
2. Only rules valid on the **value date** count, not on today: a payment warehoused into next week is
   routed by next week's rules. The seed shows this. Bank A pays Paris serially via Bank B until 30 Nov
   (R-005), and by cover from 1 Dec (R-006).
3. The lowest priority number wins. On equal priority an institution-specific rule beats `*`, then
   the lower rule id wins, so the choice is always deterministic.
4. The winner must be usable: the receiver of our message must be CONNECTED. COVER also needs the
   next hop. An RTGS route needs only the creditor agent to exist.
5. **An unusable winner is not skipped** in favour of the runner-up. A silent fall-through would send
   money by a route nobody chose for that destination. It is reported, and the payment is rejected
   **CNOR**. The same applies if the rule's settlement account does not exist, is not a nostro or
   vostro, is in another currency, or is not active. The hub checks that through `LedgerApi`;
   reference data does not read the ledger.

Routing runs twice:

- **at validation**, on the execution date, so a payment with no route is rejected CNOR **before**
  any funds are held;
- **at send time**, on the value date, so the rule actually used is the one in force then. If it has
  gone, the hold is released and the payment is rejected CNOR.

The chosen rule id is stored on the payment (`routing_rule_id`), so "why did this go via Bank B?"
can be answered later.

Routing tables are bank-specific. Each bank's table is loaded from its profile
(`minipay.seed.routing-rules`) through the same idempotent import API a reference data file would use.
The shared data (directory, registry, calendars) comes from the module's Flyway migrations and is
identical in every bank.

### 7. Crash safety: the value date is part of the booking

The ledger booking at send time includes the value date, and the ledger's idempotency check compares
the whole request (ADR-002 §7). Suppose a worker books the payment and dies before recording SENT,
and the cut-off passes before the retry. If the retry recomputed the value date, it would ask for a
*different* booking under the *same* key. The ledger would refuse it instead of replaying, and the
payment would be stuck.

So before booking, the hub asks the ledger whether this payment's SETTLE entry already exists. If it
does, that entry's value date **is** the decision, and the retry reuses it. `CrashSafetyIT` simulates
exactly this crash; the test fails if the reuse is removed.

### 8. Module shape and persistence

`refdata` follows the ledger's layout: `api` is public and `internal` is private (ArchUnit), with its
own schema and its own Flyway. It uses `JdbcClient` rather than JPA, because range operators (`@>`),
`daterange(...)` and EXCLUDE violations (SQLSTATE 23P01) are plain SQL concepts. The payment hub
depends on `refdata.api`; `refdata` depends on nothing but `common`.

## Consequences

- **The validation step has real answers.** RC01 for unknown banks; AC03 for wrong, mismatched or
  missing IBANs; AM03 for currencies we do not settle; CNOR for no route. Each is decided on the date
  the money will move.
- **Any date can be asked about.** The ops console's Reference data screen has an "as of" date; the
  directory, the IBAN check and route tests all answer for that date.
- **Every change is visible.** Release, author, approver, and the end date of what it replaced. Nothing
  is overwritten.
- **Payments change when and how they go.** Weekend and holiday requests execute on the next
  business day. Late payments are forward-valued. Warehoused payments follow the routing rules of
  their own value date.
- **Simplifications, documented as such:** no draft/approve workflow, simulator cut-off values, only
  the fixed-date holidays for Kuala Lumpur, national check digits not verified, and one cut-off per
  currency (real banks also have cut-offs per channel and per product).
