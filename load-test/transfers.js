// Load scenarios for the ledger service. Run one at a time (they would interfere with each other):
//   SCENARIO=ramp MAX_RATE=400 load-test/run.sh        # recommended: fresh database, verification, record
//   k6 run -e SCENARIO=baseline load-test/transfers.js # against an app that is already running
//
// Scenarios (all use the open arrival-rate model: k6 starts requests at a fixed rate whether or
// not the server keeps up, so slow responses show up as latency instead of silently lowering the load):
//   baseline           constant RATE; random account pairs; the unloaded latency reference
//   ramp               ramps to MAX_RATE; random pairs, little contention; finds the throughput knee
//   hot_receiver       ramps; every payment goes to ONE account (row-lock contention)
//   treasury_deposits  ramps; every transfer is a deposit from the Treasury (the built-in hot spot)
//   retries            ramps; 10% of requests are sent twice at once with the same key (idempotency race)
//   mixed              ramps; 70% transfers, 20% balance reads, 10% history pages
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const TREASURY = '00000000-0000-0000-0000-000000000001';
const SCENARIO = __ENV.SCENARIO || 'baseline';
const ACCOUNTS = Number(__ENV.ACCOUNTS || 200);
const OPENING_BALANCE = Number(__ENV.OPENING_BALANCE || 1000000); // cents, per account
const RATE = Number(__ENV.RATE || 20); // baseline requests per second
const MAX_RATE = Number(__ENV.MAX_RATE || 400); // peak requests per second of the ramping scenarios
const DURATION = __ENV.DURATION || '30s'; // baseline duration
const STAGE = __ENV.STAGE_DURATION || '30s'; // each of the four ramp stages
const JSON_HEADERS = { 'Content-Type': 'application/json' };
const SETUP = { phase: 'setup' };

// A 422 is a normal, valid outcome (insufficient funds); everything else but 200/201 counts as a failure.
http.setResponseCallback(http.expectedStatuses(200, 201, 422));

const rejected = new Counter('transfers_rejected_422');

function constantRate(exec) {
  return { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DURATION,
           preAllocatedVUs: 50, maxVUs: 1000, exec };
}

function rampingRate(exec) {
  const stage = (share) => ({ target: Math.round(MAX_RATE * share), duration: STAGE });
  return { executor: 'ramping-arrival-rate', startRate: Math.round(MAX_RATE * 0.25), timeUnit: '1s',
           preAllocatedVUs: 100, maxVUs: 2000, stages: [stage(0.25), stage(0.5), stage(0.75), stage(1)], exec };
}

const SCENARIOS = {
  baseline: constantRate('randomTransfer'),
  ramp: rampingRate('randomTransfer'),
  hot_receiver: rampingRate('hotReceiver'),
  treasury_deposits: rampingRate('treasuryDeposit'),
  retries: rampingRate('retriedTransfer'),
  mixed: rampingRate('mixedTraffic'),
};

if (!SCENARIOS[SCENARIO]) {
  throw new Error(`Unknown SCENARIO '${SCENARIO}'. Choose one of: ${Object.keys(SCENARIOS).join(', ')}`);
}

// Setup requests are tagged phase:setup and excluded from every figure that matters. The first
// latency threshold is always true: it only makes k6 report the load phase's percentiles separately.
const thresholds = {
  'http_req_failed{phase:load}': ['rate<0.01'], // 5xx, 400, 404, 409 and so on; not 422
  'http_req_duration{phase:load}': ['max>=0'],
  checks: ['rate>0.99'],
};
// No latency target is hard-coded: choose one after looking at the baseline, e.g. -e P99_MS=250
if (__ENV.P99_MS) {
  thresholds['http_req_duration{phase:load}'] = [`p(99)<${__ENV.P99_MS}`];
}

export const options = {
  scenarios: { [SCENARIO]: SCENARIOS[SCENARIO] },
  thresholds,
  setupTimeout: '15m',
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// ---- setup: not measured ----

export function setup() {
  const runId = String(Date.now());
  const open = (name) => {
    const res = http.post(`${BASE}/api/v1/accounts`, JSON.stringify({ name }), { headers: JSON_HEADERS, tags: SETUP });
    if (res.status !== 201) throw new Error(`could not open account ${name}: ${res.status} ${res.body}`);
    return res.json('id');
  };

  const accounts = [];
  for (let i = 0; i < ACCOUNTS; i++) {
    const id = open(`lt-${runId}-${i}`);
    // Funded one at a time on purpose: every deposit locks the Treasury row.
    const res = deposit(`lt-${runId}-fund-${i}`, id, OPENING_BALANCE, SETUP);
    if (res.status !== 201) throw new Error(`could not fund account ${id}: ${res.status} ${res.body}`);
    accounts.push(id);
  }
  return { runId, accounts, hot: open(`lt-${runId}-hot`) };
}

// ---- helpers ----

function transferBody(from, to, cents) {
  return JSON.stringify({ fromAccountId: from, toAccountId: to, amount: { value: cents, currency: 'CAD' } });
}

function postTransfer(key, body, tags) {
  return http.post(`${BASE}/api/v1/transfers`, body, {
    headers: Object.assign({ 'Idempotency-Key': key }, JSON_HEADERS),
    tags: tags || { name: 'POST /transfers', phase: 'load' },
  });
}

function deposit(key, to, cents, tags) {
  return postTransfer(key, transferBody(TREASURY, to, cents), tags);
}

// Unique per virtual user and iteration, and distinct from every other run.
function keyFor(data) {
  return `lt-${data.runId}-${__VU}-${__ITER}`;
}

function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

function pickTwoDifferent(list) {
  const from = Math.floor(Math.random() * list.length);
  const to = (from + 1 + Math.floor(Math.random() * (list.length - 1))) % list.length;
  return [list[from], list[to]];
}

function cents() {
  return 1 + Math.floor(Math.random() * 100);
}

function checkTransfer(res) {
  if (res.status === 422) rejected.add(1);
  return check(res, { 'transfer: 201 or 422': (r) => r.status === 201 || r.status === 422 });
}

// ---- scenario functions ----

export function randomTransfer(data) {
  const [from, to] = pickTwoDifferent(data.accounts);
  checkTransfer(postTransfer(keyFor(data), transferBody(from, to, cents())));
}

export function hotReceiver(data) {
  checkTransfer(postTransfer(keyFor(data), transferBody(pick(data.accounts), data.hot, cents())));
}

export function treasuryDeposit(data) {
  checkTransfer(deposit(keyFor(data), pick(data.accounts), cents()));
}

export function retriedTransfer(data) {
  const [from, to] = pickTwoDifferent(data.accounts);
  const key = keyFor(data);
  const body = transferBody(from, to, cents());
  if (Math.random() >= 0.1) {
    checkTransfer(postTransfer(key, body));
    return;
  }
  // A client retry racing the original: both requests in flight at once, same key and body.
  const params = { headers: Object.assign({ 'Idempotency-Key': key }, JSON_HEADERS), tags: { name: 'POST /transfers (duplicate)', phase: 'load' } };
  const [a, b] = http.batch([['POST', `${BASE}/api/v1/transfers`, body, params], ['POST', `${BASE}/api/v1/transfers`, body, params]]);
  checkTransfer(a);
  check([a, b], { 'duplicates get the identical response': ([x, y]) => x.status === y.status && x.body === y.body });
}

export function mixedTraffic(data) {
  const roll = Math.random();
  if (roll < 0.7) {
    randomTransfer(data);
  } else if (roll < 0.9) {
    const res = http.get(`${BASE}/api/v1/accounts/${pick(data.accounts)}`, { tags: { name: 'GET /accounts/{id}', phase: 'load' } });
    check(res, { 'balance: 200': (r) => r.status === 200 });
  } else {
    const res = http.get(`${BASE}/api/v1/accounts/${pick(data.accounts)}/postings?limit=20`, { tags: { name: 'GET /accounts/{id}/postings', phase: 'load' } });
    check(res, { 'history: 200': (r) => r.status === 200 });
  }
}
