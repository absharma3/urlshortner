# Redis Key Strategy

All keys use a `:` delimiter and short concern-based prefixes. TTL and data-type
choices are load-bearing — do not change them without updating the service and
the scheduled click-flush job together.

## Conventions

- **Delimiter:** `:` between segments.
- **Encoding:** short codes are strict alphanumeric Base62 (`^[a-zA-Z0-9]{4,32}$`),
  stored case-sensitively (matches MySQL `utf8mb4_bin`). Auto-generated codes are
  derived deterministically from `MurmurHash3(normalizedUrl [+ "_salt_" + n])`
  and padded to exactly 6 characters.
- **Time:** all TTLs in seconds. Timestamps are Unix epoch milliseconds.
- Constants live in `com.urlshortner.service.CacheKeys`.

Primary keys (`urls.id`) are handled by MySQL `AUTO_INCREMENT` — no Redis-based
ID allocator any more, because the shortCode is derived from the URL rather than
from a monotonic counter.

---

## 1. URL Redirection Cache

Read on every `GET /{shortCode}`. Zero DB reads on cache hit.

| Aspect | Value |
| --- | --- |
| Key | `url:{shortCode}` |
| Type | `STRING` |
| Value | `originalUrl` (raw absolute URL) |
| TTL | `app.cache.ttl-seconds` (default `86400s`) for permanent codes; `min(defaultTtl, expiresAt - now)` for expiring codes |

**Access pattern (cache-aside, in `UrlService`):**
- `GET url:{shortCode}` → hit → 302.
- Miss → DB lookup by `short_code` → `SET url:{shortCode} <url> EX <ttl>`.
- Expired rows are treated as not found and are not cached.

---

## 2. Non-Blocking Click Counter Buffers

Written on every successful redirect via `ClickAnalyticsService.recordClick`.
Flushed to MySQL by a `@Scheduled` job.

| Aspect | Value |
| --- | --- |
| Counter key | `clicks:{shortCode}` (STRING, integer) |
| Active-keys index | `clicks:active` (SET) — codes with pending deltas |
| Record op | atomic Lua: `SADD clicks:active {code}` + `INCR clicks:{code}` |
| TTL | none — persisted until the scheduled flush consumes the delta |

**Flush algorithm (`@Scheduled(fixedDelay = 5000)`):**
1. `SPOP clicks:active {batch-size}` — atomically remove up to N codes with pending deltas. O(1) per element, **no keyspace scan**.
2. For each code, `GETDEL clicks:{code}` → delta (atomic read-and-clear).
3. Single `JdbcTemplate.batchUpdate("UPDATE urls SET total_clicks = total_clicks + ? WHERE short_code = ?", batch)` — one MySQL round trip.
4. On batch-update failure, `SADD` each popped code back into `clicks:active` so the next tick retries.

**Why `SPOP` and not `SCAN clicks:*`.** `KEYS`/`SCAN` iterate the entire keyspace (millions of keys under load), blocking Redis's single thread. The active-keys set is bounded to only codes with *pending* clicks — typically a small working set. Redis 7's `SPOP` with count returns and removes members atomically in O(count), independent of total keyspace size.

---

## 3. Rate Limiting — Fixed Window per Client

Per-IP fixed-window counter enforced in `RateLimitingInterceptor`.

| Aspect | Value |
| --- | --- |
| Key | `ratelimit:{clientId}:{bucket}` |
| Type | `STRING` (integer) |
| bucket | `floor(now_ms / window_ms)` — key rolls every window automatically |
| clientId | Direct TCP peer (`request.getRemoteAddr()`) by default; XFF/X-Real-IP honoured **only when the peer is in `app.security.trusted-proxies`** |
| TTL | `EXPIRE` = window duration on the first INCR |

Response headers on every request:
- `X-RateLimit-Limit` — the configured budget
- `X-RateLimit-Remaining` — max(0, limit − current)
- `X-RateLimit-Reset` — Unix epoch seconds when the bucket rolls
- `Retry-After` (on 429 only) — seconds until reset

On Redis failure the interceptor **fails open** (allow, headers report full budget) — availability over strict quota.

---

## 4. Cache-Stampede Single-Flight Lock

Guards against the thundering herd when a hot short link's cache entry expires.

| Aspect | Value |
| --- | --- |
| Key | `lock:url:{shortCode}` |
| Type | `STRING` (sentinel) |
| Op | `SET NX EX 3` — first caller wins the lock, others fall through to a bounded retry |
| TTL | `app.cache.stampede-lock-ttl-ms` (default 3s) |

**Flow (`RedisCacheService.getOrLoadRedirect`):**
1. `GET url:{shortCode}` — hit? return immediately.
2. Miss → `SET NX EX 3 lock:url:{shortCode} 1`.
3. Lock winner runs the DB loader, caches the result, `DEL lock:url:{shortCode}`.
4. Lock losers sleep ~50ms (`app.cache.stampede-wait-before-retry-ms`), retry cache read once; if still empty, call the loader themselves as a safety net (better a small extra DB read than a hung request).
