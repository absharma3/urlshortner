# Performance Tuning Guide

Configuration required to sustain **10,000+ QPS** with **p99 < 20ms** on the
redirect hot path (per `CLAUDE.md`). Also the reference for interpreting and
troubleshooting the k6 harness in [`k6/load-test.js`](../k6/load-test.js).

This document is prescriptive, not descriptive: each section lists the current
value, the recommended value at the 10k-QPS target, and — where relevant —
the sizing formula for scaling further.

---

## 1. Prerequisites for a valid load-test run

### 1a. Lift per-scope rate limits

The app rate-limits by source IP. Every k6 VU inside the Docker network shares
the container's internal IP, so at defaults (10 creates/min, 600 redirects/min)
the harness saturates the rate limiter within the first second and every
subsequent request is a `429`.

`docker-compose.override.yml` already sets these for the `loadtest` profile:

```yaml
APP_RATELIMIT_CREATE_LIMIT:   "10000000"
APP_RATELIMIT_REDIRECT_LIMIT: "10000000"
APP_RATELIMIT_STATS_LIMIT:    "10000000"
```

If running against a bare-metal app, export the same env vars before
`./gradlew bootRun`.

### 1b. Docker Desktop resource allocation (macOS / Windows)

The `app`, `mysql`, `redis`, and `k6` containers all run inside the Docker
Desktop VM. Grant it:

| Resource | Minimum | Recommended |
| --- | --- | --- |
| CPUs   | 6 cores | 8+ cores |
| Memory | 6 GB    | 10+ GB   |
| Swap   | 1 GB    | 2 GB     |

Settings → Resources → Advanced. If the VM is starved, the load test will
report high tail latency that has nothing to do with the app.

---

## 2. OS-level tuning

Applies to Linux hosts (and inside the Docker Desktop VM). All of these are
one-shot changes; verify with `ulimit -n` / `sysctl -a` afterward.

### 2a. File descriptors

Each open TCP connection consumes one FD; a 2,000-VU test with keep-alive
disabled can burn through the default 1,024 in under a second.

```bash
# Ephemeral (current shell):
ulimit -n 1048576

# Persistent (systemd service unit for the app):
[Service]
LimitNOFILE=1048576

# Persistent (login sessions, /etc/security/limits.conf):
*  soft  nofile  1048576
*  hard  nofile  1048576
```

### 2b. Ephemeral port range

10,000 QPS from a single source can exhaust the ephemeral port range when
connections don't get reused. Widen it:

```bash
sysctl -w net.ipv4.ip_local_port_range="10000 65535"
```

### 2c. SYN backlog and accept queue

The app's `server.tomcat.accept-count: 500` sits behind the kernel's socket
backlog. If the kernel drops SYNs the app never sees them.

```bash
sysctl -w net.core.somaxconn=8192
sysctl -w net.ipv4.tcp_max_syn_backlog=8192
```

### 2d. TIME_WAIT reuse

If load-test connections use `Connection: close`, TIME_WAIT will pile up.
k6's default keep-alive avoids this, but if you switch it off:

```bash
sysctl -w net.ipv4.tcp_tw_reuse=1
```

Do **not** set `tcp_tw_recycle` — removed in Linux 4.12 and hostile to NAT.

---

## 3. HikariCP (MySQL connection pool)

Current settings live in `src/main/resources/application.yml` (`spring.datasource.hikari`):

```yaml
maximum-pool-size: 50
minimum-idle:      10
connection-timeout: 3000    # ms
validation-timeout: 2000    # ms
idle-timeout:      60000
max-lifetime:      1800000  # 30 min — under MySQL wait_timeout (default 28800s)
keepalive-time:    30000
leak-detection-threshold: 10000
```

### Sizing formula

By Little's Law: `pool_size ≈ writes_per_sec × avg_tx_ms / 1000`. With writes
at 5% of 10k QPS (~500/s) and average transaction under 10ms, ~5 concurrent
connections would be enough — 50 is already 10× headroom. Reads *never touch
the pool* on cache hit, which is the majority of traffic.

Raise `maximum-pool-size` only if:
- p95 write latency exceeds 50 ms AND
- `hikari_connections_pending` in Prometheus is non-zero for sustained periods.

Do **not** raise it past `max_connections` on the MySQL side (default 151).

### MySQL server-side counterpart

If pushing past 10k QPS or running multiple app replicas:

```sql
SET GLOBAL max_connections           = 500;
SET GLOBAL innodb_buffer_pool_size   = 4G;    -- fit the working set
SET GLOBAL innodb_flush_log_at_trx_commit = 2; -- optional: batch fsyncs
```

---

## 4. Redis Lettuce pool

Current settings (`application.yml`, `spring.data.redis.lettuce.pool`):

```yaml
lettuce:
  pool:
    max-active: 512
    max-idle:   256
    min-idle:   64
    max-wait:   1000ms
```

Each redirect hits Redis ~4 times on the hot path (`EVALSHA` cache lookup +
`GET` fallback + `INCR` click counter + `PEXPIRE`). At 10k QPS that's 40k
Redis ops/s. On loopback Docker each round-trip is ~0.2 ms if the pool has
slack; under contention it rises quickly. Empirically, dropping from
`max-active: 64` to `512` cut per-op Lettuce latency by ~30% at 2000-VU
stress load.

`max-wait: 1000ms` is important: if it's `-1` (default), a starved pool
translates VU pile-up into unbounded queueing rather than fail-fast, and
tail latency explodes. A 1 s cap keeps a degenerate pool from silently
masking a real problem.

If you'd rather size back down: at strict 10k QPS the earlier `64/32/8/100ms`
values ran without failures too — just with materially higher p95 under
oversubscription. The bump is essentially free headroom for stress-mode runs
and multi-replica prod.

### Connect timeout & command timeout

```yaml
spring:
  data:
    redis:
      timeout:          200ms   # per-command
      connect-timeout:  500ms
```

Fail-open behavior in `RedisCacheService` requires these to be *low*.
A hanging Redis connection with no timeout would block redirects instead of
degrading them.

---

## 5. Tomcat + Virtual Threads

Current settings (`application.yml`, `spring.threads.virtual` and `server.tomcat`):

```yaml
spring:
  threads:
    virtual:
      enabled: true

server:
  tomcat:
    max-connections: 20000
    accept-count:    500
```

With virtual threads enabled, Tomcat schedules each request onto a virtual
thread rather than a fixed-size platform-thread pool. The legacy
`max-threads` knob becomes irrelevant — Java's virtual-thread scheduler
handles carrier thread multiplexing.

Sanity checks:
- `max-connections` must exceed the peak concurrent VU count. 20,000 covers
  the 2,000-VU test with 10× headroom.
- `accept-count` is the kernel-level backlog *after* max-connections is hit.
  500 is enough for burst absorption at 10k QPS.

Do **not** re-enable the platform-thread pool. On Java 21, virtual-thread
carrier count defaults to `Runtime.availableProcessors()`; if you observe
carrier saturation under load (rare — only happens with blocking JNI /
`synchronized`), tune via `-Djdk.virtualThreadScheduler.parallelism=N`.

---

## 6. JVM & Garbage Collection

Current JVM opts (Dockerfile):

```
-XX:+UseZGC
-XX:MaxRAMPercentage=75.0
-Dspring.threads.virtual.enabled=true
```

`docker-compose.override.yml` upgrades these during load tests to:

```
-XX:+UseZGC
-XX:+ZGenerational           # generational ZGC (Java 21+) — better throughput
-XX:MaxRAMPercentage=75.0
-XX:+AlwaysPreTouch          # commit heap up-front; no first-touch pauses
-XX:+UseStringDeduplication  # cache-heavy workload — many duplicate strings
```

### Why ZGC over G1

At 10k QPS the request-scoped allocation rate is high (JSON marshalling,
HTTP request objects, Redis Lettuce byte buffers). G1's default pause target
of 200 ms would blow the p99 SLA. ZGC's sub-ms pauses keep the tail flat
regardless of heap size.

Generational ZGC (`+ZGenerational`) is preferred on Java 21+ — it retains
ZGC's pause guarantees while adding a young-generation collector that cuts
CPU cost by ~20% on allocation-heavy workloads.

### Heap sizing

`MaxRAMPercentage=75.0` on a container with 4 GB gives ~3 GB heap. Rule of
thumb: heap should fit **live working set × 3** to avoid GC thrash. The
service has a small live set (JPA session cache, Lettuce pools, click
counters buffered in Redis — not JVM heap) so 1–3 GB is plenty.

If the k6 run shows any GC pause > 5 ms in the JFR profile, add:

```
-Xms2g -Xmx2g               # eliminate resize pauses
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/tmp/urlshortener.hprof
```

---

## 7. Verification: running the harness end-to-end

```bash
# Start the app (mysql, redis, app all healthy):
docker compose up -d --build

# Wait for /healthz to report UP:
until curl -fsS http://localhost:8080/healthz > /dev/null; do sleep 1; done

# Run the k6 load test (uses the loadtest profile):
docker compose --profile loadtest up --build k6 --abort-on-container-exit

# Or, against a bare-metal app on the host:
k6 run -e BASE_URL=http://localhost:8080 k6/load-test.js
```

### Interpreting the run

The k6 summary lists thresholds first. A green run should look like:

```
✓ http_req_failed ................ rate<0.001
✓ http_req_duration{type:read}  .. p(95)<15   p(99)<30
✓ http_req_duration{type:write} .. p(95)<50
✓ checks ......................... rate>0.999

http_reqs ..................... N   ≥ 10000/s   ← the SLA
```

If any threshold is red, work through the diagnosis order below:

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| `http_req_failed` > 0.1% with 429 | Rate limits not lifted | § 1a |
| `http_req_failed` > 0.1% with connection reset | Kernel SYN drops | § 2c |
| Read p99 > 30 ms, low CPU | Redis timeouts / pool starvation | § 4 |
| Read p99 > 30 ms, high CPU | GC pauses / carrier saturation | § 6, § 5 |
| Write p95 > 50 ms | HikariCP pool starvation | § 3 |
| `http_reqs` < 10k/s at 2000 VUs | Docker VM under-resourced | § 1b |

Prometheus + Grafana on the app's actuator endpoint (`:8081/actuator/prometheus`)
is the fastest way to root-cause: the `hikari_*`, `lettuce_command_*`, and
`jvm_gc_*` metrics all update at 1s resolution and pinpoint the bottleneck
before the k6 run ends.
