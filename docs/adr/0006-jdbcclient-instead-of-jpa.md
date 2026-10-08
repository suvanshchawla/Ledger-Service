# 0006: JdbcClient with explicit SQL instead of JPA on the transfer path

- **Status:** Accepted
- **Date:** 2026-10-08

## Context

The transfer path is where the ledger's guarantees are enforced. It has to lock two accounts in a fixed order (ADR 0003), insert the transfer row before anything else so a duplicate idempotency key fails first (ADR 0004), write a journal entry, its postings and an outbox row, and update two balances, all in one transaction. Several of these properties depend on the exact statements and the order they run in.

The choice is between letting an ORM generate that SQL and writing it by hand.

## Options considered

1. **Spring Data JPA / Hibernate for everything.** Little code for simple CRUD. But the SQL is generated, and the properties above become configuration and framework behavior:
   - Locking is requested through `@Lock` or `LockModeType`. The `FOR UPDATE` statement still ends up in the database, but getting two rows locked in one statement in ascending id order means a custom query anyway, and it is hard to see from the code what ran.
   - Hibernate flushes pending changes at times it chooses (before queries, at commit), and it orders the writes itself. "The transfer row is inserted before the postings" would depend on flush behavior rather than on the order of lines in the method.
   - A `DuplicateKeyException`-style failure can surface at flush or commit instead of at the statement that caused it, which complicates the retry-by-lookup in ADR 0004.
   - Entities are mutable by default, which sits badly with postings and journal entries that are never updated or deleted.
2. **JPA for most things, JdbcClient for the transfer path only.** Keeps the object mapping for accounts, at the cost of two data-access styles over the same tables, and a persistence context that can hold stale copies of rows the transfer path just changed.
3. **`JdbcClient` with explicit SQL everywhere.** **Chosen.** `JdbcClient` is Spring's fluent wrapper over `JdbcTemplate`; it takes the SQL as a string, binds named parameters, and maps rows with a lambda.
4. **jOOQ or another SQL builder.** Type-checked SQL is attractive, but it adds a dependency and a code-generation step to a project this small.

## Decision

All data access uses `JdbcClient` and hand-written SQL, in the repositories (`AccountRepository`, `TransferRepository`, `PostingRepository`) and in `TransferService`. There is no Spring Data JPA dependency and no entities.

- **One statement is one round trip, in source order.** `TransferService.book` locks both accounts with a single `SELECT ... WHERE id IN (...) ORDER BY id FOR UPDATE`, then inserts, then updates. What the method reads is what the database executes, in that order.
- **No persistence context.** There is no first-level cache and no dirty checking, so no flush timing to reason about, and no stale entity after a balance update.
- **Transactions are explicit.** `TransactionTemplate` wraps `book`, and the `DuplicateKeyException` is caught outside it. This also avoids the `@Transactional` proxy pitfalls (self-invocation skips the proxy; the annotation only works on calls that go through the Spring bean), because the transaction boundary is a visible call, not an annotation.
- **Rows map to records.** Row results become immutable Java records, which matches the append-only rule for postings and journal entries.
- **Schema stays in Flyway.** The migration files are the only definition of the schema; there is no entity model to keep in agreement with it, and no `ddl-auto`.

## Consequences

**Positive**

- Locks, statement order and the transaction boundary can be read directly in the code and checked against the concurrency and idempotency tests.
- Every query can be pasted into `psql` and run with `EXPLAIN`.
- Fewer moving parts: no entity lifecycle, no lazy loading, no flush modes.
- It is easy to explain, which matters for a portfolio project.

**Negative and risks**

- **More boilerplate.** Each query needs its SQL, its parameter binding and its row mapper written out.
- **No compile-time checking of SQL or column names.** A typo in a column or a changed type shows up as a runtime failure. The Testcontainers tests against real Postgres are what catch it.
- **Mapping is manual.** Adding a column means updating the SQL, the mapper and the record together.
- **Dialect lock-in.** The SQL uses PostgreSQL features (`FOR UPDATE`, `RETURNING`). That is accepted: the correctness argument already depends on Postgres behavior.
- **Nothing here was measured against JPA.** This decision is about visibility and control, not speed, and no JPA version of the service was built.
- If the service later grows plain CRUD with no concurrency concerns (for example reporting or admin screens), JPA could be reconsidered for those parts only. That would be a new decision.

## References

- ADR 0003 (lock order) and ADR 0004 (insert order and duplicate-key handling)
- Design doc, "Tech stack": [docs/design.md](../design.md)
- `TransferService.book`, `AccountRepository`, `TransferRepository`, `PostingRepository`
