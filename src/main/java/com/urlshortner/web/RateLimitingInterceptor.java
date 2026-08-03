package com.urlshortner.web;

import java.time.Duration;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.urlshortner.exception.RateLimitExceededException;
import com.urlshortner.service.RedisCacheService;
import com.urlshortner.service.RedisCacheService.RateLimitDecision;
import com.urlshortner.service.ServiceMetrics;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Per-IP fixed-window rate limiter with per-endpoint scopes.
 *
 * <p><b>Scopes.</b> {@link #resolveScope} classifies each request into {@code create},
 * {@code redirect}, or {@code stats}. Each scope has its own bucket and its own limit — creates
 * cost a DB write + Redis + hash and are tightly capped; redirects are the hot path and get a
 * generous budget; stats sit in the middle. Falls back to the {@code redirect} scope for anything
 * that doesn't match a known endpoint (defensive).
 *
 * <p><b>Header-spoofing defence.</b> The client IP is <em>only</em> read from
 * {@code X-Forwarded-For} / {@code X-Real-IP} when the direct TCP peer is in the
 * {@code app.security.trusted-proxies} allowlist. Empty allowlist (default) means proxy headers
 * are ignored entirely.
 */
@Component
public class RateLimitingInterceptor implements HandlerInterceptor {

    private static final String HDR_LIMIT = "X-RateLimit-Limit";
    private static final String HDR_REMAINING = "X-RateLimit-Remaining";
    private static final String HDR_RESET = "X-RateLimit-Reset";
    private static final String HDR_SCOPE = "X-RateLimit-Scope";
    private static final String HDR_FORWARDED_FOR = "X-Forwarded-For";
    private static final String HDR_REAL_IP = "X-Real-IP";
    private static final String UNKNOWN_CLIENT_ID = "unknown";

    private final RedisCacheService redisCacheService;
    private final ServiceMetrics metrics;
    private final Duration window;
    private final Map<RateLimitScope, Integer> limits;
    private final Set<String> trustedProxies;

    public RateLimitingInterceptor(
            RedisCacheService redisCacheService,
            ServiceMetrics metrics,
            @Value("${app.ratelimit.window-seconds:60}") long windowSeconds,
            @Value("${app.ratelimit.create-limit:10}") int createLimit,
            @Value("${app.ratelimit.redirect-limit:600}") int redirectLimit,
            @Value("${app.ratelimit.stats-limit:60}") int statsLimit,
            @Value("${app.security.trusted-proxies:}") String trustedProxiesCsv) {
        this.redisCacheService = redisCacheService;
        this.metrics = metrics;
        this.window = Duration.ofSeconds(windowSeconds);
        this.limits = new EnumMap<>(RateLimitScope.class);
        this.limits.put(RateLimitScope.CREATE, createLimit);
        this.limits.put(RateLimitScope.REDIRECT, redirectLimit);
        this.limits.put(RateLimitScope.STATS, statsLimit);
        this.trustedProxies = parseTrustedProxies(trustedProxiesCsv);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        RateLimitScope scope = resolveScope(request);
        String clientId = scope.label() + ":" + resolveClientIp(request);
        int limit = limits.get(scope);

        RateLimitDecision decision = redisCacheService.attemptRateLimit(clientId, limit, window);

        response.setHeader(HDR_LIMIT, String.valueOf(decision.limit()));
        response.setHeader(HDR_REMAINING, String.valueOf(decision.remaining()));
        response.setHeader(HDR_RESET, String.valueOf(decision.resetEpochSeconds()));
        response.setHeader(HDR_SCOPE, scope.label());

        if (!decision.allowed()) {
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
            metrics.recordRateLimitDenied(scope.label());
            throw new RateLimitExceededException(
                    decision.limit(),
                    decision.retryAfterSeconds(),
                    decision.resetEpochSeconds());
        }
        return true;
    }

    /**
     * Very-lightweight path routing — string matching is faster than reflecting on the handler,
     * and the URL shapes are stable.
     */
    private RateLimitScope resolveScope(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean isPost = HttpMethod.POST.matches(request.getMethod());
        if (path.equals("/api/v1/urls") && isPost) {
            return RateLimitScope.CREATE;
        }
        if (path.startsWith("/api/v1/urls/") && path.endsWith("/stats")) {
            return RateLimitScope.STATS;
        }
        return RateLimitScope.REDIRECT;
    }

    private String resolveClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return UNKNOWN_CLIENT_ID;
        }
        if (!trustedProxies.contains(remoteAddr)) {
            return remoteAddr;
        }
        String forwarded = request.getHeader(HDR_FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String head = (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
            if (!head.isEmpty()) {
                return head;
            }
        }
        String realIp = request.getHeader(HDR_REAL_IP);
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return remoteAddr;
    }

    private static Set<String> parseTrustedProxies(String csv) {
        Set<String> result = new HashSet<>();
        if (csv == null || csv.isBlank()) {
            return Set.copyOf(result);
        }
        for (String token : csv.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return Set.copyOf(result);
    }
}
