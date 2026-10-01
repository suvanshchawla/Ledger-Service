# Ledger Service

Double-entry ledger for a mini fintech platform. System of record for accounts
and transfers; publishes events via a transactional outbox. Portfolio project:
correctness and readability matter more than features.
Design doc: docs/design.md. Decisions: docs/adr/.

## Stack
Java 21, Spring Boot 3, Gradle (Kotlin DSL), PostgreSQL 16, Flyway,
JUnit 5, AssertJ, Testcontainers. Package root: dev.suvansh.ledger

## Commands
- Build and test: ./gradlew test
- Run locally: docker compose up -d && ./gradlew bootRun
- Single test: ./gradlew test --tests "ClassName"

## Structure
Package by feature: account/, transfer/, journal/, outbox/, common/.
Migrations in src/main/resources/db/migration (V<n>__description.sql).

## Domain rules (never violate)
- Money is long minor units (cents) wrapped in Money. Never double or BigDecimal in the domain.
- Postings in a journal entry sum to zero. Postings and journal entries are never updated or deleted.
- Customer balances never go negative.
- One idempotency key produces at most one journal entry.

## Data access
- Transfer path uses JdbcClient with explicit SQL, not JPA.
- Lock accounts with SELECT ... FOR UPDATE in ascending id order.
- Every schema change is a new Flyway migration; never edit an applied one.

## Testing
- Every change comes with tests.
- Integration tests use Testcontainers with real Postgres, never H2 or mocks of the DB.
- Concurrency and idempotency tests are the proof of correctness; don't weaken them to make them pass.

## Working with me (learning project)
- I write TransferService, the schema, and the locking logic myself. Review and suggest; don't edit them unless I ask.
- Work in small slices: one endpoint or one test class per task.
- Explain non-obvious Spring or JVM behavior when you use it.
- Ask before adding a new dependency.
- Errors use RFC 9457 Problem Details.

## Git
Small commits, imperative messages ("Add transfer idempotency check").
