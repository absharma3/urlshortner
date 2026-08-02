package com.urlshortner.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Asynchronous, non-blocking click aggregation.
 *
 * <p>Two moving parts:
 * <ol>
 *   <li><b>Hot-path recording</b> ({@link #recordClick}) — invoked once per redirect via
 *       {@code @Async}. Atomically increments the code's counter <b>and</b> adds it to the
 *       {@code clicks:active} set, so the flusher never has to scan the keyspace.</li>
 *   <li><b>Batch flush</b> ({@link #flushClicks}) — {@code @Scheduled} tick that pops a batch
 *       of active codes ({@code SPOP}, O(1) per element), drains each counter with {@code GETDEL},
 *       and pushes a single {@code JdbcTemplate.batchUpdate} to MySQL.</li>
 * </ol>
 *
 * <p>The old implementation used {@code redisTemplate.scan("clicks:*")}. That's an O(N) linear
 * pass over Redis's keyspace on every tick — a fatal production bug at scale. The active-keys
 * set turns each flush into O(batch size), independent of the total number of shortened URLs.
 */
@Service
public class ClickAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(ClickAnalyticsService.class);
    private static final String FLUSH_SQL =
            "UPDATE urls SET total_clicks = total_clicks + ? WHERE short_code = ?";

    private final RedisCacheService redisCacheService;
    private final JdbcTemplate jdbcTemplate;
    private final ServiceMetrics metrics;
    private final int batchSize;

    public ClickAnalyticsService(RedisCacheService redisCacheService,
                                 JdbcTemplate jdbcTemplate,
                                 ServiceMetrics metrics,
                                 @Value("${app.clicks.flush-batch-size:1000}") int batchSize) {
        this.redisCacheService = redisCacheService;
        this.jdbcTemplate = jdbcTemplate;
        this.metrics = metrics;
        this.batchSize = batchSize;
    }

    /**
     * Fire-and-forget click recording. Runs on the virtual-thread executor so it never blocks the
     * redirect response. Redis errors are swallowed inside {@link RedisCacheService} — a lost
     * click is preferable to a failed redirect.
     */
    @Async
    public void recordClick(String shortCode) {
        redisCacheService.recordClick(shortCode);
    }

    /**
     * Drain up to {@code batchSize} counters and persist them to MySQL in a single batch.
     *
     * <p>Failure semantics: if the batch UPDATE fails, we re-queue every popped code back into the
     * active-keys set. The counters themselves stay drained (Redis {@code GETDEL} already removed
     * them), so re-queueing without re-populating the counter risks empty flushes — that's
     * acceptable and preferable to double-counting.
     */
    @Scheduled(fixedDelayString = "${app.clicks.flush-fixed-delay-ms:5000}")
    public void flushClicks() {
        long startNanos = System.nanoTime();
        int totalRows = 0;
        Set<String> codes = redisCacheService.popActiveClickCodes(batchSize);
        try {
            if (codes.isEmpty()) {
                return;
            }
            List<Object[]> batch = new ArrayList<>(codes.size());
            for (String code : codes) {
                Long delta = redisCacheService.drainClickCount(code);
                if (delta == null || delta <= 0L) {
                    continue;
                }
                batch.add(new Object[]{delta, code});
            }
            if (batch.isEmpty()) {
                return;
            }

            try {
                int[] rowsPerUpdate = jdbcTemplate.batchUpdate(FLUSH_SQL, batch);
                for (int rows : rowsPerUpdate) {
                    if (rows > 0) {
                        totalRows += rows;
                    }
                }
                log.debug("Flushed {} click deltas across {} rows", batch.size(), totalRows);
            } catch (RuntimeException ex) {
                log.error("Batch click flush failed for {} entries; re-queueing codes for retry",
                        batch.size(), ex);
                for (String code : codes) {
                    redisCacheService.reQueueClickCode(code);
                }
            }
        } finally {
            metrics.recordFlush(java.time.Duration.ofNanos(System.nanoTime() - startNanos), totalRows);
        }
    }
}
