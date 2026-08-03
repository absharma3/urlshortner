package com.urlshortner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.urlshortner.domain.UrlEntity;
import com.urlshortner.dto.CreateUrlRequest;
import com.urlshortner.dto.ShortUrlResponse;
import com.urlshortner.exception.ShortCodeConflictException;
import com.urlshortner.repository.UrlRepository;
import com.urlshortner.util.Base62Encoder;
import com.urlshortner.util.HostResolver;
import com.urlshortner.util.UrlHashGenerator;
import com.urlshortner.util.UrlNormalizer;
import com.urlshortner.util.UrlValidator;

@ExtendWith(MockitoExtension.class)
class UrlServiceTest {

    private static final String BASE_URL = "http://short.test";
    private static final long CACHE_TTL_SECONDS = 3600L;

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private RedisCacheService redisCacheService;

    private final UrlNormalizer urlNormalizer = new UrlNormalizer();
    private final UrlValidator urlValidator = new UrlValidator(new HostResolver(1500L));

    @Nested
    class GetOriginalUrl {

        private UrlService urlService;

        @BeforeEach
        void setUp() {
            urlService = newService(realHashGenerator());
        }

        @Test
        void delegatesToRedisCacheServiceAndReturnsResult() {
            when(redisCacheService.getOrLoadRedirect(eq("abc123"), any()))
                    .thenReturn(Optional.of("https://example.com/hit"));

            Optional<String> result = urlService.getOriginalUrl("abc123");

            assertThat(result).contains("https://example.com/hit");
        }
    }

    @Nested
    class ShortenUrl {

        private UrlHashGenerator hashGenerator;
        private UrlService urlService;

        @BeforeEach
        void setUp() {
            hashGenerator = realHashGenerator();
            urlService = newService(hashGenerator);
            // saveAndFlush just echoes the entity back with an id + createdAt stamped in.
            org.mockito.Mockito.lenient()
                    .when(urlRepository.saveAndFlush(any(UrlEntity.class)))
                    .thenAnswer(inv -> {
                        UrlEntity e = inv.getArgument(0);
                        ReflectionTestUtils.setField(e, "id", 42L);
                        ReflectionTestUtils.setField(e, "createdAt", Instant.now());
                        return e;
                    });
        }

        @Test
        void sameNormalizedUrlYieldsSameShortCode() {
            String rawUrl = "https://example.com/dedup";
            String normalized = urlNormalizer.normalize(rawUrl);
            String expectedCode = hashGenerator.generateShortCode(normalized, 0);
            UrlEntity persisted = persistedEntity(expectedCode, normalized, null);

            // First call: DB empty → saveAndFlush → persisted. Second call: already there → 1-to-1 return.
            when(urlRepository.findByShortCode(expectedCode))
                    .thenReturn(Optional.empty())      // pre-insert lookup on call #1
                    .thenReturn(Optional.of(persisted)); // pre-insert lookup on call #2

            ShortUrlResponse first = urlService.shortenUrl(new CreateUrlRequest(rawUrl, null, null));
            ShortUrlResponse second = urlService.shortenUrl(new CreateUrlRequest(rawUrl, null, null));

            assertThat(first.shortCode()).isEqualTo(expectedCode);
            assertThat(second.shortCode()).isEqualTo(expectedCode);
            // Exactly one INSERT across the two calls.
            verify(urlRepository, times(1)).saveAndFlush(any(UrlEntity.class));
        }

        @Test
        void collisionTriggersSaltLoopAndPersistsWithSaltedCode() {
            String rawUrl = "https://example.com/collide";
            String normalized = urlNormalizer.normalize(rawUrl);
            String saltZeroCode = hashGenerator.generateShortCode(normalized, 0);
            String saltOneCode = hashGenerator.generateShortCode(normalized, 1);

            UrlEntity occupier = persistedEntity(saltZeroCode, "https://different.example.com/target", null);

            when(urlRepository.findByShortCode(saltZeroCode)).thenReturn(Optional.of(occupier));
            when(urlRepository.findByShortCode(saltOneCode)).thenReturn(Optional.empty());

            ShortUrlResponse response = urlService.shortenUrl(new CreateUrlRequest(rawUrl, null, null));

            assertThat(response.shortCode()).isEqualTo(saltOneCode);
            verify(urlRepository).saveAndFlush(any(UrlEntity.class));
        }

        @Test
        void existingCodeWithMatchingUrlReturnsOneToOneWithoutInsert() {
            String rawUrl = "https://example.com/existing";
            String normalized = urlNormalizer.normalize(rawUrl);
            String code = hashGenerator.generateShortCode(normalized, 0);
            UrlEntity existing = persistedEntity(code, normalized, null);
            when(urlRepository.findByShortCode(code)).thenReturn(Optional.of(existing));

            ShortUrlResponse response = urlService.shortenUrl(new CreateUrlRequest(rawUrl, null, null));

            assertThat(response.shortCode()).isEqualTo(code);
            verify(urlRepository, never()).saveAndFlush(any(UrlEntity.class));
            verify(redisCacheService).putRedirect(eq(code), eq(normalized), any(Duration.class));
        }

        @Test
        void customAliasBypassesHashLoopAndCreatesFreshRow() {
            String rawUrl = "https://example.com/aliased";
            String alias = "myAlias99";
            when(urlRepository.findByShortCode(alias)).thenReturn(Optional.empty());

            ShortUrlResponse response = urlService.shortenUrl(
                    new CreateUrlRequest(rawUrl, alias, null));

            assertThat(response.shortCode()).isEqualTo(alias);
            verify(urlRepository).saveAndFlush(any(UrlEntity.class));
        }

        @Test
        void customAliasPointingAtSameUrlReturnsExisting() {
            String rawUrl = "https://example.com/aliased";
            String alias = "myAlias99";
            String normalized = urlNormalizer.normalize(rawUrl);
            UrlEntity existing = persistedEntity(alias, normalized, null);
            when(urlRepository.findByShortCode(alias)).thenReturn(Optional.of(existing));

            ShortUrlResponse response = urlService.shortenUrl(
                    new CreateUrlRequest(rawUrl, alias, null));

            assertThat(response.shortCode()).isEqualTo(alias);
            verify(urlRepository, never()).saveAndFlush(any(UrlEntity.class));
        }

        @Test
        void customAliasTakenByDifferentUrlThrowsConflict() {
            String alias = "conflictA1";
            UrlEntity occupier = persistedEntity(alias, "https://other.example.com/", null);
            when(urlRepository.findByShortCode(alias)).thenReturn(Optional.of(occupier));

            assertThatThrownBy(() -> urlService.shortenUrl(
                    new CreateUrlRequest("https://example.com/mine", alias, null)))
                    .isInstanceOf(ShortCodeConflictException.class)
                    .hasMessageContaining(alias);
            verify(urlRepository, never()).saveAndFlush(any(UrlEntity.class));
        }

        @Test
        void reservedAliasRejected() {
            assertThatThrownBy(() -> urlService.shortenUrl(
                    new CreateUrlRequest("https://example.com/foo", "healthz", null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reserved");
            verify(urlRepository, never()).saveAndFlush(any(UrlEntity.class));
        }

        @Test
        void ssrfLoopbackRejected() {
            assertThatThrownBy(() -> urlService.shortenUrl(
                    new CreateUrlRequest("http://127.0.0.1/admin", null, null)))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(urlRepository, never()).saveAndFlush(any(UrlEntity.class));
            verifyNoInteractions(redisCacheService);
        }

        @Test
        void nonHttpSchemeRejected() {
            assertThatThrownBy(() -> urlService.shortenUrl(
                    new CreateUrlRequest("ftp://example.com/x", null, null)))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(urlRepository, never()).saveAndFlush(any(UrlEntity.class));
        }

        @Test
        void withFutureExpirationCapsCacheTtl() {
            String rawUrl = "https://example.com/expiring";
            String normalized = urlNormalizer.normalize(rawUrl);
            String code = hashGenerator.generateShortCode(normalized, 0);
            Instant expiresAt = Instant.now().plusSeconds(60);
            when(urlRepository.findByShortCode(code)).thenReturn(Optional.empty());

            urlService.shortenUrl(new CreateUrlRequest(rawUrl, null, expiresAt));

            ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
            verify(redisCacheService).putRedirect(eq(code), eq(normalized), ttl.capture());
            assertThat(ttl.getValue().getSeconds()).isLessThanOrEqualTo(60L);
        }
    }

    // ================================================================
    // helpers
    // ================================================================

    private UrlService newService(UrlHashGenerator hashGen) {
        return new UrlService(
                urlRepository,
                redisCacheService,
                urlNormalizer,
                hashGen,
                urlValidator,
                stubMetrics(),
                CACHE_TTL_SECONDS,
                60L,
                BASE_URL);
    }

    private static UrlHashGenerator realHashGenerator() {
        return new UrlHashGenerator(new Base62Encoder(), 8);
    }

    private static ServiceMetrics stubMetrics() {
        return new ServiceMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    private static UrlEntity persistedEntity(String code, String url, Instant expiresAt) {
        UrlEntity entity = new UrlEntity(code, url, expiresAt);
        ReflectionTestUtils.setField(entity, "id", 42L);
        ReflectionTestUtils.setField(entity, "createdAt", Instant.now());
        return entity;
    }
}
