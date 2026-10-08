# Load tests

[k6](https://k6.io) scripts that drive the real HTTP API, plus a runner that gives every run a fresh
database and checks the ledger afterwards. A throughput number is only recorded if the ledger is
still consistent.

## Files

| File | Purpose |
| --- | --- |
| `smoke.js` | One pass through the whole API with one user. Run it first. |
| `transfers.js` | The load scenarios (below). |
| `verify.sql` | Ledger invariants checked after a run; every count must be 0. |
| `run.sh` | Starts PostgreSQL and the app, runs a scenario, samples the connection pool, verifies, cleans up. |
| `results/` | Raw output of each run (git-ignored). Curated results are written up under `docs/`. |

## Running

```bash
# the smoke test, on a fresh stack (or: k6 run load-test/smoke.js against an app already running on :8080)
SCENARIO=smoke load-test/run.sh

# one scenario on a fresh stack, with ledger verification
SCENARIO=baseline RATE=20 DURATION=60s load-test/run.sh
SCENARIO=ramp MAX_RATE=400 STAGE_DURATION=30s ACCOUNTS=1000 load-test/run.sh
```

Needs Docker, a JDK and k6. Run one scenario at a time: they share the same database and would interfere.

## Scenarios

All scenarios use k6's *open* arrival-rate model: requests start at a fixed rate whether or not the
server keeps up, so a slow server shows up as latency and queueing instead of silently reducing the load.

| `SCENARIO` | Load shape | What it shows |
| --- | --- | --- |
| `baseline` | Constant `RATE` requests/s for `DURATION` | Unloaded latency, the reference for everything else |
| `ramp` | Four stages up to `MAX_RATE`, each `STAGE_DURATION`; random account pairs | Throughput and where latency starts to climb, with little contention |
| `hot_receiver` | As `ramp`, but every payment goes to one account | The cost of row-lock contention |
| `treasury_deposits` | As `ramp`, but every transfer is a deposit from the Treasury | The Treasury hot spot (every deposit locks one row) |
| `retries` | As `ramp`; 10% of requests are sent twice at once with the same key | Idempotency under load; duplicates must get identical responses |
| `mixed` | As `ramp`; 70% transfers, 20% balance reads, 10% history pages | Reads and writes together |

Settings (environment variables): `ACCOUNTS` (default 200), `OPENING_BALANCE` in cents (default 1,000,000),
`RATE` (20), `MAX_RATE` (400), `DURATION` (30s), `STAGE_DURATION` (30s), and optionally `P99_MS`, a p99 latency
threshold. No latency target is built in: choose one after looking at the baseline.

Setup requests (opening and funding accounts) are tagged `phase:setup` and excluded: the figures that count are the `{phase:load}` ones in the k6 summary. A `422` (insufficient funds) is an expected outcome and not counted as a failure. Anything else other than
`200` or `201` is, and the run fails if more than 1% of requests fail.

## What a run checks

`verify.sql` runs after k6 and fails the run if any of these is non-zero:

- journal entries whose postings do not sum to zero
- accounts whose cached balance differs from the sum of their postings
- customer accounts with a negative balance
- money not conserved (the balances of all accounts must sum to 0)
- committed transfers without exactly one journal entry, rejected transfers with one, and entries without exactly two postings
- committed transfers without exactly one outbox event, and outbox events for transfers that did not commit
- deadlocks detected by PostgreSQL

## Caveats

- k6, the app and PostgreSQL run on one machine and compete for CPU, so the numbers are indicative, not production figures.
- The app and the database use default settings. The Hikari connection pool allows 10 connections by default, which may be the first limit reached; `*-pool.csv` shows active and pending connections once a second.
- Each run records the environment in `results/*-env.txt`.
