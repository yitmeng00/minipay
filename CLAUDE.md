# CLAUDE.md

MiniPay is a learning project: three simulated banks exchanging ISO 20022 messages. Correctness and realism of payment concepts
matter more than feature count. Explain payment-domain decisions in code comments and ADRs (`docs/adr/`).

## Commands

```bash
docker compose up -d                                   # 4 × Postgres (5433/5434/5435 banks, 5436 central bank) + Kafka
cd backend && ./mvnw verify                            # unit (*Test) + Testcontainers (*IT) + ArchUnit
./mvnw -pl bank-app clean package -DskipTests          # fat jar goes stale if only other modules changed
./mvnw install -DskipTests && ./mvnw -pl bank-app spring-boot:run -Dspring-boot.run.profiles=bank-a   # :8081
./mvnw -pl bank-app spring-boot:run -Dspring-boot.run.profiles=operator   # the central bank (M7), :8084
cd frontend/ops-console && npm run dev                 # :5173, proxies /bank-a|b|c|operator → 8081|8082|8083|8084
cd frontend/ops-console && npm run build && npm run lint
```

JDK 21 is required (on this machine: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21`). Testcontainers needs Docker running.

## Architecture (see ADR-001, ADR-002)

- `backend/bank-app` assembles all modules; one instance per bank via profiles `bank-a|bank-b|bank-c`, each with its own DB.
- Module = `com.minipay.<module>.api` (public) + `com.minipay.<module>.internal` (private). ArchUnit (`ModuleBoundariesTest`)
  enforces it. Never import another module's `internal`.
- Each module owns one Postgres schema and its own Flyway bean (`db/migration/<schema>`, history table in that schema).
  `spring.flyway.enabled=false`.
- `common` is framework-free (Money, CurrencyUnits, Uetr, Bic, MoneyJacksonModule).
- Ledger uses `JdbcClient` (explicit locking), not JPA. Payment hub uses Spring Data JPA (`@Version`), `ddl-auto=validate`.
- Payment status changes only via `PaymentTransitions.transition(...)` (state machine + history + outbox in one tx).
  Pipeline steps call the ledger OUTSIDE the payment tx with keys `pay:{paymentId}:{step}`; never join transactions.
- Kafka only in `com.minipay.paymenthub.internal.outbox` (relay); consumers de-duplicate by the `event-id` header.
- Refdata (`JdbcClient`): every row has `valid DATERANGE [from,to)` + EXCLUDE (btree_gist); rows are only end-dated
  (trigger), never edited/deleted/back-dated. API shows effective-to inclusive. Shared data = Flyway seed; each bank's
  routing rules = profile `minipay.seed.routing-rules` via the import API.
- Hub validates/routes as of the payment's execution date (requested date → next business day), sends with the value
  date (business day whose cut-off, in the scheme's zone, hasn't passed) and re-routes on it. A re-run reuses the
  value date of an existing SETTLE entry.
- Messaging (Prowide `pw-iso20022`): MX = AppHdr (head.001.001.02, CBPR+) + Document in a BME envelope; one tx per
  message. Raw text stored unchanged with a DB-checked SHA-256; invalid inbound messages are stored too (INVALID).
  Outbound dedupe by business key (`pacs.008:<paymentId>`), inbound by (sender, MsgId). Hub → messaging only;
  inbound pacs.002/004 go through `POST /api/payments/inbound`; the network simulation builds real messages.
- Network (M5): messages go out through the outbox in the status tx to `minipay.network.<BIC8>` (non-participants:
  `minipay.network.external`); `network` module = inbox consumer (→ `hub.receiveMessage("net:"+eventId)`) + UETR tracker.
  Kafka only in `paymenthub.internal.outbox` (the only publisher) and `network.internal` (consumers).
- Incoming payments: `IncomingProcessor` (creditor bank / intermediary / cover correspondent); SttlmMtd picks the
  inbound account; RJCT before booking, reversal + relay after; outcomes travel back hop by hop. State machine is
  per direction (OUTGOING/INCOMING tables). `minipay.network.participants` is comma-separated (read with @Value).
- `NetworkE2EIT` runs all three banks in one JVM; pass overrides as command-line args (they outrank profile yml).
- Compliance (M6, `compliance` module, depends only on `common`): hub screens via `ScreeningPort` → `ComplianceApi`
  (VALIDATED → SCREENED → HELD_COMPLIANCE, both directions, every bank). One screening per payment (re-runs read it
  back); case per hit; decisions lock the case row; release ≥ four-eyes threshold needs a 2nd person (DB trigger too).
  Closing decision → `ComplianceDecisionMade` event after commit → hub resumes; sweeper covers HELD_COMPLIANCE.
  Reject = RR04, RJCT AddtlInf only "Regulatory reason". Sanctions lists are fictional, effective-dated seed data.
- Settlement (M7, `settlement` module, ADR-008): a 4th instance, profile `operator` (MNCBDEFFXXX, :8084, DB 5436),
  runs T2 (RTGS queue, cut-off AM04), MiniPay Net (DNS cycles, multilateral netting, unwinding) and MiniPay Instant
  (prefunded positions, 10 s timeout AB05). Its ledger is the system's own books: booking + status + messages in ONE
  tx, under participant row locks (BIC order). V-shape: pacs.008 AppHdr/To = operator, InstdAgt = creditor bank,
  ClrSys/Prtry = system; the operator forwards the Document byte for byte (`MessagingApi.relay`). Sends only through
  `paymenthub.api.NetworkOutbox`. Participants B, C; D, E played by the operator; A indirect via B. Banks mirror their
  accounts (`1001-EUR-T2` etc., T2 mirrors may go negative). Payment `serviceLevel` NORM/HIGH/INST restricts routing
  methods (`clearing_system` refdata; methods DNS, INSTANT). Incoming instant: ACKED (ACCP to the operator), credited
  on its ACSC. A payment that arrived via T2/DNS is returned (pacs.004 via the operator), never RJCT.
- `commons-lang3.version` is overridden in the parent POM (Prowide needs ≥ 3.18; Boot manages 3.17).
- ADRs: 001 architecture, 002 ledger, 003 payment hub, 004 reference data, 005 messaging, 006 network, 007 compliance,
  008 settlement.

## Non-negotiable rules

1. Money = `Money` (BigDecimal + ISO currency, scale = currency minor units). Never double/float. JSON amounts are strings.
2. Postings and journal entries are append-only (DB triggers). Corrections = REVERSAL entries.
3. Every journal entry balances per currency and balance type (domain + deferred constraint trigger).
4. Balance updates in the same transaction as postings; lock balance rows `ORDER BY account_id FOR UPDATE`.
5. Every write API requires `Idempotency-Key`; same key + different body = 422; replay = 200 + `Idempotent-Replayed: true`.
6. No Kafka publishing from business code — transactional outbox + relay.
7. Payment status changes go through `PaymentStateMachine` and write history; illegal transitions throw.
8. Store raw messages unchanged plus parsed fields; never rebuild a message once sent.
9. Reference data is effective-dated; lookups take a date (M3).
10. TIMESTAMPTZ in UTC; value dates are DATE; "today" uses the bank's time zone (`minipay.bank.time-zone`); a cut-off is
    wall-clock time in its settlement scheme's zone, converted per date.
11. Real ISO codes (AM04, AC01, …; DEBT/CRED/SHAR); valid-format BICs/IBANs in seed data.

## Working style

- Implement only the current milestone. Tests come with the code; integration tests use Testcontainers, never H2.
- Ledger tests cannot clean up (append-only): open fresh, uniquely numbered accounts per test and assert only on them.
- Prove concurrency/crash-safety tests by mutation (break the lock/key, see the test fail, restore).
- Ambiguous payment-domain question → choose what real banks / CBPR+ do, record it in an ADR, continue.
- Small commits with conventional-commit style messages. **No Claude co-author/attribution trailers.** Do not open PRs —
  the user does that.
- Frontend: match `design/*.dc.html` using the tokens in `frontend/ops-console/src/index.css` and components in
  `src/components/ui.tsx`. TanStack Table is v9 (`useTable({ features, columns, data })`, `table.FlexRender`).
