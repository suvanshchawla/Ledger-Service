# Load-test results (pessimistic locking)

Measurements of the transfer path as implemented in Phase 1: pessimistic row locks (ADR 0003), a client
idempotency key (ADR 0002) and a transactional outbox row (ADR 0005). They are from one machine and one
run per scenario (the 2,000/s ramp was run twice), so read them as an indication of behaviour, not as a
benchmark of the design.

## Environment

| | |
| --- | --- |
| Date | 2026-10-07 |
| Commit | `a8628c3` for the later runs (the first runs used `main` at `d5a51fe`; only `verify.sql` differs) |
| CPU, RAM | AMD Ryzen 5 5600H, 12 threads, 13 GB |
| Java | 25.0.1 (the build targets 21) |
| PostgreSQL | 16.15, default settings, fresh container for every run |
| k6 | v2.3.0 |
| Application | Spring Boot defaults: HikariCP pool of 10 connections, Tomcat 200 threads |
| Data | 1,000 customer accounts opened per run, each funded with 10,000.00 from the Treasury |

**k6, the application and PostgreSQL all run on this one machine**, so they compete for the same CPU.
Absolute numbers would differ on separate hosts.

## Method

- k6 uses the open arrival-rate model: requests start at a fixed rate whether or not the server keeps up. A
  slow server therefore shows up as latency and queueing, and as dropped iterations when k6 runs out of VUs.
- The ramping scenarios have four 30-second stages at 25%, 50%, 75% and 100% of `MAX_RATE` (starting at 25%).
  With `MAX_RATE=400` that is 100, 200, 300 and 400 requests/s.
- Figures are the `{phase:load}` ones; account setup traffic is excluded. A `422` (insufficient funds) counts
  as a valid outcome, any other status except 200 or 201 as a failure.
- After every run `load-test/verify.sql` checks the ledger: entries sum to zero, cached balances equal the sum
  of postings, no negative customer balance, money conserved, one journal entry and one outbox event per
  committed transfer, none for rejected ones, and no PostgreSQL deadlocks. **Every run below passed all ten checks.**
- The pool columns come from sampling the Hikari metrics once per second during the run.

Commands:

```bash
SCENARIO=baseline RATE=20 DURATION=60s ACCOUNTS=1000 load-test/run.sh
SCENARIO=<ramp|hot_receiver|treasury_deposits|retries|mixed> MAX_RATE=400 STAGE_DURATION=30s ACCOUNTS=1000 P99_MS=50 load-test/run.sh
SCENARIO=ramp MAX_RATE=2000 STAGE_DURATION=30s ACCOUNTS=1000 P99_MS=50 load-test/run.sh
```

`P99_MS=50` was a placeholder threshold chosen after the baseline; it only decides the exit code.

## Results

Latency in milliseconds unless marked `s`.

| Scenario | Requests | Failed | p50 | p90 | p95 | p99 | Max | Dropped | Peak VUs | Pool pending (max) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Baseline, constant 20/s, 60 s | 1,200 | 0 | 5.69 | 6.36 | 6.53 | 6.94 | 13.8 | 0 | n/a | 0 |
| Random pairs, ramp to 400/s | 25,499 | 0 | 3.75 | 4.47 | 4.70 | 5.70 | 40.6 | 0 | 5 | 0 |
| Mixed (70% transfers, 20% balance, 10% history), ramp to 400/s | 25,498 | 0 | 3.73 | 4.52 | 4.80 | 5.87 | 40.4 | 0 | 4 | 0 |
| Retries (10% duplicated with the same key), ramp to 400/s | 28,031 | 0 | 3.75 | 4.71 | 5.08 | 6.41 | 39.4 | 0 | 2 | 0 |
| Hot receiver (one destination account), ramp to 400/s | 25,258 | 0 | 4.16 | 319 | 629 | 1,400 | 3,880 | 241 | 329 | 96 |
| Treasury deposits (every transfer from the Treasury), ramp to 400/s | 25,185 | 0 | 4.35 | 515 | 869 | 1,670 | 4,550 | 314 | 401 | 189 |
| Random pairs, ramp to 2,000/s, run 1 | 125,734 | 0 | 4.5 | 190 | 420 | 696 | 1,568 | 1,762 | 1,082 | 189 |
| Random pairs, ramp to 2,000/s, run 2 | 126,073 | 0 | 4.44 | 179 | 358 | 603 | 1,430 | 1,420 | 958 | not recorded |

"Requests" for the retries scenario includes the duplicate sends (25,499 transfers plus about 2,500 duplicates).
For the runs that fell behind, "Dropped" is the number of iterations k6 could not start because it had run out of VUs.

## What the numbers show

- **Unloaded, a transfer takes about 6 ms** (median 5.7 ms at 20/s). That is one HTTP request, a transaction
  with two row locks and six writes (transfer, journal entry, two postings, two balance updates) and the outbox insert.
  The ramps show a lower median (about 4 ms) than the 20/s baseline. A likely reason is that at a higher request rate
  the connections, JIT-compiled code and caches stay warm, but this was not investigated.
- **With little contention the service handled 400/s without queueing.** p99 stayed under 7 ms, the pool never
  had a waiting request, and k6 needed at most 5 concurrent VUs.
- **Reads are not a problem at this rate.** The mixed run, with 30% reads, was indistinguishable from the pure
  transfer run. Plain reads take no row locks.
- **Duplicates cost under 1 ms at p99** (6.41 vs 5.70 ms). All 2,532 duplicate checks passed: a duplicate
  sent at the same time as the original got a response identical to it. The ledger check "committed transfers
  without exactly one journal entry" was 0, so no duplicate booked money.
- **Contention on one row is the dominant cost.** With every payment going to one account, or every deposit
  coming from the Treasury, the median stayed at about 4 ms but p99 rose from under 6 ms to 1.4 s and 1.7 s at the
  same 400/s peak, with up to 96 and 189 requests waiting for a pool connection. Transactions that touch the
  same row run one after another, and the tail is the queue. The Treasury case was somewhat worse than the
  hot receiver. With one run each, that difference should not be read as significant.
- **Without contention, the pool is the next limit.** Ramping to 2,000/s, the application sustained about
  980 transfers/s on average over the whole ramp; p99 reached 600 to 700 ms and 1,400 to 1,800 iterations were
  dropped. The pool hit 10 of 10 active connections, with 22 and then 189 requests pending in the last samples
  of run 1, which is consistent with connection-pool saturation. This run did not isolate the cause: CPU on the
  shared machine, Postgres commit latency and Tomcat were not measured separately, and the pool size was not varied.
- **Correctness did not depend on load.** No run produced a failed request other than the expected 422s, a
  broken ledger invariant or a PostgreSQL deadlock, including the hot-row and 2,000/s runs.

## Limits of these measurements

- One machine shared by the load generator, the application and the database.
- One run per scenario, except the 2,000/s ramp. The two 2,000/s runs differ by about 15% at p99.
- Ramps, not steady-state runs: the figures mix stages with different rates. The knee between 400/s and about
  1,000/s was not located, and the sustainable rate under contention was not measured.
- Default PostgreSQL, Hikari and Tomcat settings; no tuning was attempted.
- Not measured: lock wait time (there is no metric for it yet), CPU split between processes, and any comparison
  with another locking scheme.

## Follow-ups

- A steady-state run between 400/s and 1,000/s to locate the knee, and the same run with a larger pool
  (`spring.datasource.hikari.maximum-pool-size`) to test the pool hypothesis.
- A fixed-rate hot-row run (for example 50, 100 and 200/s) to find where the single-row queue begins.
- The optimistic-versioning experiment of ADR 0003, run against the same scenarios.
- A lock-wait metric, so contention is visible without a load test.
