# 0003: Pessimistic row locks instead of optimistic versioning

- **Status:** Accepted. Load-test numbers for this approach are recorded; the comparison with the optimistic alternative is still pending.
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
- **Measured cost of contention.** In the k6 runs ([results](../load-test-results.md)), on one shared laptop and one run per scenario, random account pairs held a p99 under 7 ms up to 400 transfers/s. With every payment going to one account, or every deposit from the Treasury, p99 at the same 400/s peak rose to about 1.4 s and 1.7 s while requests queued for a pool connection. No invariant broke and PostgreSQL reported no deadlocks in any run. These numbers describe this approach only; nothing here claims it is faster or slower than the alternatives, which have not been measured.

## Follow-ups

- Build the optimistic version as an experiment and run it under the same k6 scenarios, including the hot-account case, then update this ADR with the comparison and confirm or change the decision. The pessimistic side is already recorded, so the comparison can reuse those scenarios and settings.
- Add a lock-wait metric (design doc, Observability).

## References

- Load-test results: [docs/load-test-results.md](../load-test-results.md)
- Design doc, "Consistency, concurrency and idempotency": [docs/design.md](../design.md)
- `TransferService.book`; `TransferConcurrencyTest`, `TransferFundsTest`
