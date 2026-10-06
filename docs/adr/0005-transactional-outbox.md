# 0005: Transactional outbox with polling

- **Status:** Accepted (event writing implemented; the polling publisher is planned for Phase 2)
- **Date:** 2026-10-06

## Context

Every committed transfer must be announced to other services (fraud, risk review) through Kafka. The ledger holds the money, so the one failure that is not acceptable is a transfer that committed with no event: a downstream service would never learn about money that moved.

A naive implementation does two steps in the request: commit to PostgreSQL, then publish to Kafka. These are two separate systems, so no ordering of the two steps is safe. This is the dual-write problem:

- Commit, then crash before publishing: the money moved and nobody was told.
- Publish, then the commit fails: consumers act on a transfer that never happened.

## Options considered

1. **Dual write (commit, then publish; or the reverse).** Simple, but both orderings have a failure window that loses or invents events. Rejected.
2. **Distributed transaction (two-phase commit) across PostgreSQL and Kafka.** Kafka cannot take part in an XA transaction, and this would couple the availability of the two systems anyway. Rejected.
3. **Transactional outbox with a polling publisher.** Write the event as a row in the ledger's own database, in the same transaction as the transfer. A separate publisher reads unpublished rows and sends them to Kafka. **Chosen.**
4. **Transactional outbox with change data capture (Debezium).** The same outbox table, but a CDC connector reads PostgreSQL's write-ahead log and streams new rows to Kafka, with no polling. Lower latency, but it adds infrastructure to run (Kafka Connect, a replication slot, WAL configuration). Deferred, not rejected: it is a later upgrade that keeps the same table and the same event.

## Decision

Use the transactional outbox with a polling publisher.

**Writing (implemented).** `TransferService` inserts one row into `outbox_events` inside the same database transaction as the transfer, journal entry, postings and balance updates, but only when the transfer is `COMMITTED`. All of it commits together or not at all, so an event exists if and only if the transfer committed. If the event insert fails, the transfer rolls back and the client retries safely with the same idempotency key.

- `aggregate_id` is the transfer id; the row `id` is the event id and is repeated in the payload as `eventId`.
- The payload is versioned JSON (`schemaVersion: 1`) stored as `JSONB`, with exactly these fields: `eventId`, `eventType`, `schemaVersion`, `occurredAt`, `transferId`, `fromAccountId`, `toAccountId`, `amount` (`value`, `currency`). It deliberately carries no account names: an event states a fact about the transfer, and a consumer that needs a name can look it up.
- Rejected transfers, requests that fail validation, and replays of an idempotency key write no event.

**Publishing (planned, Phase 2).**

- A scheduled poller selects unpublished rows with `SELECT ... FOR UPDATE SKIP LOCKED LIMIT 100`, so several instances can run without publishing the same row at once. A partial index on `published_at IS NULL` already supports this query.
- It sends each event to the `ledger.transfers` topic, keyed by `fromAccountId`, and sets `published_at` after Kafka acknowledges.
- A cleanup job deletes published rows after 7 days.

## Consequences

**Positive**

- The event and the money movement cannot disagree: no distributed transaction, no lost or phantom events.
- Phase 1 needs no Kafka. The guarantee is testable with plain PostgreSQL, and the tests assert the exact payload, one event per committed transfer, and none for rejected, invalid or replayed requests (including 50 parallel requests with one key).
- The event shape is a stable contract; `schemaVersion` allows it to evolve.

**Negative and risks**

- **At-least-once delivery.** If the poller sends an event and crashes before setting `published_at`, it sends it again. Consumers must deduplicate on `eventId`. "Exactly once" in this system means one booking and one event row, not one delivery.
- **Ordering.** Keying by `fromAccountId` orders events per sending account. Events for the receiving account can land in different partitions, so ordering from the receiver's point of view is not guaranteed.
- **Latency.** Publishing lags by up to one polling interval, and a stuck publisher lets outbox lag grow silently. Outbox lag (age of the oldest unpublished row) should be a metric and an alert.
- **Extra write per transfer.** Every committed transfer writes one more row, in the same database that serves transfers. The cost has not been measured yet; the k6 load test should compare throughput with and without it.
- **Coupled availability.** A transfer cannot commit if its event row cannot be written. This is intended.
- The table grows until cleanup runs, and the poller and cleanup job still have to be built.

## Follow-ups

- Build the poller, the cleanup job and the outbox lag metric (Phase 2).
- Revisit the partition key if a consumer needs per-receiver ordering.
- Re-evaluate Debezium CDC once polling latency or load becomes a problem.

## References

- Design doc, "Events and transactional outbox": [docs/design.md](../design.md)
- Implementation: `TransferService.insertOutboxEvent`; tests: `TransferOutboxTest`
