# Ledger Service — Design Doc & Phase Plan

Sep 30, 2026 · @Suvansh Chawla

## Overview and goals

The ledger service is the system of record for money in the platform: it holds accounts, moves money between them with double-entry bookkeeping, and publishes an event for every committed transfer. The fraud service (Python) and the risk-review service (C#/.NET) consume those events later.

It is also the portfolio piece that proves the Java, SQL, REST and testing lines on the CV, so the goals mix correctness and presentability.

Success criteria:

- **Correctness:** the sum of all postings is always zero, and no retried or concurrent request ever moves money twice.
- **Performance:** a documented k6 result, for example sustained transfers/sec at a p99 latency target on a single instance.
- **Proof:** tests that demonstrate the guarantees, including a concurrency test that fires many parallel transfers and checks balances afterwards.
- **Readability:** a README with an architecture diagram, the load-test numbers, and links to ADRs, so a reviewer understands it in five minutes.

## Scope and non-goals

In scope: accounts in a single currency (CAD), internal transfers between accounts, balance queries, a transaction history per account, idempotent writes, and outbox-based event publishing.

Out of scope for now, each a possible later extension:

- Multi-currency and FX conversion.
- Real payment rails (Interac, card networks) and external settlement.
- Authentication and user management; a static API key or none is fine until Phase 4.
- Reversals and refunds as a first-class flow; a reversal can be modelled as a new compensating transfer.
- A UI; the API and its OpenAPI docs are the interface.

## Domain model

Money never appears or disappears: every transfer is a journal entry made of postings that sum to zero.

| Concept | What it is | Key rules |
| --- | --- | --- |
| Account | A bucket that holds a balance | Has a type (customer, system); customer accounts cannot go below zero |
| Transfer | A request to move an amount from one account to another | Has an idempotency key; status PENDING, COMMITTED or REJECTED |
| Journal entry | The accounting record of one committed transfer | Immutable; created in the same DB transaction as its postings |
| Posting | One line of a journal entry: an account and a signed amount | Postings in an entry sum to zero; never updated or deleted |

Amounts are stored as `long` minor units (cents), never `double`. In Java, wrap them in a `Money` value object so arithmetic and currency checks live in one place.

The invariants the tests must prove:

1. For every journal entry, the sum of its postings is 0.
2. For every account, the stored balance equals the sum of its postings.
3. A customer account balance is never negative.
4. One idempotency key produces at most one journal entry.

## API design

A small REST API, versioned under `/api/v1`, with every money-moving call requiring an `Idempotency-Key` header.

| Method | Path | Purpose | Success |
| --- | --- | --- | --- |
| POST | /api/v1/accounts | Open an account | 201 Created |
| GET | /api/v1/accounts/{id} | Account details and current balance | 200 OK |
| GET | /api/v1/accounts/{id}/postings | Paginated history, newest first (cursor-based) | 200 OK |
| POST | /api/v1/transfers | Move money between two accounts | 201 Created |
| GET | /api/v1/transfers/{id} | Transfer status and details | 200 OK |

Example transfer request:

```json
POST /api/v1/transfers
Idempotency-Key: 7f3c9a2e-1b4d-4c8e-9f21-5a6b7c8d9e0f

{
  "fromAccountId": "acc_123",
  "toAccountId": "acc_456",
  "amount": { "value": 2500, "currency": "CAD" },
  "reference": "Rent share"
}
```

Errors use RFC 9457 Problem Details (`application/problem+json`) so every error has the same shape:

| Status | When |
| --- | --- |
| 400 | Validation failed: missing fields, non-positive amount, same source and target |
| 404 | An account does not exist |
| 409 | Idempotency key reused with a different request body |
| 422 | Business rule failed, e.g. insufficient funds; the transfer is stored as REJECTED |
| 428 | Idempotency-Key header missing |

A repeat request with the same key and body returns the original response, same status and same transfer id, without executing again.

## Data model

Five tables in PostgreSQL, managed by Flyway migrations, with the invariants enforced by the database as well as the code.

```sql
CREATE TABLE accounts (
    id            UUID PRIMARY KEY,
    type          TEXT NOT NULL CHECK (type IN ('CUSTOMER', 'SYSTEM')),
    currency      CHAR(3) NOT NULL DEFAULT 'CAD',
    balance_minor BIGINT NOT NULL DEFAULT 0,
    version       BIGINT NOT NULL DEFAULT 0,          -- optimistic locking
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT non_negative_customer
        CHECK (type <> 'CUSTOMER' OR balance_minor >= 0)
);

CREATE TABLE transfers (
    id              UUID PRIMARY KEY,
    idempotency_key TEXT NOT NULL UNIQUE,
    request_hash    TEXT NOT NULL,                     -- detects key reuse with a different body
    from_account_id UUID NOT NULL REFERENCES accounts(id),
    to_account_id   UUID NOT NULL REFERENCES accounts(id),
    amount_minor    BIGINT NOT NULL CHECK (amount_minor > 0),
    status          TEXT NOT NULL CHECK (status IN ('PENDING','COMMITTED','REJECTED')),
    reject_reason   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE journal_entries (
    id          UUID PRIMARY KEY,
    transfer_id UUID NOT NULL UNIQUE REFERENCES transfers(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE postings (
    id               BIGSERIAL PRIMARY KEY,
    journal_entry_id UUID NOT NULL REFERENCES journal_entries(id),
    account_id       UUID NOT NULL REFERENCES accounts(id),
    amount_minor     BIGINT NOT NULL CHECK (amount_minor <> 0),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_postings_account_time ON postings (account_id, created_at DESC, id DESC);

CREATE TABLE outbox_events (
    id             UUID PRIMARY KEY,
    aggregate_id   UUID NOT NULL,
    event_type     TEXT NOT NULL,
    payload        JSONB NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
```

Design notes:

- `balance_minor` is a cached balance for fast reads; the postings table is the truth, and a reconciliation test checks they agree.
- The `UNIQUE` constraint on `idempotency_key` is the last line of defence against double execution, even if application logic has a bug.
- `journal_entries.transfer_id` is `UNIQUE`, so one transfer can only ever be booked once.
- The index on postings supports the cursor-paginated history endpoint.

## Consistency, concurrency and idempotency

Each transfer runs in one database transaction that locks both accounts in a fixed order, so concurrent transfers serialize safely and cannot deadlock.

The transfer flow:

1. Hash the request body and look up the idempotency key.
   - Key exists with the same hash: return the stored result, do nothing else.
   - Key exists with a different hash: return 409.
2. Begin a transaction at READ COMMITTED.
3. Insert the transfer row as PENDING. If the unique key constraint fires, another request with the same key won the race: roll back and return its stored result.
4. Lock both accounts with `SELECT ... FOR UPDATE`, always in ascending id order. Fixed ordering prevents A→B and B→A transfers from deadlocking each other.
5. Check funds. If insufficient, mark the transfer REJECTED, commit, return 422.
6. Insert the journal entry and two postings (−amount, +amount), update both cached balances.
7. Insert a `TransferCommitted` row into `outbox_events`.
8. Mark the transfer COMMITTED and commit. Everything in steps 3–8 lands together or not at all.

Why pessimistic locking first: on a hot account (a popular merchant), optimistic locking with a `version` column causes many retries under contention, while row locks simply queue. Build the pessimistic version first, then try the optimistic version as an experiment and compare them in the load test; that comparison is a strong ADR and interview story.

What "exactly once" means here: the client may retry any number of times, and the idempotency key plus the unique constraints guarantee one booking. Delivery of events downstream is at-least-once (see next section), so consumers must deduplicate by event id.

## Events and transactional outbox

Events are written to the outbox table inside the transfer transaction and published to Kafka afterwards, which avoids the dual-write problem of committing to the database and then failing to publish.

Event shape (JSON, versioned):

```json
{
  "eventId": "b1e2...",
  "eventType": "TransferCommitted",
  "schemaVersion": 1,
  "occurredAt": "2026-10-05T14:03:22Z",
  "transferId": "trf_789",
  "fromAccountId": "acc_123",
  "toAccountId": "acc_456",
  "amount": { "value": 2500, "currency": "CAD" }
}
```

Publisher design:

- A scheduled poller reads unpublished rows with `SELECT ... FOR UPDATE SKIP LOCKED LIMIT 100`, so several instances can run without publishing the same row twice at once.
- It sends each event to the `ledger.transfers` topic keyed by `fromAccountId`, which keeps events for one account in order within a partition.
- After Kafka acknowledges, it sets `published_at`. A crash between send and update means a re-send, which is why delivery is at-least-once.
- Published rows are deleted by a cleanup job after 7 days.

Phase 1 has no Kafka: the outbox table fills up and a test asserts the rows are correct. Phase 2 adds the poller. A later upgrade is Debezium change-data-capture instead of polling, worth mentioning as a trade-off in the ADR.

## Tech stack and project structure

Java 21 with Spring Boot 3, the stack most Toronto banks and fintechs run, kept deliberately boring so the design is what stands out.

| Layer | Choice | Why |
| --- | --- | --- |
| Language | Java 21 | Records, sealed types and virtual threads are good talking points |
| Framework | Spring Boot 3 (Web, Data JPA or JdbcClient, Validation, Actuator) | Industry default for Java backends |
| Database | PostgreSQL 16 | Row locks, SKIP LOCKED, JSONB, partial indexes |
| Migrations | Flyway | Versioned, reviewable schema changes |
| Messaging | Kafka (Redpanda locally) | Phase 2; Redpanda is lighter to run in Docker |
| Build | Gradle (Kotlin DSL) | Common in modern Java shops&#32; |
| Testing | JUnit 5, AssertJ, Testcontainers, k6 | Real Postgres and Kafka in tests, load numbers for the README |
| API docs | springdoc-openapi | Generated Swagger UI from the code |
| Observability | Micrometer, OpenTelemetry | Metrics and traces across services later |

Prefer `JdbcClient` with explicit SQL for the transfer path. JPA hides the locking and flush order, and in this service you want every query visible.

Package layout, organized by feature rather than by layer:

```
ledger-service/
  src/main/java/dev/suvansh/ledger/
    account/     AccountController, AccountService, AccountRepository
    transfer/    TransferController, TransferService, TransferRepository
    journal/     JournalEntry, Posting, JournalRepository
    outbox/      OutboxEvent, OutboxRepository, OutboxPublisher
    common/      Money, ProblemDetails handler, IdempotencyFilter
  src/main/resources/db/migration/   V1__init.sql, ...
  src/test/java/...                  unit, integration, concurrency tests
  load-test/                         k6 scripts
  docs/adr/                          0001-double-entry.md, ...
  docker-compose.yml                 Postgres (+ Redpanda in Phase 2)
  CLAUDE.md
```

## Testing strategy

The tests are the proof of the guarantees, so the concurrency and idempotency tests matter more than coverage percentage.

| Level | Tool | What it covers |
| --- | --- | --- |
| Unit | JUnit 5, AssertJ | `Money` arithmetic, validation, request hashing, posting construction |
| Integration | Testcontainers (Postgres) | Each endpoint against a real database, migrations applied, Problem Details shapes |
| Concurrency | Testcontainers + `ExecutorService` | 1,000 parallel transfers across a few accounts; afterwards balances match postings and no customer balance is negative |
| Idempotency | Testcontainers | Same key fired 50 times in parallel yields exactly one journal entry; same key with a different body yields 409 |
| Reconciliation | SQL assertion | Sum of all postings is 0; each cached balance equals its postings sum |
| Contract (Phase 2) | Testcontainers (Redpanda) | Outbox rows are published once and match the event schema |
| Load | k6 | Throughput and p50/p95/p99 latency, recorded in the README |

Write the concurrency test before the locking code. Watch it fail against a naive implementation, then make it pass; that before-and-after is worth describing in the README.

## Observability

Start with structured logs and a handful of metrics in Phase 1; distributed tracing waits until there is a second service to trace into.

- **Logs:** JSON logs with `transferId`, `idempotencyKey` and a request id on every line, so one transfer can be followed end to end.
- **Metrics (Micrometer, exposed via Actuator):** transfer count by status, transfer latency histogram, idempotency replays, lock wait time, and outbox lag (age of the oldest unpublished event).
- **Health:** Actuator liveness and readiness probes, used later by Kubernetes.
- **Tracing (Phase 3):** OpenTelemetry, with the trace context carried in Kafka headers so one trace spans ledger, fraud and risk-review services.

Outbox lag is the metric to alert on: if it grows, downstream services are silently falling behind.

## Phase plan

Four phases, each closed by a gate that is a passing test or a visible result, not a feeling of being done.

&#91;embedded content: phase plan · 4 phases, 4 gates\]

Only Phase 1 is this service alone; Phases 2–4 grow it into the full platform. Don't start a phase until the previous gate passes.

Phase 1 tasks, in order:

- [ ] Create the repo, Gradle project and docker-compose with Postgres
- [ ] Write the V1 Flyway migration from the schema above
- [ ] Build the `Money` value object with unit tests
- [ ] Accounts endpoints with Testcontainers integration tests
- [ ] Write the concurrency test and watch it fail on a naive transfer
- [ ] Write `TransferService` by hand with locking and idempotency until it passes
- [ ] Problem Details error handling and the idempotency replay path
- [ ] GitHub Actions workflow running `./gradlew test` on every push
- [ ] ADRs 0001–0004

## AI-assisted workflow

You write the parts an interviewer will question; Claude Code writes the parts nobody asks about, and reviews everything.

| Work | Who writes it | Claude Code's role |
| --- | --- | --- |
| `TransferService` (locking, idempotency, posting logic) | You, by hand | Reviewer: ask it to find race conditions and missed edge cases |
| Schema and migrations | You | Reviewer: constraints, indexes, locking implications |
| Concurrency and idempotency tests | You specify the scenarios | Writes the Testcontainers scaffolding |
| Controllers, DTOs, validation, Problem Details handler | Claude Code | You review every diff |
| Gradle setup, docker-compose, CI workflow | Claude Code | You review and understand each line |
| k6 scripts, README, ADR drafts | Claude Code drafts | You edit into your own words |

Rules that keep it a learning project:

- Work in small slices: one endpoint or one test class per Claude Code session, each ending in a commit you can explain.
- Before accepting a diff, be able to say why each change is there. If you can't, ask Claude Code to explain it, then decide.
- Use it as a Java tutor: ask how Spring manages the transaction boundary, what `FOR UPDATE` does to other sessions, what the JIT does with records.

Starting `CLAUDE.md`:

```markdown
# Ledger service
Java 21, Spring Boot 3, Gradle Kotlin DSL, PostgreSQL 16, Flyway.

## Rules
- Money is always `long` minor units wrapped in `Money`; never double or BigDecimal in the domain.
- Transfer path uses JdbcClient with explicit SQL, not JPA.
- Lock accounts in ascending id order.
- Postings and journal entries are immutable: no UPDATE or DELETE.
- Every change comes with tests; integration tests use Testcontainers.
- Do not modify TransferService without being asked; suggest changes instead.

## Commands
- ./gradlew test
- docker compose up -d
```

## ADRs and open questions

Write each ADR when you make the decision, one page each: context, options considered, decision, consequences.

- [ ] 0001: Double-entry postings instead of a single balance column
- [ ] 0002: Integer minor units instead of BigDecimal
- [ ] 0003: Pessimistic row locks vs optimistic versioning, with load-test numbers
- [ ] 0004: Idempotency via stored request hash and unique key
- [ ] 0005: Transactional outbox with polling instead of dual writes or CDC
- [ ] 0006: JdbcClient instead of JPA on the transfer path

Open questions:

- Should reversals be a separate transfer type, or a normal transfer with a link to the original?
- How long should idempotency keys be kept: forever, or expire after 24 hours like Stripe's?
- Is Kafka worth running locally from Phase 2, or should SQS/SNS be used to lean on AWS instead?
