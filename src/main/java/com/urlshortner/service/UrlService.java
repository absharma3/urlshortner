package com.urlshortner.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.urlshortner.domain.UrlEntity;
import com.urlshortner.dto.CreateUrlRequest;
import com.urlshortner.dto.ShortUrlResponse;
import com.urlshortner.dto.UrlStatsResponse;
import com.urlshortner.repository.UrlRepository;
import com.urlshortner.util.UrlHashGenerator;
import com.urlshortner.util.UrlNormalizer;
import com.urlshortner.util.UrlValidator;

/**
 * Business logic for shortening and resolving URLs.
 *
 * <p>The shorten path is deterministic: same URL → same shortCode (via {@link UrlHashGenerator}).
 * That gives us dedup for free — no separate {@code original_url_hash} column, no INCR-based id
 * allocation. Collisions are resolved via a salt loop: if the generated shortCode is taken by a
 * different URL, retry with {@code url + "_salt_" + n} until an unused code (or an exact match)
 * is found.
 *
 * <p>The read path ({@link #getOriginalUrl}) is intentionally not {@code @Transactional} — cache
 * hits stay off the JDBC pool entirely. The write path is transactional to keep the salt-loop
 * lookup and the insert atomic.
 */
@Service
public class UrlService {

    private static final Logger log = LoggerFactory.getLogger(UrlService.class);
    private static final Duration MIN_TTL = Duration.ofSeconds(1);
    private static final int MAX_SALT_ATTEMPTS = 100;

    private final UrlRepository urlRepository;
    private final RedisCacheService redisCacheService;
    private final UrlNormalizer urlNormalizer;
    private final UrlHashGenerator hashGenerator;
    private final UrlValidator urlValidator;
    private final Duration defaultCacheTtl;
    private final Duration negativeCacheTtl;
    private final String baseUrl;
    private final ServiceMetrics metrics;

    public UrlService(UrlRepository urlRepository,
                      RedisCacheService redisCacheService,
                      UrlNormalizer urlNormalizer,
                      UrlHashGenerator hashGenerator,
                      UrlValidator urlValidator,
                      ServiceMetrics metrics,
                      @Value("${app.cache.ttl-seconds:86400}") long cacheTtlSeconds,
                      @Value("${app.cache.negative-ttl-seconds:60}") long negativeCacheTtlSeconds,
                      @Value("${app.base-url:http://localhost:8080}") String fallbackBaseUrl) {
        this.urlRepository = urlRepository;
        this.redisCacheService = redisCacheService;
        this.urlNormalizer = urlNormalizer;
        this.hashGenerator = hashGenerator;
        this.urlValidator = urlValidator;
        this.metrics = metrics;
        this.defaultCacheTtl = Duration.ofSeconds(cacheTtlSeconds);
        this.negativeCacheTtl = Duration.ofSeconds(negativeCacheTtlSeconds);
        this.baseUrl = stripTrailingSlash(fallbackBaseUrl);
    }

    public Optional<String> getOriginalUrl(String shortCode) {
        return redisCacheService.getOrLoadRedirect(shortCode, () -> loadFromDb(shortCode));
    }

    // Deliberately not @Transactional at method level. Each Spring Data repo call already opens
    // its own short-lived tx; a caught DataIntegrityViolationException or CannotAcquireLockException
    // then aborts just that inner tx (physically rolled back by MySQL on deadlock), and the salt
    // loop retries in a fresh tx. Wrapping the whole loop in an outer tx caused the classic
    // "Transaction silently rolled back because it has been marked as rollback-only" trap.
    public ShortUrlResponse shortenUrl(CreateUrlRequest request) {
        return shortenUrl(request, baseUrl);
    }

    // Deliberately not @Transactional at method level. Each Spring Data repo call already opens
    // its own short-lived tx; a caught DataIntegrityViolationException or CannotAcquireLockException
    // then aborts just that inner tx (physically rolled back by MySQL on deadlock), and the salt
    // loop retries in a fresh tx. Wrapping the whole loop in an outer tx caused the classic
    // "Transaction silently rolled back because it has been marked as rollback-only" trap.
    public ShortUrlResponse shortenUrl(CreateUrlRequest request, String requestBaseUrl) {
        String effectiveBaseUrl = stripTrailingSlash(requestBaseUrl == null ? baseUrl : requestBaseUrl);
        String normalizedUrl = urlNormalizer.normalize(request.originalUrl());
        var validation = urlValidator.validate(normalizedUrl);
        if (!validation.valid()) {
            throw new IllegalArgumentException(validation.reason());
        }

        String customAlias = request.customAlias();
        if (customAlias != null && !customAlias.isBlank()) {
            if (ReservedAliases.isReserved(customAlias)) {
                throw new IllegalArgumentException(
                        "Alias '" + customAlias + "' is reserved and cannot be used.");
            }
            return shortenWithAlias(customAlias, normalizedUrl, request.expiresAt(), effectiveBaseUrl);
        }
        return shortenDeterministically(normalizedUrl, request.expiresAt(), effectiveBaseUrl);
    }

    public UrlStatsResponse getStats(String shortCode) {
        UrlEntity entity = urlRepository.findByShortCode(shortCode)
                .filter(this::isActive)
                .orElseThrow(() -> new NotFoundException(shortCode));
        return new UrlStatsResponse(
                entity.getShortCode(),
                entity.getOriginalUrl(),
                entity.getTotalClicks(),
                entity.getCreatedAt(),
                entity.getExpiresAt());
    }

    // ================================================================
    // Shorten paths
    // ================================================================

    /**
     * Custom-alias branch. If the alias is taken by the exact same URL, 1-to-1 return; if it's
     * taken by a different URL, 409. Aliases deliberately bypass hash-based dedup: multiple
     * aliases can point at the same target when the caller explicitly wants that.
     */
    private ShortUrlResponse shortenWithAlias(String alias, String normalizedUrl, Instant expiresAt, String baseUrl) {
        Optional<UrlEntity> existing = urlRepository.findByShortCode(alias);
        if (existing.isPresent()) {
            UrlEntity entity = existing.get();
            if (entity.getOriginalUrl().equals(normalizedUrl)) {
                cacheUrl(entity);
                return toResponse(entity, baseUrl);
            }
            throw new ShortCodeConflictException(alias);
        }
        return persistNew(alias, normalizedUrl, expiresAt, baseUrl);
    }

    /**
     * Deterministic shorten with salt-loop collision resolution.
     *
     * <ol>
     *     <li>Compute {@code hash(normalizedUrl, saltAttempt)} → shortCode.</li>
     *     <li>Look the code up in MySQL (source of truth).</li>
     *     <li>If it's already stored with the same URL → 1-to-1 match, return existing.</li>
     *     <li>If it's stored with a <em>different</em> URL → salt++, retry.</li>
     *     <li>If it's not stored yet → INSERT. A concurrent write racing us on the same code
     *         hits the {@code short_code} UNIQUE constraint; we catch, re-read, and either
     *         return the winner (if the URL matches ours) or salt++.</li>
     * </ol>
     */
    private ShortUrlResponse shortenDeterministically(String normalizedUrl, Instant expiresAt, String baseUrl) {
        for (int salt = 0; salt < MAX_SALT_ATTEMPTS; salt++) {
            String shortCode = hashGenerator.generateShortCode(normalizedUrl, salt);
            Optional<UrlEntity> existing = urlRepository.findByShortCode(shortCode);
            if (existing.isPresent()) {
                UrlEntity entity = existing.get();
                if (entity.getOriginalUrl().equals(normalizedUrl)) {
                    saltRetryCounter(salt);
                    cacheUrl(entity);
                    return toResponse(entity, baseUrl);
                }
                log.debug("Hash collision on shortCode='{}' at saltAttempt={}, retrying", shortCode, salt);
                continue;
            }
            try {
                UrlEntity persisted = persistEntity(shortCode, normalizedUrl, expiresAt);
                saltRetryCounter(salt);
                cacheUrl(persisted);
                return toResponse(persisted, baseUrl);
            } catch (DataIntegrityViolationException | CannotAcquireLockException raced) {
                // A concurrent writer beat us to this shortCode (UNIQUE violation) OR MySQL
                // deadlocked on the unique-index gap lock. Re-read and either return the winner's
                // row (1-to-1 match) or move to the next salt attempt.
                Optional<UrlEntity> now = urlRepository.findByShortCode(shortCode);
                if (now.isPresent() && now.get().getOriginalUrl().equals(normalizedUrl)) {
                    saltRetryCounter(salt);
                    cacheUrl(now.get());
                    return toResponse(now.get(), baseUrl);
                }
                log.debug("Concurrent insert on shortCode='{}' resolved as collision, retrying", shortCode);
            }
        }
        throw new IllegalStateException(
                "Salt loop exhausted after " + MAX_SALT_ATTEMPTS + " attempts for " + normalizedUrl);
    }

    private ShortUrlResponse persistNew(String shortCode, String normalizedUrl, Instant expiresAt, String baseUrl) {
        UrlEntity saved = persistEntity(shortCode, normalizedUrl, expiresAt);
        cacheUrl(saved);
        return toResponse(saved, baseUrl);
    }

    /**
     * Persists via {@link org.springframework.data.jpa.repository.JpaRepository#saveAndFlush}
     * so a UNIQUE-constraint violation surfaces synchronously as
     * {@link DataIntegrityViolationException}. On failure we {@link EntityManager#clear()} to
     * evict the poisoned transient entity from the persistence context — otherwise subsequent
     * {@code save()} calls in the same transaction would re-flush it and throw again.
     *
     * <p>The outer {@code @Transactional(noRollbackFor = DataIntegrityViolationException.class)}
     * keeps the Spring tx alive across the caught exception so the salt loop can retry within
     * the same transactional scope.
     */
    private UrlEntity persistEntity(String shortCode, String normalizedUrl, Instant expiresAt) {
        // saveAndFlush opens its own short-lived tx (no method-level @Transactional). On DIVE or
        // deadlock, the tx aborts cleanly and the caller's salt-loop retries in a fresh tx.
        UrlEntity entity = new UrlEntity(shortCode, normalizedUrl, expiresAt);
        return urlRepository.saveAndFlush(entity);
    }

    private void saltRetryCounter(int saltAttempts) {
        metrics.recordSaltRetries(saltAttempts);
    }

    // ================================================================
    // Loader and cache helpers
    // ================================================================

    private Optional<RedisCacheService.LoadedUrl> loadFromDb(String shortCode) {
        Optional<UrlEntity> row = urlRepository.findByShortCode(shortCode).filter(this::isActive);
        if (row.isEmpty()) {
            // Cache the miss to absorb repeated 404s (brute force / stale QR / typo).
            redisCacheService.putRedirectMiss(shortCode, negativeCacheTtl);
            return Optional.empty();
        }
        UrlEntity entity = row.get();
        return Optional.of(new RedisCacheService.LoadedUrl(entity.getOriginalUrl(),
                computeTtl(entity.getExpiresAt())));
    }

    private void cacheUrl(UrlEntity entity) {
        redisCacheService.putRedirect(
                entity.getShortCode(),
                entity.getOriginalUrl(),
                computeTtl(entity.getExpiresAt()));
    }

    private Duration computeTtl(Instant expiresAt) {
        if (expiresAt == null) {
            return defaultCacheTtl;
        }
        Duration until = Duration.between(Instant.now(), expiresAt);
        if (until.isNegative() || until.isZero()) {
            return MIN_TTL;
        }
        return until.compareTo(defaultCacheTtl) < 0 ? until : defaultCacheTtl;
    }

    private boolean isActive(UrlEntity entity) {
        Instant expiresAt = entity.getExpiresAt();
        return expiresAt == null || expiresAt.isAfter(Instant.now());
    }

    private ShortUrlResponse toResponse(UrlEntity entity, String requestBaseUrl) {
        return new ShortUrlResponse(
                entity.getShortCode(),
                requestBaseUrl + "/" + entity.getShortCode(),
                entity.getOriginalUrl(),
                entity.getCreatedAt(),
                entity.getExpiresAt());
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
