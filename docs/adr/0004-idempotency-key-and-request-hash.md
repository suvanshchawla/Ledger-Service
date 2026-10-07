# 0004: Idempotency via a stored request hash and a unique key

- **Status:** Accepted
- **Date:** 2026-10-07

## Context

Clients retry. A request can succeed on the server while the response is lost, and the client cannot tell that apart from a request that never arrived. If a retry books the transfer again, money moves twice. At the same time, two different payments with the same accounts and amount are legitimate and must both be booked.

The requirement: one idempotency key produces at most one journal entry, however many times, and however concurrently, it is sent.

## Options considered

1. **Deduplicate by request content, with no key.** Cannot tell a retry from a deliberate second identical payment. Rejected.
2. **A client key backed by a unique constraint, with no hash.** Prevents double booking, but a key reused with a different request would silently return the first transfer's result, hiding a client bug. Rejected as too lenient.
3. **A client-supplied key, a unique constraint, and a stored hash of the request.** **Chosen.**
4. **A separate idempotency store (a table or Redis) with expiry.** Allows retention limits, but adds a second place to keep consistent with the transfer. Not needed while keys live on the transfer row.

## Decision

Every transfer request must carry an `Idempotency-Key` header, chosen by the client (one per intended payment, reused only to retry that payment).

- **Storage:** `transfers.idempotency_key` is `UNIQUE`, and `transfers.request_hash` stores the SHA-256 of the canonical request (`from|to|amount`). The database constraint is the last line of defense even if the application logic has a bug.
- **Lookup first, outside the transaction.** The same key with the same hash returns the stored result. The same key with a different hash is a 409. A new key proceeds to the transaction.
- **The race.** Parallel requests with one key can all pass the lookup. They serialize on the account locks (ADR 0003); the first inserts its transfer row and commits, and each loser's insert hits the unique constraint, which rolls back its transaction. The loser catches the `DuplicateKeyException` outside the transaction, repeats the lookup, and returns the winner's result. The `transfers` row is inserted before the journal entry and postings, so a duplicate fails before anything else is written.
- **Replays are byte-identical.** The HTTP response is built from the stored row, so a replay returns the same status, the same transfer id and the same body.
- **Rejections are stored.** An insufficient-funds transfer is saved as `REJECTED` and consumes its key; a replay returns the same 422 naming the same transfer, even if the balance has since changed. Requests that fail validation (400, a missing account, a currency mismatch) are not stored, because they book nothing.
- **Header rules:** missing is a 428; blank or over 255 characters is a 400.

## Consequences

**Positive**

- At most one booking per key, proven by tests: 50 parallel requests with one key produce one transfer, one journal entry and one outbox event; sequential replays, reuse with a different body, and replayed rejections are covered too.
- Safe client retries, and a clear error when a key is misused.

**Negative and risks**

- **Keys are global, not per client.** Two clients choosing the same key would collide. Acceptable without authentication; revisit when there is some.
- **Keys are kept forever.** Whether to expire them (Stripe uses 24 hours) is an open question; it would need a retention rule for the transfer rows or a separate store.
- **The hash covers only what is in the canonical string.** Any new request field must be added to it, or a changed field would wrongly replay the old result.
- **A deliberate repeat needs a new key.** A client that creates two keys for one intended payment, such as a double click, books it twice. That is a documented client responsibility.
- A stored rejection means a retry with the same key will not see a later balance increase; the client must use a new key.

## References

- Design doc, "API design" and "Consistency, concurrency and idempotency": [docs/design.md](../design.md)
- `TransferService.transfer`, `TransferController`; `TransferIdempotencyTest`, `TransferEndpointTest`
