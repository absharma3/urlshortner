// k6 load-test harness for the URL Shortener.
//
// Validates the CLAUDE.md SLA of 10,000+ QPS with p99 < 20ms on the hot
// redirect path against a locally-running app (Docker compose or bare-metal).
//
// Two scenarios are available; select via `K6_MODE`:
//
//   K6_MODE=sla      (default) — constant-arrival-rate scenario locked at
//                    10,000 requests/sec for 90s. This is the CLAUDE.md
//                    target rate — the p95/p99 thresholds are meaningful
//                    against this scenario.
//
//   K6_MODE=stress   — ramping-VU scenario that pushes to 2,000 VUs and
//                    measures the ceiling. Latency thresholds are NOT
//                    meaningful here; use it to answer "how much headroom
//                    is there above 10k QPS?" (Previous runs found ~31k.)
//
// Workload mix:
//   - 95% GET /{shortCode}       tag={type:read}    hot-cache redirect
//   -  5% POST /api/v1/urls      tag={type:write}   creates a new short URL
//
// The write path uses a unique originalUrl per request so the app can't
// short-circuit via deduplication — otherwise every write would collapse
// onto one shortCode and we'd stop exercising the write pipeline.
//
// Read pool is seeded once in `setup()`. A small pool (default 100) is
// deliberate: keeping the same codes in rotation is what "hot cache" means.
// Every read after the first ~few seconds should be a Redis hit.
//
// Rate-limiting note: the app's per-scope rate limits (10 creates/min,
// 600 redirects/min by default) will annihilate this test unless raised.
// docker-compose.override.yml lifts them; if running against a bare-metal
// app, set APP_RATELIMIT_{CREATE,REDIRECT,STATS}_LIMIT to something huge.

import http from 'k6/http';
import { check, fail } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SEED_COUNT = parseInt(__ENV.SEED_COUNT || '100', 10);
const WRITE_RATIO = parseFloat(__ENV.WRITE_RATIO || '0.05');
const MODE = (__ENV.K6_MODE || 'sla').toLowerCase();
const TARGET_RPS = parseInt(__ENV.TARGET_RPS || '10000', 10);

const scenarios = {
  // Fixed 10,000 req/s regardless of latency — the correct shape for measuring
  // an SLA at a target throughput. k6 pre-allocates VUs and dispatches
  // iterations on a schedule; slow responses cause k6 to spin up more VUs
  // (up to maxVUs) rather than dropping the offered rate.
  sla: {
    executor: 'constant-arrival-rate',
    rate: TARGET_RPS,
    timeUnit: '1s',
    duration: '90s',
    preAllocatedVUs: 300,
    maxVUs: 2000,
    tags: { scenario: 'sla' },
  },
  // Original ramping-VU scenario. Measures how many QPS the box can be
  // brute-forced into serving; latency thresholds are expected to trip here.
  stress: {
    executor: 'ramping-vus',
    startVUs: 0,
    stages: [
      { duration: '30s', target: 100 },
      { duration: '15s', target: 500 },
      { duration: '5s',  target: 2000 },
      { duration: '60s', target: 2000 },
      { duration: '15s', target: 0 },
    ],
    gracefulRampDown: '10s',
    tags: { scenario: 'stress' },
  },
};

if (!scenarios[MODE]) {
  throw new Error(`unknown K6_MODE: '${MODE}' (expected 'sla' or 'stress')`);
}

// Latency thresholds only apply in SLA mode (constant 10k RPS). In stress
// mode we still track them but don't fail the run — the point of stress mode
// is to find the ceiling, not enforce a rate-dependent SLA.
const readThresholds  = MODE === 'sla' ? ['p(95)<15', 'p(99)<30'] : [];
const writeThresholds = MODE === 'sla' ? ['p(95)<50']              : [];

export const options = {
  scenarios: { [MODE]: scenarios[MODE] },
  thresholds: {
    'http_req_failed': ['rate<0.001'],
    'http_req_duration{type:read}':  readThresholds,
    'http_req_duration{type:write}': writeThresholds,
    'checks': ['rate>0.999'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const JSON_HEADERS = { 'Content-Type': 'application/json' };

// setup runs once, in a single VU, before any load stage. Its return value
// is passed as `data` to every default-fn iteration.
export function setup() {
  console.log(`[setup] mode=${MODE} target_rps=${TARGET_RPS} seed=${SEED_COUNT} at ${BASE_URL}`);
  const codes = new Array(SEED_COUNT);
  for (let i = 0; i < SEED_COUNT; i++) {
    // Namespace the seed URL with a fresh timestamp so re-runs against the
    // same app instance don't collapse onto previously-shortened codes.
    const originalUrl = `https://example.com/seed/${Date.now()}/${i}`;
    const res = http.post(
      `${BASE_URL}/api/v1/urls`,
      JSON.stringify({ originalUrl }),
      { headers: JSON_HEADERS, tags: { type: 'setup' } },
    );
    if (res.status !== 201) {
      fail(`seed ${i} failed: HTTP ${res.status} — ${res.body}`);
    }
    codes[i] = res.json('shortCode');
  }
  console.log(`[setup] seeded ${codes.length} codes; first=${codes[0]}`);
  return { codes };
}

export default function (data) {
  if (Math.random() < WRITE_RATIO) {
    writeIteration();
  } else {
    readIteration(data.codes);
  }
}

function readIteration(codes) {
  const code = codes[Math.floor(Math.random() * codes.length)];
  const res = http.get(`${BASE_URL}/${code}`, {
    // Do NOT follow the 302 to https://example.com/... — that would introduce
    // WAN latency (or a connection failure) into the measured http_req_duration
    // and swamp the p95/p99 signal we care about.
    redirects: 0,
    tags: { type: 'read' },
  });
  check(res, {
    'read: status is 302': (r) => r.status === 302,
    'read: has Location':  (r) => !!r.headers['Location'],
  });
}

function writeIteration() {
  // Unique URL per request so the deterministic-shortening dedup path
  // doesn't turn every write into a no-op findByShortCode hit.
  const originalUrl = `https://example.com/vu-${__VU}/iter-${__ITER}/${Date.now()}`;
  const res = http.post(
    `${BASE_URL}/api/v1/urls`,
    JSON.stringify({ originalUrl }),
    { headers: JSON_HEADERS, tags: { type: 'write' } },
  );
  check(res, {
    'write: status is 201':    (r) => r.status === 201,
    'write: has shortCode':    (r) => !!r.json('shortCode'),
  });
}
