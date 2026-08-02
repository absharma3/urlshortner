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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UrlShortenerIntegrationTest {

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
        registry.add("app.ratelimit.create-limit", () -> "100");
        registry.add("app.ratelimit.redirect-limit", () -> "100");
        registry.add("app.ratelimit.stats-limit", () -> "100");
    }

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private final JsonMapper mapper = JsonMapper.builder().build();
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
    void endToEndCreateRedirectAndStats() throws Exception {
        String originalUrl = "https://example.com/some/very/long/target?a=1&b=2";

        HttpResponse<String> createResponse = post("/api/v1/urls",
                "{\"originalUrl\":\"" + originalUrl + "\"}");
        assertThat(createResponse.statusCode()).isEqualTo(201);
        assertThat(createResponse.headers().firstValue("Location")).isPresent();

        JsonNode created = mapper.readTree(createResponse.body());
        String shortCode = created.get("shortCode").asText();
        assertThat(shortCode).matches("^[a-zA-Z0-9]{4,32}$");
        assertThat(created.get("originalUrl").asText()).isEqualTo(originalUrl);

        HttpResponse<String> redirectResponse = get("/" + shortCode);
        assertThat(redirectResponse.statusCode()).isEqualTo(302);
        assertThat(redirectResponse.headers().firstValue("Location"))
                .contains(originalUrl);
        assertThat(redirectResponse.headers().firstValue("Cache-Control"))
                .contains("no-store");

        HttpResponse<String> statsResponse = get("/api/v1/urls/" + shortCode + "/stats");
        assertThat(statsResponse.statusCode()).isEqualTo(200);
        JsonNode stats = mapper.readTree(statsResponse.body());
        assertThat(stats.get("shortCode").asText()).isEqualTo(shortCode);
        assertThat(stats.get("originalUrl").asText()).isEqualTo(originalUrl);
        assertThat(stats.get("totalClicks").asLong()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void dedupReturnsSameShortCodeForRepeatedUrlWithoutAlias() throws Exception {
        String url = "https://example.com/dedup-target";
        String payload = "{\"originalUrl\":\"" + url + "\"}";

        HttpResponse<String> first = post("/api/v1/urls", payload);
        HttpResponse<String> second = post("/api/v1/urls", payload);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(201);
        String firstCode = mapper.readTree(first.body()).get("shortCode").asText();
        String secondCode = mapper.readTree(second.body()).get("shortCode").asText();
        assertThat(secondCode).isEqualTo(firstCode);

        // MySQL should still hold only one row for that URL
        Long rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM urls WHERE original_url = ?", Long.class, url);
        assertThat(rowCount).isEqualTo(1L);
    }

    @Test
    void customAliasBypassesDedupAndCreatesAdditionalRow() throws Exception {
        String url = "https://example.com/dedup-alias-target";

        HttpResponse<String> first = post("/api/v1/urls",
                "{\"originalUrl\":\"" + url + "\"}");
        HttpResponse<String> second = post("/api/v1/urls",
                "{\"originalUrl\":\"" + url + "\",\"customAlias\":\"customA1\"}");

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(201);
        String firstCode = mapper.readTree(first.body()).get("shortCode").asText();
        assertThat(mapper.readTree(second.body()).get("shortCode").asText()).isEqualTo("customA1");
        assertThat(firstCode).isNotEqualTo("customA1");

        Long rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM urls WHERE original_url = ?", Long.class, url);
        assertThat(rowCount).isEqualTo(2L);
    }

    @Test
    void ssrfLoopbackTargetRejectedWith400() throws Exception {
        HttpResponse<String> response = post("/api/v1/urls",
                "{\"originalUrl\":\"http://127.0.0.1/admin\"}");
        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode problem = mapper.readTree(response.body());
        assertThat(problem.get("status").asInt()).isEqualTo(400);
    }

    @Test
    void nonHttpSchemeRejectedWith400() throws Exception {
        HttpResponse<String> response = post("/api/v1/urls",
                "{\"originalUrl\":\"ftp://example.com/x\"}");
        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void invalidPayloadReturnsProblemDetailWith400() throws Exception {
        HttpResponse<String> response = post("/api/v1/urls", "{\"originalUrl\":\"not-a-url\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type"))
                .isPresent()
                .hasValueSatisfying(ct -> assertThat(ct).contains("application/problem+json"));
        JsonNode problem = mapper.readTree(response.body());
        assertThat(problem.get("status").asInt()).isEqualTo(400);
        assertThat(problem.get("title").asText()).isEqualTo("Bad Request");
    }

    @Test
    void duplicateCustomAliasReturns409ProblemDetail() throws Exception {
        String payload = "{\"originalUrl\":\"https://example.com/a\",\"customAlias\":\"conflictA\"}";

        HttpResponse<String> first = post("/api/v1/urls", payload);
        assertThat(first.statusCode()).isEqualTo(201);

        // Second request re-uses the alias but with a different URL so dedup won't short-circuit
        String payload2 = "{\"originalUrl\":\"https://example.com/b\",\"customAlias\":\"conflictA\"}";
        HttpResponse<String> second = post("/api/v1/urls", payload2);
        assertThat(second.statusCode()).isEqualTo(409);
        JsonNode problem = mapper.readTree(second.body());
        assertThat(problem.get("status").asInt()).isEqualTo(409);
        assertThat(problem.get("title").asText()).isEqualTo("Conflict");
    }

    @Test
    void rateLimit_101stRequestReturns429WithHeaders() throws Exception {
        String probeUrl = "/api/v1/urls/rateProbe/stats";

        for (int i = 1; i <= 100; i++) {
            HttpResponse<String> response = get(probeUrl);
            assertThat(response.statusCode())
                    .as("request #%d must not be rate-limited", i)
                    .isNotEqualTo(429);
        }

        HttpResponse<String> response = get(probeUrl);
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("X-RateLimit-Limit")).contains("100");
        assertThat(response.headers().firstValue("X-RateLimit-Remaining")).contains("0");
        assertThat(response.headers().firstValue("X-RateLimit-Reset")).isPresent();
        assertThat(response.headers().firstValue("Retry-After")).isPresent();

        JsonNode problem = mapper.readTree(response.body());
        assertThat(problem.get("status").asInt()).isEqualTo(429);
        assertThat(problem.get("title").asText()).isEqualTo("Too Many Requests");
    }

    @Test
    void spoofedXForwardedForIgnoredWhenNoTrustedProxyConfigured() throws Exception {
        // With trusted-proxies empty by default, XFF is ignored and quota is shared across "fake" IPs.
        for (int i = 1; i <= 100; i++) {
            HttpResponse<String> response = getWithHeader(
                    "/api/v1/urls/spoofP99/stats", "X-Forwarded-For", "203.0.113." + i);
            assertThat(response.statusCode())
                    .as("spoofed request #%d must not be rate-limited yet", i)
                    .isNotEqualTo(429);
        }
        HttpResponse<String> tripped = getWithHeader(
                "/api/v1/urls/spoofP99/stats", "X-Forwarded-For", "203.0.113.255");
        assertThat(tripped.statusCode()).isEqualTo(429);
    }

    @Test
    void concurrentShortensOfSameUrlAllReturnTheSameCode() throws Exception {
        String url = "https://example.com/concurrent-target";
        String payload = "{\"originalUrl\":\"" + url + "\"}";

        int concurrency = 20;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(concurrency);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<String>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                HttpResponse<String> res = post("/api/v1/urls", payload);
                if (res.statusCode() != 201) return "STATUS_" + res.statusCode();
                return mapper.readTree(res.body()).get("shortCode").asText();
            }));
        }
        start.countDown();
        java.util.Set<String> observedCodes = new java.util.HashSet<>();
        for (var f : futures) {
            observedCodes.add(f.get(15, java.util.concurrent.TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        // All concurrent shorteners agree on the same code, and MySQL holds exactly one row.
        assertThat(observedCodes).hasSize(1);
        Long rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM urls WHERE original_url = ?", Long.class, url);
        assertThat(rowCount).isEqualTo(1L);
    }

    @Test
    void expiredUrlReturns404OnRedirect() throws Exception {
        // Create with a 2-second expiry, sleep past it, expect 404 on redirect.
        String futureIso = java.time.Instant.now().plusSeconds(2).toString();
        HttpResponse<String> create = post("/api/v1/urls",
                "{\"originalUrl\":\"https://example.com/expiring\",\"expiresAt\":\"" + futureIso + "\"}");
        assertThat(create.statusCode()).isEqualTo(201);
        String shortCode = mapper.readTree(create.body()).get("shortCode").asText();

        HttpResponse<String> beforeExpiry = get("/" + shortCode);
        assertThat(beforeExpiry.statusCode()).isEqualTo(302);

        Thread.sleep(3000);
        HttpResponse<String> afterExpiry = get("/" + shortCode);
        assertThat(afterExpiry.statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> post(String path, String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getWithHeader(String path, String name, String value) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header(name, value)
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
