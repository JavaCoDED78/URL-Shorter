// Performance baseline (Sprint 23): full load profile on the cache-hit
// redirect path — warm-up → sustained → spike → cool-down (Task 23.3).
// Run:  k6 run benchmarks/k6-main.js
// Needs a primed 'bench-hit' link (benchmarks/prepare.py) and the app on :8080.

import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  stages: [
    { duration: '30s', target: 10 },    // warm-up
    { duration: '30s', target: 100 },   // ramp to sustained load
    { duration: '120s', target: 100 },  // sustained
    { duration: '30s', target: 500 },   // spike
    { duration: '30s', target: 0 },     // cool-down
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<200'],
  },
};

export default function () {
  const response = http.get(`${BASE}/bench-hit`, { redirects: 0 });
  check(response, { 'redirect 302': (r) => r.status === 302 });
}
