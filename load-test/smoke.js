// One pass through the whole API with a single user: if this fails, nothing else is worth running.
//   k6 run -e BASE_URL=http://localhost:8080 load-test/smoke.js
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const TREASURY = '00000000-0000-0000-0000-000000000001';
const JSON_HEADERS = { 'Content-Type': 'application/json' };

// The deliberate overdraw below is a 422; that is an expected outcome, not a failed request.
http.setResponseCallback(http.expectedStatuses(200, 201, 422));

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { checks: ['rate==1'] }, // every check must pass
};

function transfer(key, from, to, cents) {
  const body = JSON.stringify({ fromAccountId: from, toAccountId: to, amount: { value: cents, currency: 'CAD' } });
  return http.post(`${BASE}/api/v1/transfers`, body, {
    headers: Object.assign({ 'Idempotency-Key': key }, JSON_HEADERS),
  });
}

export default function () {
  const run = `smoke-${Date.now()}`;

  const alex = http.post(`${BASE}/api/v1/accounts`, JSON.stringify({ name: `${run}-alex` }), { headers: JSON_HEADERS });
  const sam = http.post(`${BASE}/api/v1/accounts`, JSON.stringify({ name: `${run}-sam` }), { headers: JSON_HEADERS });
  check(alex, { 'open account: 201': (r) => r.status === 201 });
  check(sam, { 'open account: 201': (r) => r.status === 201 });
  const alexId = alex.json('id');
  const samId = sam.json('id');

  const deposit = transfer(`${run}-deposit`, TREASURY, alexId, 10000);
  check(deposit, { 'deposit from the Treasury: 201': (r) => r.status === 201 });

  const pay = transfer(`${run}-pay`, alexId, samId, 2500);
  check(pay, { 'transfer: 201': (r) => r.status === 201 });
  const replay = transfer(`${run}-pay`, alexId, samId, 2500);
  check(replay, { 'replay returns the identical response': (r) => r.status === 201 && r.body === pay.body });

  const alexNow = http.get(`${BASE}/api/v1/accounts/${alexId}`);
  const samNow = http.get(`${BASE}/api/v1/accounts/${samId}`);
  check(alexNow, { 'payer balance is 7500': (r) => r.json('balance.value') === 7500 });
  check(samNow, { 'payee balance is 2500': (r) => r.json('balance.value') === 2500 });

  const tooMuch = transfer(`${run}-big`, samId, alexId, 999999);
  check(tooMuch, { 'overdraw is a 422 naming the rejected transfer': (r) => r.status === 422 && !!r.json('transferId') });

  const history = http.get(`${BASE}/api/v1/accounts/${alexId}/postings?limit=1`);
  check(history, {
    'history: 200 with one item and a next cursor': (r) => r.status === 200 && r.json('items').length === 1 && r.json('nextCursor') !== null,
  });
}
