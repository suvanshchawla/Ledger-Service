# Ledger Service — Design Doc & Phase Plan

Sep 30, 2026 · @Suvansh Chawla

## Overview and goals

The ledger service is the system of record for money in the platform: it holds accounts, moves money between them with double-entry bookkeeping, and publishes an event for every committed transfer. The fraud service (Python) and the risk-review service (C#/.NET) consume those events later.

It is also a portfolio project, so the goals combine correctness with presentability.

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
| Transfer | A request to move an amount from one account to another | Has an idempotency key; status COMMITTED or REJECTED |
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
  "fromAccountId": "5d49f5f4-c240-445d-9cd3-a4bdd7dab36a",
  "toAccountId": "2950fa7c-8706-4da7-9d8c-059d5630c97c",
  "amount": { "value": 2500, "currency": "CAD" }
}
```

Errors use RFC 9457 Problem Details (`application/problem+json`) so every error has the same shape:

| Status | When |
| --- | --- |
| 400 | Validation failed: missing fields, non-positive or fractional amount, same source and target, malformed JSON or id, a blank or over-long `Idempotency-Key` |
| 404 | An account or transfer does not exist |
| 409 | Idempotency key reused with a different request body |
| 422 | Business rule failed: insufficient funds (the transfer is stored as REJECTED) or accounts of different currencies |
| 428 | Idempotency-Key header missing |

A repeat request with the same key and body returns the original response, same status and same transfer id, without executing again.

Notes on the contract:

- Amounts are whole minor units (cents). `Idempotency-Key` is required on `POST /api/v1/transfers` and must be 1 to 255 characters.
- A rejected transfer is answered with 422 and Problem Details that include a `transferId` member naming the stored transfer; `GET /api/v1/transfers/{id}` returns it with status `REJECTED` and its reason.
- Accounts opened through the API are always CUSTOMER accounts with a zero balance. The Treasury SYSTEM account is seeded by a migration, so money enters the system only through transfers from it.
- The history takes `limit` (1 to 100, default 20) and an opaque `cursor`, the previous page's `nextCursor`. Items carry the transfer id, the signed amount and the counterparty account.
- A free-text transfer reference is not supported: the schema has no column for it.

## Data model

Five tables in PostgreSQL, managed by Flyway migrations, with the invariants enforced by the database as well as the code.

```sql
CREATE TABLE accounts (
    id            UUID PRIMARY KEY,
    type          TEXT NOT NULL CHECK (type IN ('CUSTOMER', 'SYSTEM')),
    currency      CHAR(3) NOT NULL DEFAULT 'CAD',
    balance_minor BIGINT NOT NULL DEFAULT 0,
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
    status          TEXT NOT NULL CHECK (status IN ('COMMITTED','REJECTED')),
    reject_reason   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT no_self_transfer CHECK (from_account_id <> to_account_id)
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

-- Journal entries and postings are append-only: any UPDATE or DELETE is an error.
CREATE FUNCTION forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% on % is not allowed: table is append-only', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER journal_entries_append_only
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TRIGGER postings_append_only
    BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
```

Design notes:

- `balance_minor` is a cached balance for fast reads; the postings table is the truth, and a reconciliation test checks they agree.
- The `UNIQUE` constraint on `idempotency_key` is the last line of defence against double execution, even if application logic has a bug.
- `journal_entries.transfer_id` is `UNIQUE`, so one transfer can only ever be booked once.
- The index on postings supports the cursor-paginated history endpoint.
- `journal_entries` and `postings` are append-only: triggers reject any `UPDATE` or `DELETE`.
- Zero-sum per journal entry is enforced in the service code (and proven by tests), not by the database.
- `transfers.status` has no PENDING state: the transfer row and its journal entry are written in one transaction, so a transfer is only ever visible as COMMITTED or REJECTED.
- Currency is stored per account; a transfer between accounts with different currencies is rejected by the service (422).
- The schema above is V1. Migration V2 adds `accounts.name` (required, 1 to 100 characters, no leading or trailing whitespace, not unique). V3 seeds the Treasury SYSTEM account (`00000000-0000-0000-0000-000000000001`), the source of deposits.

## Consistency, concurrency and idempotency

Each transfer runs in one database transaction that locks both accounts in a fixed order, so concurrent transfers serialize safely and cannot deadlock.

The transfer flow:

1. Validate what needs no database: the source and destination differ, and the amount is positive (400 otherwise).
2. Hash the request (SHA-256 of source, destination and amount) and look up the idempotency key.
   - Key exists with the same hash: return the stored result and stop.
   - Key exists with a different hash: return 409.
3. Begin a transaction at READ COMMITTED.
4. Lock both accounts with one statement, `SELECT ... WHERE id IN (...) ORDER BY id FOR UPDATE`, always in ascending id order. Fixed ordering prevents A→B and B→A transfers from deadlocking each other. A missing account is a 404; accounts of different currencies are a 422.
5. Check funds. Only CUSTOMER sources are checked; SYSTEM accounts may go negative.
6. Insert the transfer row with its final status: COMMITTED, or REJECTED with a reason. It is inserted first, so a duplicate idempotency key fails here before anything else is written.
7. If COMMITTED, insert the journal entry and two postings (−amount, +amount), update both cached balances, and insert a `TransferCommitted` row into `outbox_events`.
8. Commit. Steps 4–7 land together or not at all.

If the insert in step 6 hits the `UNIQUE` constraint on the key, another request with the same key won the race. The transaction rolls back, the lookup from step 2 is repeated, and the winner's result is returned (or a 409 if its request differed). Only COMMITTED and REJECTED transfers are stored; requests that fail in steps 1 or 4 leave no trace.

Why pessimistic locking first: on a hot account (a popular merchant), optimistic locking (which needs a `version` column, absent from the current schema) causes many retries under contention, while row locks simply queue. The pessimistic version is implemented first. An optimistic version is planned as an experiment, to be compared with it in the load test and recorded in ADR 0003.

What "exactly once" means here: the client may retry any number of times, and the idempotency key plus the unique constraints guarantee one booking. Delivery of events downstream is at-least-once (see next section), so consumers must deduplicate by event id.

## Events and transactional outbox

Events are written to the outbox table inside the transfer transaction and published to Kafka afterwards, which avoids the dual-write problem of committing to the database and then failing to publish.

Event shape (JSON, versioned):

```json
{
  "eventId": "b1e2c7a4-3f90-4d1e-8a52-6c0d9e7f1a23",
  "eventType": "TransferCommitted",
  "schemaVersion": 1,
  "occurredAt": "2026-10-05T14:03:22Z",
  "transferId": "8f14e45f-ceea-467a-9575-1e3b8d2c4a90",
  "fromAccountId": "5d49f5f4-c240-445d-9cd3-a4bdd7dab36a",
  "toAccountId": "2950fa7c-8706-4da7-9d8c-059d5630c97c",
  "amount": { "value": 2500, "currency": "CAD" }
}
```

Publisher design:

- A scheduled poller reads unpublished rows with `SELECT ... FOR UPDATE SKIP LOCKED LIMIT 100`, so several instances can run without publishing the same row twice at once.
- It sends each event to the `ledger.transfers` topic keyed by `fromAccountId`, which keeps events for one account in order within a partition.
- After Kafka acknowledges, it sets `published_at`. A crash between send and update means a re-send, which is why delivery is at-least-once.
- Published rows are deleted by a cleanup job after 7 days.

Phase 1 has no Kafka: the outbox table fills up and a test asserts the rows are correct. Phase 2 adds the poller. A later upgrade is Debezium change-data-capture instead of polling; the trade-off is recorded in ADR 0005.

## Tech stack and project structure

Java 21 with Spring Boot 3, the stack most Toronto banks and fintechs run, kept deliberately boring so the design is what stands out.

| Layer | Choice | Why |
| --- | --- | --- |
| Language | Java 21 | Records for immutable value types; a long-term support release |
| Framework | Spring Boot 3 (Web, Data JPA or JdbcClient, Validation, Actuator) | Industry default for Java backends |
| Database | PostgreSQL 16 | Row locks, SKIP LOCKED, JSONB, partial indexes |
| Migrations | Flyway | Versioned, reviewable schema changes |
| Messaging | Kafka (Redpanda locally) | Phase 2; Redpanda is lighter to run in Docker |
| Build | Gradle (Kotlin DSL) | Common in modern Java projects |
| Testing | JUnit 5, AssertJ, Testcontainers, k6 | Real Postgres and Kafka in tests, load numbers for the README |
| API docs | springdoc-openapi | Generated Swagger UI from the code |
| Observability | Micrometer, OpenTelemetry | Metrics and traces across services later |

The transfer path uses `JdbcClient` with explicit SQL. JPA hides the locking and flush order, and this service needs every query to be visible.

Not yet in the project: Kafka (Phase 2), springdoc-openapi, metrics beyond the Actuator defaults and OpenTelemetry.

Package layout, organized by feature rather than by layer:

```
ledger-service/
  src/main/java/dev/suvansh/ledger/
    account/     account endpoints and postings history: controller, service, repositories, DTOs
    transfer/    TransferController, TransferService, TransferRepository
    common/      Money, MoneyDto, ProblemException, Problems, ProblemDetailsHandler
  src/main/resources/db/migration/   V1__init.sql, V2__add_account_name.sql, V3__seed_treasury_account.sql
  src/test/java/...                  unit, integration, concurrency and idempotency tests
  .github/workflows/ci.yml           ./gradlew test on every push
  docs/adr/                          architecture decision records
  docker-compose.yml                 Postgres (+ Redpanda in Phase 2)
  load-test/                         k6 scripts, runner and ledger verification
  CLAUDE.md
```

`TransferService` writes the journal entry, postings and outbox row itself. A package for the outbox publisher arrives with the Phase 2 poller.

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

The concurrency tests are written before the locking code. They fail against a naive implementation without locking and pass once the locking is in place.

## Observability

Phase 1 targets structured logs and a handful of metrics; distributed tracing waits until there is a second service to trace into. Implemented so far: the Actuator health endpoint. The logs, metrics and alert below are planned.

- **Logs:** JSON logs with `transferId`, `idempotencyKey` and a request id on every line, so one transfer can be followed end to end.
- **Metrics (Micrometer, exposed via Actuator):** transfer count by status, transfer latency histogram, idempotency replays, lock wait time, and outbox lag (age of the oldest unpublished event).
- **Health:** Actuator liveness and readiness probes, used later by Kubernetes.
- **Tracing (Phase 3):** OpenTelemetry, with the trace context carried in Kafka headers so one trace spans ledger, fraud and risk-review services.

Outbox lag is the metric to alert on: if it grows, downstream services are silently falling behind.

## Phase plan

Four phases, each closed by a gate that is a passing test or a visible result, not a feeling of being done.

| Phase | Scope | Gate |
| --- | --- | --- |
| 1 | The ledger service alone: accounts, transfers, idempotency, the outbox table, CI (the task list below) | The concurrency and idempotency tests pass, and a k6 result is recorded in the README |
| 2 | The outbox poller publishes `TransferCommitted` to Kafka (Redpanda locally) | Contract test: outbox rows are published once and match the event schema |
| 3 | Distributed tracing with OpenTelemetry across the ledger, fraud and risk-review services | To be defined |
| 4 | Authentication (a static API key or none until then) | To be defined |

Only Phase 1 is this service alone; Phases 2–4 grow it into the full platform. A phase starts only after the previous gate passes.

Phase 1 tasks, in order:

- [x] Create the repo, Gradle project and docker-compose with Postgres
- [x] Write the V1 Flyway migration from the schema above
- [x] Build the `Money` value object with unit tests
- [x] Accounts endpoints with Testcontainers integration tests
- [x] Write the concurrency test and watch it fail on a naive transfer
- [x] Write `TransferService` by hand with locking and idempotency until it passes
- [x] Problem Details error handling and the idempotency replay path
- [x] GitHub Actions workflow running `./gradlew test` on every push
- [x] ADRs 0001–0004 (0003 still awaits the optimistic comparison)
- [x] Outbox event written in the transfer transaction
- [x] Transfer endpoints and the postings history endpoint
- [x] k6 load test, with the results recorded in the README ([results](load-test-results.md))

## ADRs and open questions

Each ADR is written when the decision is made, on one page: context, options considered, decision and consequences.

- [x] 0001: Double-entry postings instead of a single balance column
- [x] 0002: Integer minor units instead of BigDecimal
- [ ] 0003: Pessimistic row locks vs optimistic versioning, with load-test numbers (written; pessimistic numbers recorded, optimistic comparison pending)
- [x] 0004: Idempotency via stored request hash and unique key
- [x] 0005: Transactional outbox with polling instead of dual writes or CDC
- [x] 0006: JdbcClient with explicit SQL instead of JPA on the transfer path

Open questions:

- Should reversals be a separate transfer type, or a normal transfer with a link to the original?
- How long should idempotency keys be kept: forever, or expire after 24 hours like Stripe's?
- Is Kafka worth running locally from Phase 2, or should SQS/SNS be used to lean on AWS instead?
