// Per-endpoint benchmark scenarios (Sprint 23, Task 23.1).
// Run:  k6 run -e SCENARIO=hit    benchmarks/k6-scenario.js
//       k6 run -e SCENARIO=miss   benchmarks/k6-scenario.js   (after cache flush)
//       k6 run -e SCENARIO=create benchmarks/k6-scenario.js
//       k6 run -e SCENARIO=stats  benchmarks/k6-scenario.js
//       k6 run -e SCENARIO=delete benchmarks/k6-scenario.js
//       k6 run -e SCENARIO=spike10k benchmarks/k6-scenario.js
// 'miss' consumes benchmarks/links.json codes (benchmarks/prepare.py);
// each code is requested at most once per run, so every request is a
// genuine PostgreSQL lookup.

import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const SCENARIO = __ENV.SCENARIO || 'hit';
const DURATION = __ENV.DURATION || '30s';

const links = new SharedArray('links', () =>
    JSON.parse(open('./links.json')).codes);

const executors = {
  hit:      { executor: 'constant-vus', vus: 50, duration: DURATION },
  miss:     { executor: 'constant-vus', vus: 30, duration: DURATION },
  create:   { executor: 'constant-vus', vus: 30, duration: DURATION },
  stats:    { executor: 'constant-vus', vus: 50, duration: DURATION },
  delete:   { executor: 'constant-vus', vus: 20, duration: DURATION },
  spike10k: {
    executor: 'constant-arrival-rate',
    rate: 3000, timeUnit: '1s',
    duration: '4s',
    preAllocatedVUs: 500, maxVUs: 1000,
  },
};

export const options = {
  scenarios: { main: executors[SCENARIO] },
  thresholds: { http_req_failed: ['rate<0.01'] },
};

export default function () {
  const suffix = `${exec.vu.idInInstance}-${exec.vu.iterationInInstance}`;
  if (SCENARIO === 'hit' || SCENARIO === 'spike10k') {
    const r = http.get(`${BASE}/bench-hit`, { redirects: 0 });
    check(r, { '302': (x) => x.status === 302 });
  } else if (SCENARIO === 'miss') {
    const code = links[exec.vu.iterationInInstance % links.length];
    const r = http.get(`${BASE}/${code}`, { redirects: 0 });
    check(r, { '302': (x) => x.status === 302 });
  } else if (SCENARIO === 'create') {
    const r = http.post(`${BASE}/api/v1/links`,
        JSON.stringify({ originalUrl: 'https://example.com/bench', alias: `c-${suffix}` }),
        { headers: { 'Content-Type': 'application/json' } });
    check(r, { '201': (x) => x.status === 201 });
  } else if (SCENARIO === 'stats') {
    const r = http.get(`${BASE}/api/v1/links/${links[0]}/stats`);
    check(r, { '200': (x) => x.status === 200 });
  } else if (SCENARIO === 'delete') {
    const alias = `d-${suffix}`;
    const created = http.post(`${BASE}/api/v1/links`,
        JSON.stringify({ originalUrl: 'https://example.com/bench', alias }),
        { headers: { 'Content-Type': 'application/json' } });
    if (created.status === 201) {
      const deleted = http.del(`${BASE}/api/v1/links/${alias}`);
      check(deleted, { '204': (x) => x.status === 204 });
    }
  }
}
