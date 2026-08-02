package com.urlshortner.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lightweight liveness/readiness probe.
 *
 * <p>Runs an actual round-trip against both dependencies so orchestrators (Docker Compose,
 * Kubernetes, load balancers) can distinguish a healthy app from one where MySQL or Redis are
 * gone but the JVM is still serving. Returns 200 only when both probes succeed; otherwise 503
 * with the failing component in the JSON body.
 *
 * <p>The {@code timestamp} field is an ISO-8601 instant in UTC ({@code 2026-08-02T18:35:24.5Z}).
 */
@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final DataSource dataSource;
    private final StringRedisTemplate redisTemplate;

    public HealthController(DataSource dataSource, StringRedisTemplate redisTemplate) {
        this.dataSource = dataSource;
        this.redisTemplate = redisTemplate;
    }

    @GetMapping("/healthz")
    public ResponseEntity<Map<String, Object>> health() {
        boolean mysqlUp = probeMysql();
        boolean redisUp = probeRedis();
        boolean up = mysqlUp && redisUp;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", up ? "UP" : "DOWN");
        body.put("timestamp", Instant.now().toString());
        body.put("mysql", mysqlUp ? "UP" : "DOWN");
        body.put("redis", redisUp ? "UP" : "DOWN");
        return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private boolean probeMysql() {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT 1")) {
            return rs.next() && rs.getInt(1) == 1;
        } catch (Exception ex) {
            log.warn("MySQL health probe failed: {}", ex.getMessage());
            return false;
        }
    }

    private boolean probeRedis() {
        try {
            String pong = redisTemplate.getConnectionFactory()
                    .getConnection()
                    .ping();
            return pong != null;
        } catch (Exception ex) {
            log.warn("Redis health probe failed: {}", ex.getMessage());
            return false;
        }
    }
}
