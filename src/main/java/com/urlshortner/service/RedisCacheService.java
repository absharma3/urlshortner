package com.urlshortner.service;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * The single seam between application code and Redis.
 *
 * <p>Every method in this class is expected to be called from a hot path, so
 * two invariants apply:
 * <ol>
 *   <li><b>Fail-open by default.</b> Redis is treated as a best-effort accelerator, not a source
 *       of truth. Any {@link DataAccessException} bubbling out of the Redis client is caught,
 *       logged at WARN, and translated into a safe default: empty for reads, no-op for writes,
 *       "allow" for rate-limit decisions. There is no authoritative Redis path — since short
 *       codes are derived deterministically from the URL via {@code UrlHashGenerator}, a Redis
 *       outage never blocks a shorten, only degrades cache and rate-limit behavior.</li>
 *   <li><b>No leakage of Redis primitives.</b> Callers receive Java-shaped values ({@link Optional},
 *       {@link Set}, records) and never touch {@code redisTemplate} directly. This keeps single-
 *       flight locking, TTL calculation, and Lua scripting concerns local to this class (SRP).</li>
 * </ol>
 *
 * <p>Also owns:
 * <ul>
 *   <li><b>Cache-stampede protection</b> via a per-key single-flight lock
 *       ({@link #getOrLoadRedirect}).</li>
 *   <li><b>Click aggregation</b> using an active-keys SET so the flusher never has to scan the
 *       keyspace ({@link #recordClick} / {@link #popActiveClickCodes}).</li>
 * </ul>
 */
@Service
public class RedisCacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);

    /**
     * Atomic click record: adds the code to the active-keys set and then bumps its counter.
     * The order is important — if we crash between the two steps, the counter is either untouched
     * (safe) or the set already contains the code so the next flush will drain it (safe).
     * KEYS[1] = counter key, KEYS[2] = active-keys set, ARGV[1] = shortCode.
     */
    private static final RedisScript<Long> RECORD_CLICK_SCRIPT = new DefaultRedisScript<>(
            "redis.call('SADD', KEYS[2], ARGV[1])\n" +
                    "return redis.call('INCR', KEYS[1])",
            Long.class);

    /** How many wait-and-retry rounds a lock-loser will make before falling through to the loader. */
    private static final int STAMPEDE_MAX_RETRIES = 20;

    private final StringRedisTemplate redisTemplate;
    private final ServiceMetrics metrics;
    private final Duration stampedeLockTtl;
    private final Duration stampedeWaitBeforeRetry;

    public RedisCacheService(
            StringRedisTemplate redisTemplate,
            ServiceMetrics metrics,
            @Value("${app.cache.stampede-lock-ttl-ms:3000}") long stampedeLockTtlMs,
            @Value("${app.cache.stampede-wait-before-retry-ms:50}") long stampedeWaitBeforeRetryMs) {
        this.redisTemplate = redisTemplate;
        this.metrics = metrics;
        this.stampedeLockTtl = Duration.ofMillis(stampedeLockTtlMs);
        this.stampedeWaitBeforeRetry = Duration.ofMillis(stampedeWaitBeforeRetryMs);
    }

    // ================================================================
    // Redirect cache
    // ================================================================

    /** Sentinel written by {@link #putRedirectMiss} to short-circuit repeated lookups of unknown codes. */
    public static final String MISS_SENTINEL = "__MISS__";

    public void putRedirect(String shortCode, String originalUrl, Duration ttl) {
        tryRedisVoid("putRedirect",
                () -> redisTemplate.opsForValue().set(CacheKeys.redirect(shortCode), originalUrl, ttl));
    }

    /**
     * Stores a short-lived "no such shortCode" sentinel to absorb 404 storms (someone brute-forcing
     * shortCodes, a stale QR poster pointing at a deleted URL). {@link #getOrLoadRedirect} treats
     * the sentinel as an empty result and skips the loader.
     */
    public void putRedirectMiss(String shortCode, Duration ttl) {
        tryRedisVoid("putRedirectMiss",
                () -> redisTemplate.opsForValue().set(CacheKeys.redirect(shortCode), MISS_SENTINEL, ttl));
    }

    /**
     * Cache-aside with single-flight stampede protection.
     *
     * <p>On cache miss, tries to acquire a per-shortCode lock. The lock winner runs {@code loader}
     * (typically a DB query), caches the result, and returns. Losers wait a short time then re-read
     * the cache; if the winner has populated it, they get the hit. If not (winner still running,
     * or loader returned empty), they fall back to running the loader themselves — a safety net,
     * not the common case.
     *
     * <p>Under a hot-key stampede at 10k QPS, one thread hits the DB and the other 9,999 pick up
     * the newly-cached value on their retry.
     */
    public Optional<String> getOrLoadRedirect(String shortCode, Supplier<Optional<LoadedUrl>> loader) {
        CachedRedirect cached = readRedirect(shortCode);
        if (cached.isHit()) {
            return Optional.of(cached.value());
        }
        if (cached.isNegativeHit()) {
            return Optional.empty();
        }

        String lockKey = CacheKeys.stampedeLock(shortCode);
        // Try to be the single-flight loader. If we lose the lock, we wait for the winner to
        // populate the cache. Retry cache-read with a bounded backoff so a slow loader (>>50ms)
        // doesn't cause the whole herd to fall through to the loader as a "safety net."
        for (int attempt = 0; attempt < STAMPEDE_MAX_RETRIES; attempt++) {
            if (tryAcquireLock(lockKey, stampedeLockTtl)) {
                try {
                    // Re-check cache — winner may have populated between our earlier read and lock.
                    CachedRedirect afterLock = readRedirect(shortCode);
                    if (afterLock.isHit()) {
                        return Optional.of(afterLock.value());
                    }
                    if (afterLock.isNegativeHit()) {
                        return Optional.empty();
                    }
                    Optional<LoadedUrl> loaded = loader.get();
                    loaded.ifPresent(lu -> putRedirect(shortCode, lu.originalUrl(), lu.cacheTtl()));
                    return loaded.map(LoadedUrl::originalUrl);
                } finally {
                    releaseLock(lockKey);
                }
            }
            sleepQuietly(stampedeWaitBeforeRetry);
            CachedRedirect retry = readRedirect(shortCode);
            if (retry.isHit()) {
                return Optional.of(retry.value());
            }
            if (retry.isNegativeHit()) {
                return Optional.empty();
            }
        }
        // Bounded retries exhausted — safety net. Rare in practice; means the lock holder crashed
        // or is stuck longer than STAMPEDE_MAX_RETRIES * stampedeWaitBeforeRetry.
        return loader.get().map(LoadedUrl::originalUrl);
    }

    private CachedRedirect readRedirect(String shortCode) {
        String raw = tryRedis("readRedirect",
                () -> redisTemplate.opsForValue().get(CacheKeys.redirect(shortCode)))
                .orElse(null);
        if (raw == null) {
            metrics.recordCacheMiss();
            return CachedRedirect.miss();
        }
        if (MISS_SENTINEL.equals(raw)) {
            metrics.recordCacheNegativeHit();
            return CachedRedirect.negative();
        }
        metrics.recordCacheHit();
        return CachedRedirect.hit(raw);
    }

    private record CachedRedirect(String value, boolean negativeHit) {
        static CachedRedirect miss() {
            return new CachedRedirect(null, false);
        }
        static CachedRedirect negative() {
            return new CachedRedirect(null, true);
        }
        static CachedRedirect hit(String value) {
            return new CachedRedirect(value, false);
        }
        boolean isHit() {
            return value != null;
        }
        boolean isNegativeHit() {
            return negativeHit;
        }
    }

    // ================================================================
    // Click counter (active-keys SET + atomic INCR)
    // ================================================================

    /**
     * Records a click atomically: adds the code to the active-keys set and bumps its counter.
     * Fire-and-forget from the caller's perspective — Redis errors are logged, never propagated,
     * so a click miss will never fail a redirect.
     */
    public void recordClick(String shortCode) {
        tryRedisVoid("recordClick", () -> redisTemplate.execute(
                RECORD_CLICK_SCRIPT,
                List.of(CacheKeys.clicks(shortCode), CacheKeys.CLICKS_ACTIVE_SET),
                shortCode));
    }

    /**
     * Atomically removes up to {@code batchSize} short codes from the active-keys set and returns
     * them. Uses {@code SPOP} — O(1) per element, non-blocking, no key-space scan.
     */
    public Set<String> popActiveClickCodes(long batchSize) {
        return tryRedis("popActiveClickCodes", () -> {
            List<String> popped = redisTemplate.opsForSet().pop(CacheKeys.CLICKS_ACTIVE_SET, batchSize);
            return popped == null ? Set.<String>of() : new HashSet<>(popped);
        }).orElse(Set.of());
    }

    /**
     * Atomically reads-and-clears the pending click delta for a short code. Returns null if the
     * key doesn't exist (nothing to flush) or Redis is unavailable.
     */
    public Long drainClickCount(String shortCode) {
        return tryRedis("drainClickCount", () -> {
            String val = redisTemplate.opsForValue().getAndDelete(CacheKeys.clicks(shortCode));
            if (val == null) {
                return null;
            }
            try {
                return Long.parseLong(val);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }).orElse(null);
    }

    /**
     * Puts a short code back into the active-keys set after a failed DB flush so the next tick
     * retries it.
     */
    public void reQueueClickCode(String shortCode) {
        tryRedisVoid("reQueueClickCode",
                () -> redisTemplate.opsForSet().add(CacheKeys.CLICKS_ACTIVE_SET, shortCode));
    }

    // ================================================================
    // Rate limiting
    // ================================================================

    /**
     * Fixed-window rate limit check. On Redis failure, fails open (allowed=true) — availability
     * over strict quota. Bucket key rolls automatically every {@code window} since it embeds the
     * window index in the key.
     */
    public RateLimitDecision attemptRateLimit(String clientId, int limit, Duration window) {
        long nowMillis = System.currentTimeMillis();
        long windowMillis = window.toMillis();
        long bucket = nowMillis / windowMillis;
        long resetEpochSeconds = ((bucket + 1) * windowMillis) / 1000L;
        String key = CacheKeys.rateLimit(clientId, bucket);

        Optional<Long> count = tryRedis("attemptRateLimit", () -> {
            Long c = redisTemplate.opsForValue().increment(key);
            if (c != null) {
                redisTemplate.expire(key, window);
            }
            return c;
        });

        if (count.isEmpty()) {
            // Fail-open: quota headers reflect full budget so callers aren't misled.
            return RateLimitDecision.allow(limit, limit, resetEpochSeconds, 0L);
        }
        long current = count.get();
        long remaining = Math.max(0L, limit - current);
        if (current > limit) {
            long retryAfter = Math.max(1L, resetEpochSeconds - (nowMillis / 1000L));
            return RateLimitDecision.deny(limit, remaining, resetEpochSeconds, retryAfter);
        }
        return RateLimitDecision.allow(limit, remaining, resetEpochSeconds, 0L);
    }

    // ================================================================
    // Private helpers
    // ================================================================

    private <T> Optional<T> tryRedis(String op, Supplier<T> action) {
        try {
            return Optional.ofNullable(action.get());
        } catch (DataAccessException ex) {
            log.warn("Redis op '{}' failed; degrading gracefully: {}", op, ex.getMessage());
            return Optional.empty();
        }
    }

    private void tryRedisVoid(String op, Runnable action) {
        try {
            action.run();
        } catch (DataAccessException ex) {
            log.warn("Redis op '{}' failed; degrading gracefully: {}", op, ex.getMessage());
        }
    }

    private boolean tryAcquireLock(String lockKey, Duration ttl) {
        return tryRedis("tryAcquireLock", () -> {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", ttl);
            return Boolean.TRUE.equals(acquired);
        }).orElse(false);
    }

    private void releaseLock(String lockKey) {
        tryRedisVoid("releaseLock", () -> redisTemplate.delete(lockKey));
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    // ================================================================
    // Public value types
    // ================================================================

    /** Data passed back from the DB loader in {@link #getOrLoadRedirect}. */
    public record LoadedUrl(String originalUrl, Duration cacheTtl) {
    }

    /** Outcome of a {@link #attemptRateLimit} check, carrying data for X-RateLimit-* headers. */
    public record RateLimitDecision(
            boolean allowed,
            int limit,
            long remaining,
            long resetEpochSeconds,
            long retryAfterSeconds) {

        public static RateLimitDecision allow(int limit, long remaining, long reset, long retryAfter) {
            return new RateLimitDecision(true, limit, remaining, reset, retryAfter);
        }

        public static RateLimitDecision deny(int limit, long remaining, long reset, long retryAfter) {
            return new RateLimitDecision(false, limit, remaining, reset, retryAfter);
        }
    }
}
