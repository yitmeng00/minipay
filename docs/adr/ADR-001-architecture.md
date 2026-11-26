# ADR-001: Overall architecture

- **Status:** Accepted
- **Date:** 2026-11-24
- **Milestone:** M0
- **Amended by:** ADR-008 (M7): a fourth instance of the same application, the simulated central bank (profile
  `operator`, its own database), runs the clearing systems

## Context

MiniPay simulates a small cross-border payment network: three banks exchanging ISO 20022 messages.

- **Bank A** in Kuala Lumpur sends the payments.
- **Bank B** in Frankfurt is the EUR correspondent.
- **Bank C** in London receives GBP payments for its customers.

It is a learning project, so the structure itself should teach how bank payment back-ends are built:

- clear ownership of data;
- money that can never be created or lost by accident;
- every external effect traceable to a stored record.

The project has to stay runnable on one laptop and readable by one person.

## Decision

### 1. One modular monolith per bank, three instances

`bank-app` is a Spring Boot application that assembles every module. It runs **once per bank**, using the profiles `bank-a`, `bank-b` and `bank-c`. Each instance has its own port (8081–8083), its own PostgreSQL database and its own time zone.

```
           ┌──────────── bank-app (profile bank-a) ─────────────┐
           │ ledger · payment-hub · messaging · refdata · ...   │──► bank-a-db (Postgres)
           └──────────────────────────┬─────────────────────────┘
                                      │ ISO 20022 messages only (M4/M5)
                         ┌────────────▼────────────┐
                         │  network-simulator      │  (Kafka, KRaft)
                         └────────────┬────────────┘
             bank-app (bank-b) ◄──────┴──────► bank-app (bank-c)
                    │                                 │
                bank-b-db                         bank-c-db
```

Real banks never share databases. They only exchange messages: pacs.008 to send a payment, pacs.002 for status, camt.053 for statements. Three databases make that boundary physical. Bank A *cannot* read Bank B's books; it can only learn what Bank B tells it.

### 2. Modules with hard boundaries inside each bank

| Module | Owns schema | Purpose |
|---|---|---|
| `common` | – | Value types: `Money`, `Uetr`, `Bic`. No Spring, no persistence. |
| `ledger` | `ledger` | Double-entry books (M1, see ADR-002) |
| `payment-hub` | `payment` | Payment lifecycle, state machine, outbox (M2) |
| `refdata` | `refdata` | BIC directory, IBAN, routing, calendars (M3) |
| `messaging` | `messaging` | pacs/camt/MT parsing and generation (M4) |
| `network-simulator` | – | Inter-bank transport, UETR tracker (M5) |
| `compliance` | `compliance` | Sanctions screening (M6) |
| `settlement` | `settlement` | RTGS, DNS, instant (M7) |
| `recon` | `recon` | camt.053 reconciliation (M8) |

Rules:

- **Public versus private code.** A module's public contract is `com.minipay.<module>.api`: interfaces, commands, views and, later, events. Everything else is in `com.minipay.<module>.internal`.
- **Enforced by ArchUnit** (`ModuleBoundariesTest`):
  - no class outside a module may touch its `internal` package;
  - `api` must not leak `internal` types;
  - `common` depends on no module and no framework;
  - there are no dependency cycles between modules;
  - no business code may depend on a Kafka client (see rule 4 below).
- **Data ownership.** Each module owns **one PostgreSQL schema** and is the only code with SQL against it. Since all SQL lives in `internal`, the ArchUnit rule also enforces "no module reads another module's tables".
- **Each module migrates its own schema.** It has its own Flyway instance, and the migration history table (`<schema>.flyway_schema_history`) lives in that schema. Spring Boot's global Flyway is disabled.

Rejected alternatives:

- **Microservices per module.** This would teach distributed-systems plumbing instead of payments. It would also force a distributed transaction between "reserve funds" and "update payment state", which in a real bank sit behind the same database transaction or an outbox.
- **One database shared by the three banks.** This makes cheating easy, and nothing in the code would show the real boundary between banks.

### 3. Money, time and identifiers (cross-cutting rules)

- **Money**
  - `Money` is a `BigDecimal` plus an ISO 4217 currency, always at the currency's own number of decimals (JPY 0, EUR 2, KWD 3).
  - Amounts coming from outside are **rejected** if they have too many decimals, never silently rounded.
  - Amounts the bank computes itself are rounded HALF_EVEN.
  - JSON carries amounts as **strings**, so no client parses money into a float.
- **Time**
  - Timestamps are `TIMESTAMPTZ`, stored in UTC.
  - Value dates and business dates are `DATE`.
  - The bank's time zone (`minipay.bank.time-zone`) decides what "today" means: the business date, the statement day and, from M3, cut-offs.
- **Identifiers**
  - `Uetr` is a lower-case UUIDv4, as SWIFT gpi requires.
  - `Bic` checks the ISO 9362 format only. Whether a BIC *exists* is a reference-data question, answered in M3.
- **ISO reason codes** (AM04, AC01, AC04, AC06, AM03, AM12) are used wherever a request is refused, so the same code can travel in a pacs.002 later.

### 4. Reliability conventions (all milestones)

- **Idempotency keys.** Every write API needs an `Idempotency-Key` header.
  - The same key with the same request replays the stored result: HTTP 200 with `Idempotent-Replayed: true`.
  - The same key with a different request is **HTTP 422**.
- **Transactional outbox (M2).** Business code never publishes to Kafka. It writes an outbox row in its own transaction, and a relay publishes it. ArchUnit already forbids Kafka imports.
- **Append-only where history matters.** This covers postings and journal entries (M1), and from later milestones payment state history and raw messages. Corrections are new records, never edits.
- **Database-enforced invariants.** The application checks every rule, and the database checks the critical ones *again* (triggers, constraints). A bug or a manual SQL "fix" then cannot corrupt the books.

### 5. Testing strategy

- Unit tests for pure domain rules.
- Integration tests run against **real PostgreSQL 16 via Testcontainers**. H2 is never used: the ledger depends on deferred constraint triggers, partitioning, row locks and generated columns.
- Concurrency tests release all threads at once from a latch. They were checked by **mutation**: removing `FOR UPDATE` or the lock ordering makes them fail.
- `mvn verify` runs unit tests (Surefire, `*Test`) and integration tests (Failsafe, `*IT`). CI runs the same command, plus the frontend build.

### 6. Frontend

`frontend/ops-console` is React, TypeScript, Vite and Tailwind; its design tokens (colours, type, spacing, light and dark) are in `src/index.css`.

- It talks to each bank through same-origin proxy prefixes (`/bank-a/...`). The header's bank switcher changes the prefix.
- Screens ship with the milestone that powers them. Navigation shows the later screens, disabled.

## Consequences

- Each module can later be split out into a service: it already has its own schema, its own migrations and an API-only contract.
- Three JVMs and three Postgres containers make up the local footprint. That is acceptable on a developer laptop.
- Cross-module calls are in-process and share a transaction manager. That is convenient, but modules must still behave as if they could not see each other's data. ArchUnit keeps us honest.

## Domain choices made where the brief was ambiguous

- **Correspondent banking.** Bank B is the EUR correspondent for Bank A, so Bank A has a *nostro* at Bank B. Bank B sees the same relationship as a *vostro* for Bank A. The seed data makes the two balances equal (EUR 2,899,613.17), which is what reconciliation will check in M8. Bank A's GBP relationship with Bank C works the same way.
- **Settlement accounts.** Each bank also has a settlement account at a (fictional) central bank: RENTAS-like for MYR, T2-like for EUR, an RTGS account for GBP. Opening balances are booked against these accounts or against nostros, so the books balance without an equity account.
- **Malaysian accounts.** Malaysia does not use IBANs, so Bank A's domestic customers have plain account numbers. German and British accounts use valid IBANs (mod-97 checked).
