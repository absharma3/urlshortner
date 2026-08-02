# URL Shortener Service

A production-grade, high-concurrency URL shortener built on Spring Boot 4 with virtual threads,
MySQL for durability, and Redis for cache, rate limiting, and async click aggregation. Designed
to meet a 10k QPS / <20ms p99 target on the redirect path.

This README is a project-level overview aimed at external engineers evaluating the codebase.
For step-by-step setup and troubleshooting see [`SETUP.md`](./SETUP.md); for the Redis key
strategy see [`docs/redis-keys.md`](./docs/redis-keys.md); for the machine-readable API contract
see [`src/main/resources/openapi.yaml`](./src/main/resources/openapi.yaml).

---

## What it does

Two public flows:

1. **Shorten** — `POST /api/v1/urls` accepts a target URL (and optionally a custom alias and
   expiration) and returns a short code + short URL.
2. **Redirect** — `GET /{shortCode}` resolves a short code to its target URL and issues an
   HTTP 302.

Plus supporting endpoints for analytics (`GET /api/v1/urls/{code}/stats`), health probes, and
Springdoc-generated OpenAPI / Swagger UI. Full contract in `openapi.yaml`.

---

## Highlights

- **Deterministic shortening.** Same URL always produces the same short code (MurmurHash3-32 →
  Base62). No `INCR`-driven counter, no primary-key coupling — dedup happens naturally through
  a `UNIQUE` short-code lookup.
- **Salt-loop collision resolution.** On the rare hash collision (two different URLs hashing to
  the same code), we retry with `url + "_salt_" + n` until an unused code is found. Handles
  concurrent-write races via `DataIntegrityViolationException` / `CannotAcquireLockException`
  catch-and-retry.
- **Cache-aside redirect with single-flight stampede protection.** A hot short code that just
  expired from Redis will trigger exactly one DB read, not ten thousand; other readers wait on a
  Redis `SET NX` lock and pick up the newly-populated value.
- **Zero-DB redirect on cache hit.** The 10k QPS budget assumes the hot path never touches
  MySQL. Redirects that miss the cache take a single indexed row lookup.
- **Non-blocking click aggregation.** Every redirect fires an atomic Lua `SADD + INCR` to an
  active-keys set + per-code counter in Redis. A `@Scheduled` job drains the set via `SPOP`
  (O(1) per element, no keyspace scan) and flushes to MySQL as one `batchUpdate`.
- **SSRF & open-redirect defense.** Target URLs are validated: only `http`/`https` schemes, DNS
  resolution bounded by a 1.5-second timeout, and hosts resolving to loopback / link-local /
  private / cloud-metadata ranges are refused.
- **Fail-open Redis.** Every Redis call is wrapped so a Redis outage degrades gracefully:
  redirects fall back to MySQL, rate limits allow, click counters silently drop. The only
  write-time hard dependency was removed when we switched from `INCR`-issued IDs to MurmurHash.
- **Per-scope rate limits.** Different budgets for creates (10/min), redirects (600/min), and
  stats lookups (60/min), so a browser following a shortened link doesn't burn the client's
  create quota.
- **Trusted-proxy XFF handling.** `X-Forwarded-For` is honored only when the direct TCP peer is
  in an explicit allowlist. Empty allowlist (the default) = XFF ignored entirely — no header
  spoofing attack surface out of the box.
- **RFC 7807 problem details** on every error response, with per-field validation errors and
  rate-limit-reset metadata.

---

## Architecture

```
flowchart LR
    Client([Client])
    LB[Reverse proxy<br/>optional]
    subgraph app["Spring Boot 4 (Java 21, virtual threads)"]
        RL[RateLimitingInterceptor<br/>per-scope, XFF-aware]
        C[UrlController]
        US[UrlService<br/>normalize → hash → salt loop]
        RCS[RedisCacheService<br/>cache-aside · stampede lock<br/>active-keys · rate limit]
        CAS[ClickAnalyticsService<br/>@Async · @Scheduled flush]
        UV[UrlValidator<br/>+ HostResolver]
        UN[UrlNormalizer]
        UHG[UrlHashGenerator<br/>MurmurHash3 + Base62]
        R[UrlRepository<br/>Spring Data JPA]
    end
    subgraph store["Data plane"]
        MY[(MySQL 8<br/>Flyway V1-V3)]
        RD[(Redis 7<br/>AOF, allkeys-lru)]
    end

    Client -->|HTTP| LB --> RL
    RL --> C
    C --> US
    US --> UN --> UV --> UHG
    US --> R
    US --> RCS
    C --> CAS
    RCS --> RD
    CAS --> RD
    CAS --> MY
    R --> MY
```

### Redirect (`GET /{shortCode}`) — the hot path

1. `RateLimitingInterceptor` — INCR the caller's rate-limit bucket in Redis, deny with 429 if
   over quota. Fails open on Redis errors.
2. `RedisCacheService.getOrLoadRedirect` — `GET url:{code}` from Redis.
    - **Hit** → return the URL. Zero DB touch.
    - **Negative hit** (a `__MISS__` sentinel from a prior 404) → return empty → 404.
    - **Miss** → acquire the `lock:url:{code}` single-flight lock. Winner runs the DB loader;
      losers wait 50 ms then re-check the cache. Bounded retry, then fall through as a safety net.
3. Loader: `findByShortCode`, filter expired rows, cache the result (with TTL capped by
   `expiresAt - now`), or write the negative sentinel on miss.
4. Fire-and-forget: `clickAnalyticsService.recordClick(shortCode)` dispatches to a virtual-thread
   executor; a Lua script atomically `SADD clicks:active {code}` + `INCR clicks:{code}`.
5. Respond with 302 + `Location` + `Cache-Control: no-store`.

### Shorten (`POST /api/v1/urls`) — the write path

1. `UrlNormalizer` canonicalizes (default scheme = `https`, lowercase host, strip trailing `/`,
   preserve query/fragment).
2. `UrlValidator` runs the SSRF + scheme guard (`HostResolver` bounds DNS lookup at 1.5s;
   unresolvable hosts fail open, resolved private ranges fail closed).
3. If a `customAlias` was supplied and it's not on the reserved list, we look it up and either
   return the existing row (same URL) or throw 409 (different URL).
4. Otherwise we enter the salt loop:
    - `hash(normalizedUrl, saltAttempt=n)` → 6-char Base62 shortCode.
    - `findByShortCode(shortCode)` — if it exists with the same URL, we're done (1-to-1 dedup);
      if it exists with a different URL, `saltAttempt++`.
    - Otherwise `saveAndFlush`. On `DataIntegrityViolationException` (unique-constraint race) or
      `CannotAcquireLockException` (MySQL gap-lock deadlock under high concurrency), re-read
      and either return the winner's row or `saltAttempt++`.
    - Bounded at 100 attempts.
5. Cache the winning row in Redis with an appropriate TTL.

### Click aggregation — the background path

- Every redirect calls `ClickAnalyticsService.recordClick` (`@Async`), which pipes to the
  atomic `SADD + INCR` Lua script — the redirect thread never blocks on Redis.
- `@Scheduled(fixedDelay = 5s)` drains the active-keys set via `SPOP {batch}` — never a
  `SCAN clicks:*` keyspace walk, so flush cost is O(batch), not O(total shortened URLs).
- For each popped code: `GETDEL clicks:{code}` (atomic read-and-clear), collect
  `(delta, shortCode)` tuples, then one `JdbcTemplate.batchUpdate` UPDATE for the whole batch.
- On batch-update failure, popped codes are `SADD`-ed back into the active-keys set for the next
  tick to retry.
- An `ExpiredUrlSweeper` `@Scheduled` job DELETEs rows past `expires_at + grace` (default 5 min)
  every 10 minutes, keyed on the `ix_urls_expires_at` index. Prevents unbounded row growth.

---

## Notable design decisions

Some choices worth pointing out because they're the "why not the obvious thing" moments.

### Deterministic hash-derived shortCodes, not `INCR`-issued IDs

The naive shortener stores an auto-incremented row and encodes the ID as Base62. That works,
but every write to a specific URL creates a new short code — the second POST for the same URL
allocates a fresh ID, fresh row, fresh code. Users complain, DB bloats, cache thrashes.

We use MurmurHash3-32 of the normalized URL, Base62-encoded and padded to six characters. Same
URL, same code — always. A duplicate POST is a `findByShortCode` + return. No INSERT, no ID
allocator, no dedup index (V3 dropped the SHA-256 hash column that was added in V2 for that).

Trade-off: two different URLs might hash to the same 32-bit code (birthday-paradox territory
around ~65k URLs). We handle it via a **salt loop** — retry with `url + "_salt_" + n` until we
find an unused code. Deterministic re-hashing means a duplicate POST for the *loser* of a
collision still stabilizes on the same second-attempt code.

### No `@Transactional` on `shortenUrl`

The salt-loop path catches `DataIntegrityViolationException` and `CannotAcquireLockException`.
An outer `@Transactional` would seem correct, but under real concurrency, MySQL's gap-lock
deadlock detection *physically* rolls back the transaction at the DB level — Spring's
`noRollbackFor` can't prevent that. The next call in the same tx then throws
`UnexpectedRollbackException`.

Removing the outer `@Transactional` lets each `saveAndFlush` open its own short-lived tx.
A deadlock or constraint violation aborts one inner tx cleanly and the salt loop retries fresh.
This was the specific fix for a 500-under-concurrency bug we found while writing the concurrent
shorten test.

### Cache-stampede: single-flight lock with a bounded retry loop

A naive single-flight lock waits once for the winner and falls through to the loader as a
"safety net" if the winner isn't done. Under a slow loader (say 150 ms — plausible for a cold
DB) that safety net **is** the stampede — everyone falls through.

Losers now loop up to 20 times (50 ms each = 1s max wait) re-checking the cache before falling
through. In tests with 32 concurrent readers on a 150 ms loader, this collapses 32 loader calls
to 1-2. See `RedisCacheServiceTest.stampedeProtectionCollapsesConcurrentLoaderCalls`.

### Click flush uses a SET, not `SCAN clicks:*`

`redisTemplate.scan("clicks:*")` is O(N) over the entire keyspace and blocks Redis's single
thread. Under real load with millions of keys, this becomes a production incident.

Instead: every recorded click atomically adds the code to `clicks:active` (a Redis SET) via a
Lua script. The scheduled flush pops from that set with `SPOP {batch-size}` — O(batch), not
O(keyspace). Only codes with pending deltas are ever visited.

### Rate-limit XFF handling is opt-in via `trusted-proxies`

If we blindly honored `X-Forwarded-For`, a client could rotate through a fresh rate-limit
bucket on every request just by sending a random IP in the header. Instead: the interceptor
only reads XFF when the direct TCP peer is in a configured allowlist (`app.security.trusted-proxies`).
Default is empty → XFF is ignored entirely. Deploying behind a real LB? Set the LB's IP in the
env var. Deploying without one? Attackers can't spoof.

### `HostResolver` bounds DNS on the write path

`InetAddress.getByName(host)` is a blocking system call with no timeout. A hostile DNS server
can pin a request thread indefinitely — an accidental DoS via targeted URLs.
`HostResolver.resolve` wraps the lookup in a `CompletableFuture` with a 1.5s deadline. Also
mockable — `UrlValidatorDnsMockTest` exercises fail-open / fail-closed decisions without
touching the network.

### `/healthz` actually probes MySQL and Redis

A liveness endpoint that always returns `UP` regardless of dependencies is worse than useless:
load balancers keep sending traffic to a broken app. Our `HealthController` runs `SELECT 1`
against the DataSource and `PING` against Redis; returns 503 with per-component status when
either fails. The Docker healthcheck (in `Dockerfile` and `docker-compose.yml`) targets this
endpoint via `curl`.

---

## Tech stack

| Concern | Choice | Why |
|---|---|---|
| Language | Java 21 (LTS) | Virtual threads for I/O-heavy request handling |
| Framework | Spring Boot 4.1 (webmvc, JPA, data-redis, validation, actuator, flyway) | Ecosystem breadth; virtual-thread integration comes for free |
| Persistence | MySQL 8, Hibernate ORM 7, HikariCP | Standard, robust, `utf8mb4_bin` for case-sensitive short codes |
| Cache & side-channel | Redis 7 (Lettuce client, connection pool via commons-pool2) | Single dependency covers cache, rate limit, click aggregation, single-flight lock |
| Migrations | Flyway (starter-flyway + flyway-mysql), 3 versioned files | Additive schema evolution; sqL files under `src/main/resources/db/migration/` |
| Hashing | Guava `Hashing.murmur3_32_fixed()` | Fast, deterministic, non-cryptographic — dedup doesn't need collision resistance |
| API docs | Springdoc OpenAPI 2.8 → Swagger UI | Auto-generated from controller annotations; matches `openapi.yaml` |
| Tests | JUnit 5, Mockito, AssertJ, Testcontainers (MySQL + Redis) | Real containers for integration tests; H2 is deliberately excluded |
| Build | Gradle 9 with `foojay-resolver-convention` | Auto-provisions JDK 21 to `~/.gradle/jdks/` — zero manual JDK install |
| Container | Multi-stage Dockerfile (Temurin 21 JDK → JRE) | Small runtime image; healthcheck via `curl` |

---

## Repository layout

```
urlshortner/
├── build.gradle                       Spring Boot 4 build, Guava, springdoc, flyway
├── settings.gradle                    Foojay resolver auto-provisions JDK 21
├── docker-compose.yml                 mysql + redis + app; healthchecks; volumes
├── Dockerfile                         Multi-stage build; curl for healthcheck
├── .env.example                       Externalize DB creds & feature flags
├── CLAUDE.md                          Project brief — the original spec
├── SETUP.md                           First-time setup, ports, troubleshooting
├── HELP.md                            Spring Boot's generated help notes
├── docs/
│   └── redis-keys.md                  Full Redis key strategy (cache, clicks, rate limit, stampede lock)
├── src/main/
│   ├── java/com/urlshortner/
│   │   ├── UrlshortnerApplication.java
│   │   ├── config/WebMvcConfig.java              Interceptor registration + exclusions
│   │   ├── domain/UrlEntity.java                 JPA entity, no setters (write-once)
│   │   ├── dto/                                  CreateUrlRequest, ShortUrlResponse, UrlStatsResponse
│   │   ├── repository/UrlRepository.java         Spring Data JPA
│   │   ├── service/
│   │   │   ├── UrlService.java                   Shorten + resolve + stats
│   │   │   ├── RedisCacheService.java            All Redis ops behind one seam
│   │   │   ├── ClickAnalyticsService.java        @Async record + @Scheduled flush
│   │   │   ├── ExpiredUrlSweeper.java            @Scheduled DELETE of past-expiry rows
│   │   │   ├── ServiceMetrics.java               Prometheus counters + timers
│   │   │   ├── CacheKeys.java                    Central key namespace
│   │   │   ├── ReservedAliases.java              healthz/actuator/… blocklist
│   │   │   └── (exceptions)                      NotFoundException, ShortCodeConflictException
│   │   ├── util/
│   │   │   ├── Base62Encoder.java                Stateless, thread-safe
│   │   │   ├── UrlNormalizer.java                Canonicalization
│   │   │   ├── UrlHashGenerator.java             MurmurHash + Base62 + salt
│   │   │   ├── UrlValidator.java                 SSRF + scheme guard
│   │   │   └── HostResolver.java                 Bounded-latency DNS with fake seam
│   │   └── web/
│   │       ├── UrlController.java                REST endpoints
│   │       ├── HealthController.java             /healthz with real probes
│   │       ├── RateLimitingInterceptor.java      Per-scope, XFF-aware
│   │       ├── RateLimitScope.java               enum { CREATE, REDIRECT, STATS }
│   │       ├── GlobalExceptionHandler.java       RFC 7807 mapping
│   │       └── RateLimitExceededException.java
│   └── resources/
│       ├── application.yml                       Base config
│       ├── application-docker.yml                Docker-profile overrides
│       ├── db/migration/V{1,2,3}__*.sql          Flyway migrations
│       ├── openapi.yaml                          Hand-authored API contract
│       └── static/index.html                     Dev workbench UI
└── src/test/                                     135 tests
```

---

## Getting started

Full instructions in [`SETUP.md`](./SETUP.md). The 30-second version:

```bash
# 1. Start MySQL + Redis
docker compose up -d --wait mysql redis

# 2. Run the app locally
./gradlew bootRun

# 3. Try it
curl -X POST http://localhost:8080/api/v1/urls \
  -H 'Content-Type: application/json' \
  -d '{"originalUrl":"https://example.com/some/very/long/path"}'
# → 201 with {"shortCode":"...", "shortUrl":"...", ...}

# 4. Follow the short link
curl -I http://localhost:8080/{shortCode}
# → 302 + Location

# 5. Analytics
curl http://localhost:8080/api/v1/urls/{shortCode}/stats
```

Or launch the full stack in Docker:

```bash
docker compose up --build
```

Prerequisites, port-conflict resolution, running tests, and log inspection are covered in
`SETUP.md`.

---

## API

The canonical, machine-readable contract is [`src/main/resources/openapi.yaml`](./src/main/resources/openapi.yaml).
It's rendered live at `/swagger-ui/index.html` when the app runs, and the raw JSON is at
`/v3/api-docs`. Both are gated by `SPRINGDOC_ENABLED` (default `true` in dev).

The four endpoints at a glance:

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/urls` | Shorten. Body `{originalUrl, customAlias?, expiresAt?}`. 201 with `Location` header. |
| `GET` | `/{shortCode}` | Redirect. 302 + `Location: <original>` + `Cache-Control: no-store`. 404 if missing/expired. |
| `GET` | `/api/v1/urls/{shortCode}/stats` | Total clicks + metadata. 404 if missing/expired. |
| `GET` | `/healthz` | Real-dependency liveness probe. 200 or 503. |

Error responses use `application/problem+json` (RFC 7807) with per-field violations for 400s
and `Retry-After` + `X-RateLimit-*` headers for 429s.

---

## Observability

- **Prometheus scrape:** `/actuator/prometheus` (on the management port, default `8081`, not
  exposed to public 8080). Custom metrics defined in `ServiceMetrics`:
    - `urlshortener.cache.{hits,misses,negative_hits}` — cache-hit ratio
    - `urlshortener.shorten.salt_retries` — how often we retry a hash collision
    - `urlshortener.click.flush.{duration,rows}` — background flush health
    - `urlshortener.ratelimit.denied{scope}` — per-scope denies
- **Rate-limit headers on every response:** `X-RateLimit-{Limit,Remaining,Reset}` (plus
  `X-RateLimit-Scope` telling the client which bucket applied, and `Retry-After` on 429).
- **`/healthz`** for LB / orchestration probes; `/actuator/health` for full Spring Boot health.

---

## Testing

135 tests across three tiers:

| Tier | What it covers | Runs |
|---|---|---|
| **Unit** (~90) | `Base62Encoder`, `UrlNormalizer`, `UrlHashGenerator`, `UrlValidator` (SSRF/scheme), `UrlValidatorDnsMockTest` (DNS mocked), `UrlServiceTest` (Mockito), `ClickAnalyticsServiceTest` (DB-failure re-queue) | No Docker |
| **Focused integration** (~2) | `RedisCacheServiceTest` — stampede-protection + negative-cache round-trip against a real Redis container | Testcontainers Redis |
| **End-to-end** (~13) | `UrlShortenerIntegrationTest` (E2E + rate-limit + XFF spoof + concurrent shorten + expiry) and `UrlShortenerTrustedProxyIntegrationTest` (positive XFF path) | Testcontainers MySQL + Redis |

Run them:

```bash
./gradlew test --tests "com.urlshortner.util.*" --tests "com.urlshortner.service.UrlServiceTest"   # unit only, no Docker
./gradlew check                                                                                     # everything, needs Docker
```

Tests do NOT use H2 — that was a deliberate call in the original brief. Integration tests spin
up real `mysql:8.0` + `redis:7-alpine` containers via Testcontainers. Adds ~15s to `./gradlew check`
container-startup cost but catches driver-specific and schema-specific issues that H2 would hide.

---

## Trade-offs and known limitations

Called out here so evaluators aren't surprised.

- **No authentication.** Anyone reachable can shorten URLs. Fine for public shorteners like
  bit.ly-style deployments; if the deployment is internal, wire Spring Security in.
- **Fixed-window rate limits, not sliding.** Cheap and effective, but a client can burst 2x the
  quota across a window boundary (100 in the last second of window N + 100 in the first of
  window N+1). Acceptable for this scale; if you need strict, switch to a ZSET-based sliding
  window.
- **Rate-limit clock skew across app instances.** Each instance uses local `System.currentTimeMillis()`
  for bucket calculation. Under NTP-managed fleets the drift is well under the 60s window.
  Using Redis `TIME` via Lua would eliminate this — not implemented; documented in the code.
- **Redis fail-open on writes has one exception.** Shorten needs to `saveAndFlush` a row, which
  is durable in MySQL — Redis being down doesn't block a shorten. But the shorten's cache write
  is best-effort; the next redirect will re-populate the cache from MySQL.
- **Click aggregation loses at most one flush cycle on partial failure.** `GETDEL` removes the
  delta from Redis before the batch UPDATE. If MySQL fails after `GETDEL`, we `SADD` the code
  back into the active-keys set — the *counter* value is lost. Bounded to <= 5s of clicks
  (default flush interval).
- **32-bit hash space.** MurmurHash3-32 collides at ~65k URLs (birthday paradox). Handled via
  the salt loop, but at extreme scale you'd want to widen to 64-bit and re-pad. Current 6-char
  Base62 space is 5.7×10¹⁰, so the *code* space is fine — it's the hash function width that
  bounds practical collision-free density.
- **Springdoc + Swagger UI ship enabled by default.** Turn off via `SPRINGDOC_ENABLED=false`
  in prod if you don't want the schema/UI exposed.
- **Actuator on port 8081 by default.** Compose exposes only 8080 externally; if operators
  need the management endpoint, they port-forward or override `MANAGEMENT_SERVER_PORT`.

---

## Further reading

- [`CLAUDE.md`](./CLAUDE.md) — original project brief and design constraints
- [`SETUP.md`](./SETUP.md) — first-time setup, ports, healthchecks, troubleshooting, one-liners
- [`docs/redis-keys.md`](./docs/redis-keys.md) — full Redis key strategy: cache, click aggregation, rate limit, single-flight lock
- [`src/main/resources/openapi.yaml`](./src/main/resources/openapi.yaml) — canonical API contract
- [`src/main/resources/db/migration/`](./src/main/resources/db/migration/) — Flyway migrations, one story per file
- [`src/main/resources/application.yml`](./src/main/resources/application.yml) — all tunable knobs with inline commentary

---

## License

No license declared in the repository at this time.
