package com.urlshortner.web;

import java.net.URI;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.urlshortner.dto.CreateUrlRequest;
import com.urlshortner.dto.ShortUrlResponse;
import com.urlshortner.dto.UrlStatsResponse;
import com.urlshortner.exception.NotFoundException;
import com.urlshortner.service.ClickAnalyticsService;
import com.urlshortner.service.UrlService;

import jakarta.validation.Valid;

/**
 * REST surface for the shortener. Three endpoints:
 * <ul>
 *   <li>{@code POST /api/v1/urls} — shorten a URL. Returns 201 with the {@link ShortUrlResponse}
 *       body and a {@code Location} header pointing at the stats endpoint for the new code.</li>
 *   <li>{@code GET /{shortCode}} — redirect (302). The hot path — cache-served, no DB read on
 *       hit. Fire-and-forgets a click to {@link ClickAnalyticsService}.</li>
 *   <li>{@code GET /api/v1/urls/{shortCode}/stats} — totals for a code.</li>
 * </ul>
 *
 * <p>Error mapping (404 / 409 / 429 / 400) lives in {@code GlobalExceptionHandler}, not here —
 * this class is deliberately thin. The canonical wire contract is
 * {@code src/main/resources/openapi.yaml}.
 */
@RestController
public class UrlController {

    private final UrlService urlService;
    private final ClickAnalyticsService clickAnalyticsService;

    public UrlController(UrlService urlService, ClickAnalyticsService clickAnalyticsService) {
        this.urlService = urlService;
        this.clickAnalyticsService = clickAnalyticsService;
    }

    @PostMapping("/api/v1/urls")
    public ResponseEntity<ShortUrlResponse> create(@Valid @RequestBody CreateUrlRequest request,
                                                    UriComponentsBuilder uriBuilder) {
        // Both `Location` header and `shortUrl` body field derive from the service's configured
        // base URL (app.base-url, defaults to localhost:8080, overridable per-env via env var),
        // so the two representations never diverge. UriComponentsBuilder is used only to compose
        // the `/api/v1/urls/{code}` path onto that same base.
        ShortUrlResponse response = urlService.shortenUrl(request);
        URI location = uriBuilder
                .path("/api/v1/urls/{code}")
                .buildAndExpand(response.shortCode())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{shortCode:" + CreateUrlRequest.SHORT_CODE_PATTERN + "}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        String target = urlService.getOriginalUrl(shortCode)
                .orElseThrow(() -> new NotFoundException(shortCode));
        clickAnalyticsService.recordClick(shortCode);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(target))
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @GetMapping("/api/v1/urls/{shortCode:" + CreateUrlRequest.SHORT_CODE_PATTERN + "}/stats")
    public UrlStatsResponse stats(@PathVariable String shortCode) {
        return urlService.getStats(shortCode);
    }
}
