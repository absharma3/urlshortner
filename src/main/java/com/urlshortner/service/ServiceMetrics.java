package com.urlshortner.service;

import java.time.Duration;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Custom Prometheus metrics that answer the four questions dashboards actually need:
 * <ul>
 *     <li>What's my Redis cache hit ratio? ({@code urlshortener.cache.hits} vs {@code .misses})</li>
 *     <li>How often are shorten requests hitting salt-loop retries? ({@code urlshortener.shorten.salt_retries})</li>
 *     <li>How long is the click flush taking? ({@code urlshortener.click.flush.duration})</li>
 *     <li>How often is the rate limiter denying? ({@code urlshortener.ratelimit.denied})</li>
 * </ul>
 *
 * <p>All counters are pre-registered so labels are cheap to record on the hot path. The
 * {@code scope} tag on the rate-limit counter lets dashboards distinguish create/redirect/stats.
 */
@Component
public class ServiceMetrics {

    public static final String CACHE_HITS = "urlshortener.cache.hits";
    public static final String CACHE_MISSES = "urlshortener.cache.misses";
    public static final String CACHE_NEGATIVE_HITS = "urlshortener.cache.negative_hits";
    public static final String SALT_RETRIES = "urlshortener.shorten.salt_retries";
    public static final String FLUSH_DURATION = "urlshortener.click.flush.duration";
    public static final String FLUSH_ROWS = "urlshortener.click.flush.rows";
    public static final String RATELIMIT_DENIED = "urlshortener.ratelimit.denied";

    private final MeterRegistry registry;
    private final Counter cacheHits;
    private final Counter cacheMisses;
    private final Counter cacheNegativeHits;
    private final Counter saltRetries;
    private final Timer flushDuration;
    private final Counter flushRows;

    public ServiceMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.cacheHits = Counter.builder(CACHE_HITS)
                .description("Redis redirect-cache hits").register(registry);
        this.cacheMisses = Counter.builder(CACHE_MISSES)
                .description("Redis redirect-cache misses (loader consulted)").register(registry);
        this.cacheNegativeHits = Counter.builder(CACHE_NEGATIVE_HITS)
                .description("Redis negative-cache hits (unknown shortCode short-circuited)").register(registry);
        this.saltRetries = Counter.builder(SALT_RETRIES)
                .description("Salt attempts consumed by successful shortens; 0 means first-try success").register(registry);
        this.flushDuration = Timer.builder(FLUSH_DURATION)
                .description("Wall-clock time of ClickAnalyticsService.flushClicks").register(registry);
        this.flushRows = Counter.builder(FLUSH_ROWS)
                .description("Total rows updated across all click flushes").register(registry);
    }

    public void recordCacheHit() {
        cacheHits.increment();
    }

    public void recordCacheMiss() {
        cacheMisses.increment();
    }

    public void recordCacheNegativeHit() {
        cacheNegativeHits.increment();
    }

    public void recordSaltRetries(int attempts) {
        // Record each retry beyond attempt 0.
        if (attempts > 0) {
            saltRetries.increment(attempts);
        }
    }

    public void recordFlush(Duration duration, int rowsUpdated) {
        flushDuration.record(duration);
        if (rowsUpdated > 0) {
            flushRows.increment(rowsUpdated);
        }
    }

    public void recordRateLimitDenied(String scope) {
        Counter.builder(RATELIMIT_DENIED)
                .description("Requests rejected by the rate limiter, tagged by scope")
                .tag("scope", scope)
                .register(registry)
                .increment();
    }
}
