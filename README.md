# MiniPay

A simulated cross-border payment network, built as a learning project. Three banks exchange
ISO 20022 messages, and each runs its own ledger, payment hub, reference data, compliance,
settlement and reconciliation.

| Bank | City | BIC | Role | Port | Database |
|---|---|---|---|---|---|
| Bank A | Kuala Lumpur | `MNPAMYKLXXX` | Originating bank | 8081 | `localhost:5433/bank_a` |
| Bank B | Frankfurt | `MNPBDEFFXXX` | EUR correspondent | 8082 | `localhost:5434/bank_b` |
| Bank C | London | `MNPCGB2LXXX` | Beneficiary bank (GBP) | 8083 | `localhost:5435/bank_c` |

The banks, BICs, IBANs and customers are all fictional. BICs and IBANs have valid formats (IBAN check digits included).

**Status:** M0 (foundation), M1 (ledger) and M2 (payment hub) are done. See the [delivery plan](#milestones).

## Repository layout

```
docs/adr/              Architecture Decision Records (ADR-001 architecture, 002 ledger, 003 payment hub, 004 reference data)
design/                Claude Design export of the ops console (UI reference only)
docker-compose.yml     3 × PostgreSQL 16 (one per bank) + Kafka (KRaft)
backend/               Maven multi-module, Java 21, Spring Boot 3.5
  common/              Money, ISO 4217 minor units, UETR, BIC — no framework code
  ledger/              Double-entry ledger (M1)
  payment-hub/          Payment lifecycle, state machine, idempotent API, outbox → Kafka (M2)
  refdata/             Effective-dated BIC directory, IBAN registry, calendars, cut-offs, routing (M3)
  messaging/ compliance/ settlement/ recon/ network-simulator/
                       Placeholders for M4–M8
  bank-app/            Spring Boot app assembling the modules; run with profile bank-a|bank-b|bank-c
frontend/ops-console/  React + TypeScript + Vite + Tailwind + TanStack Query/Table
```

## Conventions

These rules are non-negotiable. The reasoning behind them is in [ADR-001](docs/adr/ADR-001-architecture.md) and [ADR-002](docs/adr/ADR-002-ledger-design.md).

1. **Money** is `Money` (a `BigDecimal` plus an ISO 4217 currency), at the currency's own number of decimals: JPY 0, EUR 2, KWD 3.
   - Input with too many decimals is rejected; computed amounts round HALF_EVEN.
   - Never use `double` or `float`. JSON carries amounts as strings.
2. **Postings are append-only.** The database rejects UPDATE and DELETE; corrections are REVERSAL entries.
3. **Every journal entry balances** per currency (and per balance type). This is checked in the domain *and* by a deferred constraint trigger.
4. **Balances change in the same transaction as their postings.** Accounts are locked in ascending id order, so transactions never deadlock.
5. **Every write API needs an `Idempotency-Key` header.**
   - The same key with the same body replays the original result (200 + `Idempotent-Replayed: true`).
   - The same key with a different body is **422**.
6. **No direct Kafka publishing from business code.** Use the transactional outbox and its relay; ArchUnit enforces this.
7. **Every payment status change is validated by a state machine and recorded in history.**
8. **Raw messages are stored exactly as received or sent**, alongside the parsed fields (M4).
9. **Reference data is effective-dated.** Every lookup takes a date; rows are end-dated, never edited or deleted.
10. **Timestamps are `TIMESTAMPTZ` in UTC; value dates are `DATE`.** "Today" follows the bank's time zone; a cut-off is wall-clock time in its settlement scheme's time zone (T2: Frankfurt).
11. **Real ISO codes:** reason codes (AM04, AC01 …), charge bearer (DEBT/CRED/SHAR), valid-format BICs and IBANs.
12. **Module boundaries:**
    - Modules talk through `com.minipay.<module>.api` only; `internal` packages are private (ArchUnit).
    - Each module owns one Postgres schema and runs its own Flyway migrations.
13. **Tests come with the code.** Integration tests use Testcontainers (real PostgreSQL), never H2.

## Running it

**Prerequisites:** Java 21, Docker, and Node 20 or later. The Maven wrapper (`./mvnw`) downloads Maven itself.

```bash
# 1. Infrastructure: three Postgres databases and Kafka (payment events)
docker compose up -d

# 2. Build and run all tests: unit + Testcontainers integration tests + ArchUnit
cd backend
./mvnw verify

# 3. Run Bank A (install once so bank-app can resolve the sibling modules)
./mvnw install -DskipTests
./mvnw -pl bank-app spring-boot:run -Dspring-boot.run.profiles=bank-a
#   or: java -jar bank-app/target/bank-app-0.1.0-SNAPSHOT.jar --spring.profiles.active=bank-a
#   (after changing another module, rebuild the jar with `./mvnw -pl bank-app clean package -DskipTests`:
#    Spring Boot only repackages it when bank-app itself changed, so it can keep stale module jars)
#   Banks B and C: the same command with bank-b / bank-c, in other terminals.

# 4. Ops console (another terminal)
cd frontend/ops-console
npm install
npm run dev        # http://localhost:5173
```

On first start each bank **seeds** its chart of accounts and opening balances through the
ledger API, and its routing table through the reference data import API. The seed lists are in
`bank-app/src/main/resources/application-bank-*.yml`. Seeding is idempotent, so restarting changes
nothing. The shared reference data (BIC directory, IBAN registry, calendars) comes with the refdata
module's Flyway migrations.

- **API docs:** http://localhost:8081/swagger-ui.html
- **Health:** http://localhost:8081/actuator/health
- **Start from scratch:** `docker compose down -v` deletes all three databases.

### Sample calls (Bank A)

Account ids are generated, so look them up by account number first (requires `jq`).

The idempotency keys below (`demo-*`) are fixed. If you run the script a second time, the ledger
does exactly what it should: identical requests are replayed and nothing is posted twice, and a
key reused for a different hold (step 4) gets a 422. To see fresh postings, change the keys or
reset the databases with `docker compose down -v`.

```bash
A=localhost:8081/api/ledger
acct() { curl -s "$A/accounts" | jq -r --arg n "$1" '.[] | select(.accountNumber==$n) | .id'; }
CUS=$(acct 514087234412)     # Selangor Precision Components Sdn Bhd (EUR customer)
NOSTRO=$(acct 1101-EUR-MNPB) # our EUR nostro at Bank B
FEES=$(acct 4020-FEE-EUR)

# All accounts with booked / reserved / available
curl -s "$A/accounts" | jq '.[] | {accountNumber, type, booked: .booked.amount, available: .available.amount}'

# 1. Reserve funds for an outgoing payment (payment state FUNDS_RESERVED)
curl -s -X POST $A/reservations -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-hold-1' \
  -d "{\"accountId\": $CUS, \"amount\": {\"amount\": \"48250.00\", \"currency\": \"EUR\"}, \"reason\": \"INV-HF-2026-1187\"}" | tee /tmp/hold.json; echo
HOLD=$(jq -r .id /tmp/hold.json)

# 2. Send it again: same key + same body → 200 and Idempotent-Replayed: true, nothing new is posted
curl -si -X POST $A/reservations -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-hold-1' \
  -d "{\"accountId\": $CUS, \"amount\": {\"amount\": \"48250\", \"currency\": \"EUR\"}, \"reason\": \"INV-HF-2026-1187\"}" | grep -i replayed

# 3. Same key, different body → 422 IDEMPOTENCY_KEY_REUSED
curl -s -X POST $A/reservations -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-hold-1' \
  -d "{\"accountId\": $CUS, \"amount\": {\"amount\": \"1.00\", \"currency\": \"EUR\"}}" | jq

# 4. Book it: release the hold + debit the customer, credit our nostro (one transaction)
curl -s -X POST $A/reservations/$HOLD/settle -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-settle-1' \
  -d "{\"creditAccountId\": $NOSTRO, \"narrative\": \"To Harlow & Finch Engineering Ltd\"}" | jq .status

# 5. Charge a fee, then reverse it (the original stays; a mirror entry is added)
FEE=$(curl -s -X POST $A/journal-entries -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-fee-1' \
  -d "{\"entryType\": \"FEE\", \"narrative\": \"Outward transfer fee (SHAR)\", \"postings\": [
        {\"accountId\": $CUS,  \"direction\": \"D\", \"amount\": {\"amount\": \"20.00\", \"currency\": \"EUR\"}},
        {\"accountId\": $FEES, \"direction\": \"C\", \"amount\": {\"amount\": \"20.00\", \"currency\": \"EUR\"}}]}" | jq -r .id)
curl -s -X POST $A/journal-entries/$FEE/reversal -H 'Idempotency-Key: demo-rev-1' | jq '{entryType, reversesEntryId}'

# 6. Try to overspend → 422 with ISO reason code AM04 (InsufficientFunds)
curl -s -X POST $A/reservations -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-big-1' \
  -d "{\"accountId\": $CUS, \"amount\": {\"amount\": \"99999999.00\", \"currency\": \"EUR\"}}" | jq '{status, reasonCode, detail}'

# 7. Today's statement (business date in Asia/Kuala_Lumpur) and the end-of-day integrity check
curl -s "$A/accounts/$CUS/statement" | jq '{openingBooked, closingBooked, lines: [.lines[] | {entryType, debit, credit, balance}]}'
curl -s $A/integrity | jq '{ok, journalEntries, postings, trialBalance}'
```

Then open http://localhost:5173/accounts?account=514087234412 to see the same activity in the ops console.

### Sample calls: payments (Bank A)

```bash
P=localhost:8081/api/payments

# 1. Send EUR 48,250 to Harlow & Finch in London. Bank A has no EUR account with Bank C, so routing
#    rule R-003 sends it serially via its EUR correspondent, Bank B. Our EUR 20 fee is charged on top (SHAR).
curl -s -X POST $P -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-pay-1' -d '{
  "debtorAccount": "514087234412", "creditorName": "Harlow & Finch Engineering Ltd",
  "creditorAccount": "GB67 MNPC 4005 1271 5200 93", "creditorAgentBic": "MNPCGB2LXXX",
  "amount": {"amount": "48250.00", "currency": "EUR"}, "chargeBearer": "SHAR",
  "endToEndId": "INV-HF-2026-1187", "remittanceInformation": "INV-HF-2026-1187 CNC spindle assemblies"}' \
  | tee /tmp/pay.json | jq '{uetr, status, instructionId, instructedAgentBic, routingRuleId, chargesAmount, valueDate}'
UETR=$(jq -r .uetr /tmp/pay.json); ID=$(jq -r .id /tmp/pay.json)

# 2. Its audit trail and the ledger entries it caused (hold, release, principal, fee)
curl -s $P/$UETR | jq '.history[] | {to, actor, detail}'
curl -s "localhost:8081/api/ledger/journal-entries?paymentId=$ID" | jq '.[] | {entryType, narrative}'

# 3. Simulate the network (until M5): pacs.002 ACCP, settlement, then a pacs.004 return —
#    AC04 "closed account", with EUR 25 of intermediary charges kept.
for t in ACCEPTED SETTLED; do
  curl -s -X POST $P/$UETR/network-events -H 'Content-Type: application/json' \
    -H "Idempotency-Key: demo-$t-1" -d "{\"type\": \"$t\"}" | jq -r .status
done
curl -s -X POST $P/$UETR/network-events -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-ret-1' \
  -d '{"type": "RETURNED", "reasonCode": "AC04", "returnedAmount": {"amount": "48225.00", "currency": "EUR"}}' \
  | jq '{status, rejectionReason}'

# 4. Illegal transition → 409 (a returned payment cannot be acknowledged)
curl -s -X POST $P/$UETR/network-events -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-ack-2' \
  -d '{"type": "ACCEPTED"}' | jq '{status, code, detail}'

# 5. A payment the debtor cannot afford is stored and REJECTED with AM04 — not lost, not half-done
curl -s -X POST $P -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-pay-2' -d '{
  "debtorAccount": "514066120983", "creditorName": "Rheinwerk Logistik GmbH",
  "creditorAccount": "DE34500100600533190042", "creditorAgentBic": "MNPBDEFFXXX",
  "amount": {"amount": "9310.75", "currency": "EUR"}, "chargeBearer": "SHAR"}' | jq '{status, rejectionReason}'

# 6. The events on Kafka (one per status change, keyed by UETR, with an event-id header)
docker exec minipay-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic minipay.MNPAMYKLXXX.payment-events --from-beginning --timeout-ms 10000 --property print.headers=true
```

In the console: http://localhost:5173/payments, then open a payment to see its lifecycle, ledger
postings and audit log, or use **Create payment** with the scenario presets.

### Sample calls: reference data (Bank A)

Run against a fresh database (`docker compose down -v` first), because step 5 publishes a release.
Its key makes a second run replay, but after a week the dates in it are in the past.

```bash
R=localhost:8081/api/refdata

# 1. Current data version, and the BIC directory as of a date (try a date before 2026-10-02:
#    the Berlin branch still has its old name)
curl -s $R | jq '{dataVersion, institutions, routingRules}'
curl -s "$R/bics/MNPBDEFF100/history" | jq '.[] | {institutionName, validFrom, validTo}'
curl -s "$R/bics/MNPXNL2AXXX?date=2027-01-04" | jq '{status, detail}'   # gone after 2026-12-31 → 404

# 2. IBAN validation reports every check (one wrong digit → mod-97 fails, expected digits shown)
curl -s $R/iban/GB97MNPC60161331926819 | jq '{valid, checks: [.checks[] | "\(.outcome) \(.name): \(.detail)"]}'

# 3. Value dates: TARGET is closed on 25 and 26 December, so Christmas Day rolls to Monday 28th.
#    Cut-offs are per scheme, in the scheme's own time zone (shown here as UTC instants).
curl -s "$R/value-date?currency=EUR&date=2026-12-25" | jq '{valueDate, reasons}'
curl -s $R/cutoffs | jq '.[] | {schemeName, currency, settlementDate, cutoff}'

# 4. Routing is evaluated on the value date: Paris goes serial until 30 Nov, by cover from 1 Dec
for d in 2026-11-30 2026-12-01; do
  curl -s "$R/routes?bic=MNPEFRPPXXX&currency=EUR&date=$d" \
    | jq -c '{valueDate, rule: .route.rule.ruleId, method: .route.rule.method, instructedAgent: .route.instructedAgentBic}'
done

# 5. Publish a change as a new release: Belgium moves from serial to cover in a week's time.
#    The superseded rule R-004 is end-dated the day before, in the same transaction.
FROM=$(date -d '+7 days' +%F 2>/dev/null || date -v+7d +%F)
curl -s -X POST $R/routing-rules -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-rule-1' -d "{
  \"destination\": \"MNPDBEBB*\", \"currency\": \"EUR\", \"method\": \"COVER\", \"nextHopBic\": \"MNPBDEFFXXX\",
  \"settlementAccount\": \"1101-EUR-MNPB\", \"priority\": 20, \"effectiveFrom\": \"$FROM\", \"supersedes\": \"R-004\",
  \"publishedBy\": \"r.lim\", \"approvedBy\": \"a.wong\", \"description\": \"Belgium by cover\"}" | jq '{ruleId, effectiveFrom, releaseVersion}'
curl -s $R/routing-rules | jq -c '.[] | select(.destination == "MNPDBEBB*") | {ruleId, method, effectiveFrom, effectiveTo}'

# 6. Four-eyes: the author cannot approve their own change → 422
curl -s -X POST $R/routing-rules -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-rule-2' -d "{
  \"destination\": \"MNPDBEBB*\", \"currency\": \"EUR\", \"method\": \"SERIAL\", \"nextHopBic\": \"MNPBDEFFXXX\",
  \"settlementAccount\": \"1101-EUR-MNPB\", \"priority\": 30, \"effectiveFrom\": \"$FROM\",
  \"publishedBy\": \"r.lim\", \"approvedBy\": \"r.lim\", \"description\": \"Self-approved\"}" | jq '{status, code}'

# 7. The payment hub uses all of it: a Saturday request executes on Monday; an IBAN of Bank C sent
#    to a Belgian bank is rejected AC03
P=localhost:8081/api/payments
curl -s -X POST $P -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-pay-sat' -d '{
  "debtorAccount": "514087234412", "creditorName": "Atelier Lumière SARL",
  "creditorAccount": "FR76 3000 6000 0112 3456 7890 189", "creditorAgentBic": "MNPEFRPPXXX",
  "amount": {"amount": "1250.00", "currency": "EUR"}, "chargeBearer": "SHAR", "requestedExecutionDate": "2026-12-05"}' \
  | jq '{status, requestedExecutionDate, executionDate}'
curl -s -X POST $P -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-pay-ac03' -d '{
  "debtorAccount": "514087234412", "creditorName": "Brouwerij De Klok NV",
  "creditorAccount": "GB67 MNPC 4005 1271 5200 93", "creditorAgentBic": "MNPDBEBBXXX",
  "amount": {"amount": "500.00", "currency": "EUR"}, "chargeBearer": "SHAR"}' | jq '{status, rejectionReason, rejectionDetail}'
```

In the console: http://localhost:5173/reference-data. Change **As of** to see the directory, the IBAN
registry and the routing table as they were, or will be, on another date. **Overview** shows today's
figures and each currency's cut-off in the bank's local time.

## What you should learn from M1 (ledger)

**Double entry, and why a customer debit is a decrease.** Every event is recorded twice: once
on each side. Each account type has a *normal side*:
- A customer account is a **liability**: it is money the bank owes. A debit lowers it.
- A **nostro** ("ours, with you") is our money at another bank, an **asset**. A debit raises it.

So "pay EUR 100 abroad" is *debit customer, credit nostro*. Both balances go down, and the books
still balance. Look at the statement footnotes in the console, and at `AccountType.normalSide()`.

**Nostro and vostro are two views of one relationship.** Bank A's nostro `1101-EUR-MNPB` and
Bank B's vostro for Bank A (`DE87…2003`) hold the same EUR 2,899,613.17. Every cross-border
payment moves both, and checking that they still agree is nostro reconciliation (M8).

**Booked, reserved, available.**
- *Booked* is what has really happened.
- *Reserved* is money promised to payments in flight.
- *Available = booked − reserved* is what a new payment may use.

A hold is recorded as balanced postings on a separate RESERVED balance, against a per-currency
holds control account. It is auditable, yet it never appears on the customer's statement.
Settling a hold is *release + book* in one transaction, so available does not move a second
time.

**History is immutable.** Postings can't be updated or deleted, and the database enforces it.
A mistake is fixed with a reversal entry that points to the original, so auditors see both the
mistake and the correction. Balances, on the other hand, are derived data: the end-of-day check
recomputes them from postings and proves they match.

**Correctness under concurrency is designed, not hoped for.**
- Locking the balance rows with `FOR UPDATE` stops two debits from reading the same balance and both succeeding: 100 parallel debits of EUR 10 against EUR 500 give exactly 50 successes.
- Always locking in account-id order makes A→B and B→A wait instead of deadlocking.

Both properties have tests, and those tests fail if the locking is broken.

**Retries are normal; double-posting is not.** Clients time out and resend. The
Idempotency-Key, plus a hash of the request, turns a resend into a replay of the original
answer. Reusing a key for a *different* request is a client bug, so it gets a 422.

**Refusals speak ISO 20022.** Refusals return the reason codes a bank would put in a pacs.002:
AM04 (insufficient funds), AC01 (unknown account), AC04 (closed), AC06 (blocked), AM03
(currency not allowed). When the payment hub arrives in M2, these flow straight into payment
rejections.

**Time is two different things.** Booking time is a UTC timestamp. The *value date* and the
*business date* are calendar dates in the bank's time zone. Bank A's "today" ends at midnight
in Kuala Lumpur, not at midnight UTC.

## What you should learn from M2 (payment hub)

**A payment is a state machine, and the states guard money.** RECEIVED → VALIDATED → SCREENED →
FUNDS_RESERVED → SENT → ACKED → SETTLED. Every jump is checked against one table; an illegal one
throws. A payment can be REJECTED only *before* it leaves the bank. Afterwards the only way back is
a RETURN (pacs.004), and only once it has settled.

**Rejected is a result, not an error.** A well-formed payment with an unknown account, the wrong
currency or too little money is *stored* and REJECTED with an ISO reason code (AC01, AM03, AM04…).
The bank can then tell the customer exactly why. Only malformed input gets an HTTP 400.

**Three amounts, not one.** The *instructed* amount is what the customer asked for. The *interbank
settlement* amount is what moves between banks. Our *charges* are what we keep. The charge bearer
(DEBT, SHAR or CRED) decides how they relate. Under SHAR, the debtor pays amount + our fee. Under
CRED, our fee comes out of what is sent. A return can bring back less than was sent, because banks
on the route keep their charges.

**Correspondent banking in one routing rule.** Bank A holds no EUR account at Bank C, so a EUR
payment to London goes to Bank A's EUR correspondent, Bank B, and is booked against the nostro
there. That is the serial method. If the creditor's bank holds a vostro with us, we simply credit
it.

**Crash safety comes from idempotent steps.** Each step calls the ledger with a key derived from
the payment and the step (`pay:{id}:reserve`). If the process dies between "ledger done" and
"status saved", re-running the step replays the ledger result instead of booking twice. Ten workers
racing on one payment produce exactly one hold, one booking and one fee.

**The transactional outbox.** "Update the database, then publish to Kafka" loses messages when the
process dies in between. Instead, the event is written to an outbox table *in the same transaction*
as the status change, and a relay publishes it afterwards. Delivery is therefore *at least once*: a
crash after Kafka acknowledged but before the row was marked sends the event again. That is why
every event carries an `event-id`, and why consumers must de-duplicate.

**Optimistic and pessimistic locking, side by side.** One payment rarely sees two writers, so it
uses `@Version`, and the loser of a rare race just retries. One ledger account can see hundreds of
writers, so the ledger locks rows. Pick the lock for the contention you actually have.

## What you should learn from M3 (reference data)

**Facts have dates.** A bank is renamed on 2 October, a route changes on 1 December, a BIC disappears
at year end. So every row is valid over a date range, and every question is asked "as of" a date.
Nothing is overwritten. A change is a new row, and the old one is end-dated. PostgreSQL enforces both
halves: an `EXCLUDE` constraint makes two versions on the same day impossible, and a trigger refuses
any update other than shortening a validity.

**The date that matters is the day the money moves.** A payment warehoused for next week is checked
against next week's directory and routed by next week's rules. Bank A pays Paris serially via Bank B
until 30 November, and by cover from 1 December. The route a payment took is stored with it (R-005,
R-006), so it can be explained later.

**Format-valid is not the same as valid.** `MNPZDEFFXXX` is a perfectly shaped BIC of a bank that does
not exist (RC01). `GB97 MNPC…` fails mod-97 by one digit (AC03). A valid IBAN of a London bank, sent
to a Brussels bank, is rejected too (AC03): one of the two is wrong, and guessing could pay the wrong
bank.

**Business days and cut-offs belong to the currency.** A EUR payment needs a TARGET business day
before T2's cut-off, and 17:00 Frankfurt time is 00:00 or 23:00 in Kuala Lumpur depending on the
season. Store the cut-off in the scheme's time zone and convert at the moment of use. Requested dates
on a weekend or holiday roll forward to the next business day. A payment that misses the cut-off still
leaves now, with the next value date.

**Routing is a decision, and decisions are explicit.** The lowest priority wins. A rule that cannot be
used (receiver not connected, settlement account closed) rejects the payment CNOR, instead of quietly
falling through to some other route.

**Retries must ask the same question.** The ledger booking includes the value date. A retry after the
cut-off must reuse the date already booked, or the ledger sees a different request under the same
idempotency key and refuses it.

**Reference data is governed.** It arrives in releases, each published by one person and approved by
another (four eyes; the database refuses self-approval), and is never back-dated.

## Milestones

| | Milestone | Status |
|---|---|---|
| M0 | Foundation: modules, Compose, Flyway, Money/UETR/BIC, ADR-001, CI | ✅ |
| M1 | Ledger: double entry, append-only, holds, locking, idempotency, integrity, Accounts screen | ✅ |
| M2 | Payment hub: idempotent payment API, state machine, history, outbox + Kafka relay, crash safety, Payments screens | ✅ |
| M3 | Reference data: effective-dated BIC directory, IBAN registry, routing rules, holidays, cut-offs; Overview and Reference data screens | ✅ |
| M4 | Messaging: pacs.008/002/004, camt.053, MT103 ↔ pacs.008 | |
| M5 | Network simulator, 3 banks, UETR tracker (serial and cover) | |
| M6 | Compliance: sanctions screening, 4-eyes release | |
| M7 | Settlement: RTGS, deferred net settlement, instant | |
| M8 | Reconciliation: camt.053 matching, breaks | |
