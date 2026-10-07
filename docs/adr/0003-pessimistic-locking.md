# 0003: Pessimistic row locks instead of optimistic versioning

- **Status:** Accepted for now. The comparison with the optimistic alternative, and the load-test numbers that should settle it, are still pending.
- **Date:** 2026-10-07

## Context

A transfer reads the source balance, decides whether the funds suffice, and writes new balances. Done naively, two concurrent transfers both read the same balance, both see enough, and both proceed. This is a race, and it is not hypothetical here: against a version with no locking, 100 parallel withdrawals of 100 from an account holding 1,000 drove the balance negative until the database's own `CHECK` constraint stopped them.

Two accounts are involved in every transfer, so there is a second hazard. A→B and B→A running together can each hold one account and wait for the other: a deadlock.

## Options considered

1. **No locking.** Rejected: the concurrency tests fail.
2. **Pessimistic row locks.** Lock both account rows with `SELECT ... FOR UPDATE` inside the transfer's transaction, in a fixed order. Concurrent transfers on the same account queue. **Chosen.**
3. **Optimistic versioning.** A `version` column; update with `WHERE version = :seen` and retry on conflict. No locks held, but on a hot account most attempts conflict and must retry. Not implemented yet; the plan is to build it as an experiment and compare (see Follow-ups).
4. **`SERIALIZABLE` isolation with retries.** The database detects conflicts itself. Not evaluated; it would need the same retry machinery.
5. **A conditional atomic update** (`UPDATE ... SET balance = balance - :x WHERE id = :id AND balance >= :x`). Not evaluated; it checks funds without an explicit read, but two-account ordering and the journal writes still need care.

## Decision

Use pessimistic locking at the default `READ COMMITTED` isolation.

- Each transfer runs in one transaction. Its first statement locks both accounts at once: `SELECT ... WHERE id IN (:from, :to) ORDER BY id FOR UPDATE`. Every transfer locks in ascending id order, so opposing transfers cannot deadlock.
- The ordering is done in SQL, not in Java. PostgreSQL sorts UUIDs bytewise, while `java.util.UUID.compareTo` compares signed longs, so the two orders can disagree and sorting in Java could reintroduce the deadlock. The result is therefore sorted by id, not source-first, and the source row is picked out by id.
- A transaction that waits for a lock re-reads the row once it is released, so it decides on the balance as updated by the transaction before it.
- The funds check, the idempotency insert, the journal entry, the postings, the balance updates and the outbox row all happen while the locks are held, and commit together.

The tests that back this up: 100 parallel withdrawals from an account that can afford exactly 10 commit exactly 10; 1,000 opposing transfers between two accounts finish without a deadlock; 1,000 random parallel transfers across five accounts leave every ledger invariant intact (ADR 0001).

## Consequences

**Positive**

- Simple to reason about and to prove: no retry loop, and the check and the update cannot interleave with another transfer on the same account.
- Contended accounts queue instead of failing and retrying.

**Negative and risks**

- **Contention is a throughput ceiling.** Transfers touching the same account run one after another. The Treasury is the clearest hot spot: every deposit locks it. If deposits ever need to scale, that points to several Treasury accounts, not to a different locking scheme.
- Locks are held for the whole transaction, including the idempotency and outbox writes, so the transaction must stay short. Lock wait time should be a metric.
- Long waits can pile up threads and connections under load; the pool size and timeouts matter.
- **No performance numbers yet.** This decision rests on correctness tests, not on a throughput or latency comparison. Nothing here should be read as a claim that it is faster than the alternatives.

## Follow-ups

- Build the optimistic version as an experiment and run both under k6 at several contention levels, including the hot-account case. Record transfers per second and p50/p95/p99 latency, then update this ADR with the numbers and confirm or change the decision.
- Add a lock-wait metric (design doc, Observability).

## References

- Design doc, "Consistency, concurrency and idempotency": [docs/design.md](../design.md)
- `TransferService.book`; `TransferConcurrencyTest`, `TransferFundsTest`
