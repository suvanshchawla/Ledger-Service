# Ledger Service

[![CI](https://github.com/suvanshchawla/Ledger-Service/actions/workflows/ci.yml/badge.svg)](https://github.com/suvanshchawla/Ledger-Service/actions/workflows/ci.yml)

A double-entry ledger for a small fintech platform. It is the system of record for accounts and money movement: every transfer is booked as an immutable journal entry whose postings sum to zero, writes are idempotent, and every committed transfer is published as an event through a transactional outbox.

This is a portfolio project. Correctness, tests that prove the guarantees, and readability matter more than feature count.

> **Status: early development (Phase 1).** The project skeleton, the `Money` type, the database schema, error handling, the account and transfer endpoints, and the transfer logic (locking, idempotency, validation, outbox event) exist. The per-account postings history exists too. Publishing events to Kafka is planned and not built yet. See [Status](#status).

## What it will do

- Open accounts and query their balances (customer and system accounts).
- Move money between two accounts as a single atomic, double-entry transfer.
- Make every write idempotent: retrying a request with the same `Idempotency-Key` never moves money twice, however many times or how concurrently it is sent.
- Keep a per-account transaction history (cursor-paginated, newest first).
- Record an event for every committed transfer in an outbox table, and publish it to Kafka later (at-least-once).
- Report all errors as [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) Problem Details.

## Guarantees

These are the invariants the test suite is built to prove:

1. For every journal entry, the sum of its postings is 0.
2. For every account, the stored balance equals the sum of its postings.
3. A customer account balance is never negative.
4. One idempotency key produces at most one journal entry.
5. Postings and journal entries are never updated or deleted.

Where possible the database enforces them as well as the application code. For example, customer accounts have a `CHECK` that blocks negative balances, and the append-only tables have triggers that reject `UPDATE` and `DELETE`.

## Architecture

```mermaid
flowchart LR
    Client -->|REST /api/v1| API[Ledger Service<br/>Spring Boot]
    API -->|JDBC, one transaction per transfer| DB[(PostgreSQL 16)]
    DB --- Outbox[outbox_events]
    Outbox -->|poller, Phase 2| Kafka[[Kafka / Redpanda]]
    Kafka --> Fraud[Fraud service]
    Kafka --> Risk[Risk-review service]
```

A transfer runs in one database transaction: it locks both accounts with `SELECT ... FOR UPDATE` in ascending id order (so opposing transfers cannot deadlock), checks funds, inserts the journal entry and two postings, updates the cached balances, and writes the outbox row. All of it commits together or not at all. The full flow, data model and reasoning are in the [design doc](docs/design.md).

## Tech stack

Java 21, Spring Boot 3 (Web, Validation, Actuator, JDBC), PostgreSQL 16, Flyway, Gradle (Kotlin DSL), JUnit 5, AssertJ, Testcontainers. The transfer path uses `JdbcClient` with explicit SQL rather than JPA, so every query and lock is visible.

## Getting started

Prerequisites: a JDK 21 or newer, and Docker.

```bash
docker compose up -d     # PostgreSQL 16 on localhost:5432
./gradlew bootRun        # starts the service; Flyway applies migrations on startup
```

Health check: <http://localhost:8080/actuator/health>

Run the tests (they start their own throwaway Postgres with Testcontainers, so Docker must be running):

```bash
./gradlew test
./gradlew test --tests "MoneyTest"   # a single test class
```

If you change a migration that has already been applied to your local database, Flyway will refuse to start. Reset the local database with `docker compose down -v`.

## Try it

With the app running (`./gradlew bootRun`), open two accounts, fund one from the seeded Treasury account, and move money. Each response carries the new account or transfer `id`. The `<alex-id>`-style parts of the commands below are placeholders, not literal text: replace each one with the real id from the earlier response.

```bash
# Open accounts (a customer account starts at a zero balance)
curl -s -X POST localhost:8080/api/v1/accounts -H 'Content-Type: application/json' -d '{"name":"Alex"}'
curl -s -X POST localhost:8080/api/v1/accounts -H 'Content-Type: application/json' -d '{"name":"Sam"}'

# Fund Alex with 100.00 CAD (10000 cents) from the Treasury system account
curl -si -X POST localhost:8080/api/v1/transfers \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: deposit-1' \
  -d '{"fromAccountId":"00000000-0000-0000-0000-000000000001","toAccountId":"<alex-id>","amount":{"value":10000,"currency":"CAD"}}'

# Alex pays Sam 25.00 CAD. Re-running this exact command replays the same response and moves no money
curl -si -X POST localhost:8080/api/v1/transfers \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: pay-1' \
  -d '{"fromAccountId":"<alex-id>","toAccountId":"<sam-id>","amount":{"value":2500,"currency":"CAD"}}'

curl -s localhost:8080/api/v1/transfers/<transfer-id>
curl -s localhost:8080/api/v1/accounts/<alex-id>

# Alex's history, newest first, two at a time; pass a response's nextCursor back as cursor for the next page
curl -s 'localhost:8080/api/v1/accounts/<alex-id>/postings?limit=2'
curl -s 'localhost:8080/api/v1/accounts/<alex-id>/postings?limit=2&cursor=<nextCursor>'
```

Amounts are whole minor units (cents). Use a new `Idempotency-Key` for each new payment, and reuse a key only to retry the same payment. Try breaking it: send more than the balance (a `422` problem naming the stored, rejected transfer), reuse a key with a different amount (`409`), omit the header (`428`), or send `"value": 12.5` (`400`).

## Project structure

```
src/main/java/dev/suvansh/ledger/
  account/ transfer/ journal/ outbox/ common/    packages by feature
src/main/resources/db/migration/                  Flyway migrations (V<n>__description.sql)
src/test/java/                                    unit and integration tests
docs/                                             design doc and ADRs
docker-compose.yml                                local PostgreSQL
```

## Status

| Area | State |
| --- | --- |
| Gradle project, docker-compose, config | Done |
| `Money` value type (minor units, overflow-safe) | Done |
| Schema (V1-V3): accounts, transfers, journal, postings, outbox, account names, seeded treasury account | Done, with constraint tests |
| Accounts endpoints: open an account (`POST /api/v1/accounts`), fetch one (`GET /api/v1/accounts/{id}`) | Done |
| Account postings history (`GET /api/v1/accounts/{id}/postings`, cursor-paginated) | Done |
| Transfer service: row locking, idempotency, validation, with concurrency and idempotency tests | Done (service layer) |
| Transfer endpoints (`POST /api/v1/transfers`, `GET /api/v1/transfers/{id}`) | Done |
| Outbox event written in the transfer transaction (`TransferCommitted`) | Done |
| Problem Details error handling (RFC 9457) | Done |
| Outbox poller and Kafka publishing | Planned (Phase 2) |
| CI: `./gradlew test` on every push (GitHub Actions) | Done |
| ADRs 0001-0005 | Done (0003 awaits the load-test comparison) |
| k6 load-test results | Planned |

Load-test numbers will be added here once the transfer endpoint exists. There are none yet.

## Limitations

- **Not production software.** It is a learning and portfolio project, built to demonstrate correctness techniques.
- **Single currency (CAD).** Accounts carry a currency, but multi-currency and FX conversion are out of scope; a cross-currency transfer will be rejected.
- **No authentication or user management.** At most a static API key later.
- **No real payment rails.** No Interac, card networks or external settlement; money only moves between accounts inside the ledger.
- **No first-class reversals or refunds.** A reversal is a new, compensating transfer.
- **At-least-once event delivery.** Consumers must deduplicate by event id.
- **Single database, single region.** Throughput numbers will be for one instance against one Postgres.
- **No transfer reference or memo field yet.** Unknown request fields are ignored.
- **Idempotency keys are global,** not scoped per client, until there is authentication.
- **No UI.** The REST API is the interface.

## Documentation

- [Design doc and phase plan](docs/design.md)
- Architecture decision records, written as decisions are made:
  - [0001: Double-entry postings instead of a single balance column](docs/adr/0001-double-entry-postings.md)
  - [0002: Integer minor units instead of BigDecimal](docs/adr/0002-integer-minor-units.md)
  - [0003: Pessimistic row locks instead of optimistic versioning](docs/adr/0003-pessimistic-locking.md) (load-test comparison pending)
  - [0004: Idempotency via a stored request hash and a unique key](docs/adr/0004-idempotency-key-and-request-hash.md)
  - [0005: Transactional outbox with polling](docs/adr/0005-transactional-outbox.md)
