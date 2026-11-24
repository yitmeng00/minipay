# CLAUDE.md

MiniPay is a learning project: three simulated banks exchanging ISO 20022 messages. Correctness and realism of payment concepts
matter more than feature count. Explain payment-domain decisions in code comments and ADRs (`docs/adr/`).

## Commands

```bash
docker compose up -d                                   # 3 × Postgres (5433/5434/5435) + Kafka
cd backend && ./mvnw verify                            # unit (*Test) + Testcontainers (*IT) + ArchUnit
./mvnw install -DskipTests && ./mvnw -pl bank-app spring-boot:run -Dspring-boot.run.profiles=bank-a   # :8081
cd frontend/ops-console && npm run dev                 # :5173, proxies /bank-a|b|c → 8081|8082|8083
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
- ADRs: 001 architecture, 002 ledger, 003 payment hub.

## Non-negotiable rules

1. Money = `Money` (BigDecimal + ISO currency, scale = currency minor units). Never double/float. JSON amounts are strings.
2. Postings and journal entries are append-only (DB triggers). Corrections = REVERSAL entries.
3. Every journal entry balances per currency and balance type (domain + deferred constraint trigger).
4. Balance updates in the same transaction as postings; lock balance rows `ORDER BY account_id FOR UPDATE`.
5. Every write API requires `Idempotency-Key`; same key + different body = 422; replay = 200 + `Idempotent-Replayed: true`.
6. No Kafka publishing from business code — transactional outbox + relay.
7. Payment status changes go through `PaymentStateMachine` and write history; illegal transitions throw.
8. Store raw messages unchanged plus parsed fields (M4).
9. Reference data is effective-dated; lookups take a date (M3).
10. TIMESTAMPTZ in UTC; value dates are DATE; "today"/cut-offs use the bank's time zone (`minipay.bank.time-zone`).
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
