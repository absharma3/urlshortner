package com.urlshortner.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Direct exercise of {@link RedisCacheService} against a real Redis container. Two things this
 * test set catches that pure mock-based UrlServiceTest cannot:
 * <ol>
 *     <li>Stampede protection actually collapses concurrent loader invocations to one.</li>
 *     <li>The negative-cache sentinel round-trips through Redis correctly and is honored by
 *         {@link RedisCacheService#getOrLoadRedirect}.</li>
 * </ol>
 */
@Testcontainers
class RedisCacheServiceTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private RedisCacheService cache;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        cache = new RedisCacheService(
                redisTemplate,
                new ServiceMetrics(new SimpleMeterRegistry()),
                3000L,
                50L);
        // Fresh state per test.
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void stampedeProtectionCollapsesConcurrentLoaderCalls() throws Exception {
        final int concurrency = 32;
        final String code = "hotcode";
        AtomicInteger loaderCalls = new AtomicInteger();
        Supplier<Optional<RedisCacheService.LoadedUrl>> slowLoader = () -> {
            loaderCalls.incrementAndGet();
            sleep(150); // simulate slow DB query
            return Optional.of(new RedisCacheService.LoadedUrl(
                    "https://example.com/hot", Duration.ofSeconds(60)));
        };

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);
        AtomicInteger hits = new AtomicInteger();

        for (int i = 0; i < concurrency; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    Optional<String> res = cache.getOrLoadRedirect(code, slowLoader);
                    if (res.isPresent()) hits.incrementAndGet();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(hits.get()).isEqualTo(concurrency);
        // The lock winner runs the loader once. Losers wait 50ms, retry the cache, get the winner's
        // populated value. A very late loser MIGHT still hit the fallback path — allow up to 2
        // loader calls to keep the test resilient without permitting a full stampede.
        assertThat(loaderCalls.get())
                .as("Expected single-flight to keep loader invocations at 1 or 2 under %d concurrent readers", concurrency)
                .isLessThanOrEqualTo(2);
    }

    @Test
    void negativeCacheShortCircuitsLoader() {
        cache.putRedirectMiss("nosuch12", Duration.ofSeconds(60));

        AtomicInteger loaderCalls = new AtomicInteger();
        Optional<String> result = cache.getOrLoadRedirect("nosuch12", () -> {
            loaderCalls.incrementAndGet();
            return Optional.of(new RedisCacheService.LoadedUrl("should-not-reach-here", Duration.ofSeconds(60)));
        });

        assertThat(result).isEmpty();
        assertThat(loaderCalls.get()).isZero();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    RedisConnectionFactory getConnectionFactory() {
        return connectionFactory;
    }
}
