package com.urlshortner;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The <em>positive</em> half of the XFF-handling coverage: with the local peer address on the
 * trusted-proxy allowlist, per-request {@code X-Forwarded-For} values become the effective client
 * id and produce distinct rate-limit buckets — so 100+ requests with unique XFF values must all
 * succeed.
 *
 * <p>The negative half (spoofed XFF ignored) lives in {@link UrlShortenerIntegrationTest}.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UrlShortenerTrustedProxyIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("urlshortener")
            .withUsername("test")
            .withPassword("test");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("app.ratelimit.window-seconds", () -> "60");
        registry.add("app.ratelimit.create-limit", () -> "5");
        registry.add("app.ratelimit.redirect-limit", () -> "5");
        registry.add("app.ratelimit.stats-limit", () -> "5");
        // The TestRestTemplate/HttpClient hits Tomcat over 127.0.0.1, so trust the loopback peer.
        registry.add("app.security.trusted-proxies", () -> "127.0.0.1");
    }

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @BeforeEach
    void resetState() {
        jdbcTemplate.execute("TRUNCATE TABLE urls");
        try (RedisConnection connection = Objects.requireNonNull(redisTemplate.getConnectionFactory()).getConnection()) {
            connection.serverCommands().flushAll();
        }
    }

    @Test
    void xffProducesDistinctBucketsWhenPeerIsTrusted() throws Exception {
        // With stats-limit=5 and every request bearing a UNIQUE XFF, each request occupies its
        // own bucket → none should trip. 12 requests with 12 unique XFFs proves XFF is being
        // honored (12 in one shared bucket would trip well before 12).
        for (int i = 1; i <= 12; i++) {
            HttpResponse<String> res = send("/api/v1/urls/xffTest/stats", "203.0.113." + i);
            assertThat(res.statusCode())
                    .as("request #%d with unique XFF must occupy its own bucket", i)
                    .isNotEqualTo(429);
        }
    }

    @Test
    void repeatedXffFromSameTrustedPeerStillCountsInOneBucket() throws Exception {
        String xff = "198.51.100.7";
        int trippedAt = -1;
        for (int i = 1; i <= 8; i++) {
            HttpResponse<String> res = send("/api/v1/urls/sameXff/stats", xff);
            if (res.statusCode() == 429) {
                trippedAt = i;
                break;
            }
        }
        assertThat(trippedAt)
                .as("with 5/min limit, same XFF must trip on or before request 6")
                .isGreaterThan(0)
                .isLessThanOrEqualTo(6);
    }

    private HttpResponse<String> send(String path, String xff) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("X-Forwarded-For", xff)
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
