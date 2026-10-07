# 0001: Double-entry postings instead of a single balance column

- **Status:** Accepted
- **Date:** 2026-10-07

## Context

The ledger is the system of record for money. It must be able to answer not only "what is this account's balance?" but "where did that money come from, and can you prove the books balance?". It must also never create or destroy money: every unit that leaves one account arrives in another.

## Options considered

1. **A mutable balance column per account** (optionally with an audit log). Simplest, and fast to read. But the balance is the only record: history is whatever someone remembered to log, a bug silently changes money, and there is nothing to reconcile against. Rejected.
2. **Event sourcing**, where an append-only event log is the only source of truth and balances are always derived from it. Strong audit story, but every balance read replays or maintains projections, and it is a lot of machinery for a service whose data model is already naturally a ledger. Rejected as heavier than needed.
3. **Double-entry bookkeeping**: every transfer is a journal entry made of postings that sum to zero, kept append-only, with a cached balance on the account for fast reads. **Chosen.**

## Decision

Model money movement as double-entry.

- A committed transfer produces one `journal_entries` row (linked one-to-one to its transfer) and two `postings` rows: `-amount` on the source account and `+amount` on the destination. The postings of an entry sum to zero.
- `journal_entries` and `postings` are append-only. Triggers reject any `UPDATE` or `DELETE` on them, so immutability is enforced by the database, not by convention. A mistake is corrected with a new compensating transfer, never by editing history.
- `accounts.balance_minor` is a **cached** balance for cheap reads. The postings are the truth. The balance is updated in the same transaction as the postings, while the account row is locked.
- Money enters and leaves the system through a seeded SYSTEM account (the Treasury), which may go negative. CUSTOMER accounts may not: a `CHECK` constraint backs up the application check.
- Zero-sum per entry is enforced in the service code and proven by tests, not by a database constraint.

The invariants the test suite checks, with SQL, after concurrent runs:

1. For every journal entry, the sum of its postings is 0.
2. For every account, the cached balance equals the sum of its postings.
3. No CUSTOMER balance is negative.
4. The balances of all accounts involved, funding accounts included, sum to zero: money is conserved.

## Consequences

**Positive**

- A complete, tamper-resistant history: the account history endpoint is a plain read of postings, and every balance can be re-derived and audited.
- The invariants are checkable by queries, which is what makes the concurrency tests meaningful.
- Reversals and refunds need no special mechanism; they are ordinary transfers.

**Negative and risks**

- **Two sources of truth.** The cached balance can drift from the postings if a bug updates one without the other. Mitigated by updating both in one transaction under the row lock, and by the reconciliation check in the tests.
- **More writes per transfer:** a transfer row, a journal entry, two postings, two balance updates and an outbox row. The cost is unmeasured; the k6 load test should report it.
- The zero-sum rule is not a database constraint, so a future code path that bypasses the service could violate it. A deferred constraint trigger could close that gap if it ever matters.
- The Treasury is a single account that every deposit touches (see ADR 0003).

## References

- Design doc, "Domain model" and "Data model": [docs/design.md](../design.md)
- Schema: `V1__init.sql` (tables and triggers), `V3__seed_treasury_account.sql`
- Tests: `SchemaConstraintsTest`, `LedgerFixture.assertInvariantsHold`, `TransferConcurrencyTest`
