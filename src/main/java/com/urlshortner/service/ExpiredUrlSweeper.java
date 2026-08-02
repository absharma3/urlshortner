package com.urlshortner.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes expired rows from {@code urls} on a schedule.
 *
 * <p>Expired shortCodes already 404 on redirect (via {@code UrlService.isActive}) and their cache
 * entries expire naturally (cache TTL is bounded by {@code expiresAt}), but nothing else prunes
 * the row itself — the table would grow unbounded under any create traffic that uses
 * {@code expiresAt}. The sweeper closes that leak.
 *
 * <p>Uses the {@code ix_urls_expires_at} B-tree index for the DELETE. A grace period keeps
 * just-expired rows around briefly so stats requests immediately after expiry still return
 * something meaningful.
 */
@Component
public class ExpiredUrlSweeper {

    private static final Logger log = LoggerFactory.getLogger(ExpiredUrlSweeper.class);
    private static final String DELETE_SQL =
            "DELETE FROM urls WHERE expires_at IS NOT NULL AND expires_at < (NOW(3) - INTERVAL ? SECOND) LIMIT ?";

    private final JdbcTemplate jdbcTemplate;
    private final int graceSeconds;
    private final int batchLimit;

    public ExpiredUrlSweeper(JdbcTemplate jdbcTemplate,
                             @Value("${app.expiry.grace-seconds:300}") int graceSeconds,
                             @Value("${app.expiry.batch-limit:5000}") int batchLimit) {
        this.jdbcTemplate = jdbcTemplate;
        this.graceSeconds = graceSeconds;
        this.batchLimit = batchLimit;
    }

    @Scheduled(fixedDelayString = "${app.expiry.fixed-delay-ms:600000}",
               initialDelayString = "${app.expiry.initial-delay-ms:60000}")
    public void sweep() {
        try {
            int deleted = jdbcTemplate.update(DELETE_SQL, graceSeconds, batchLimit);
            if (deleted > 0) {
                log.info("Expired-URL sweep removed {} rows (grace={}s, batch={})",
                        deleted, graceSeconds, batchLimit);
            }
        } catch (RuntimeException ex) {
            log.error("Expired-URL sweep failed; will retry next tick", ex);
        }
    }
}
