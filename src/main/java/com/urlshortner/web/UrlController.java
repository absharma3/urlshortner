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
import com.urlshortner.service.ClickAnalyticsService;
import com.urlshortner.service.NotFoundException;
import com.urlshortner.service.UrlService;

import jakarta.validation.Valid;

@RestController
public class UrlController {

    private static final String SHORT_CODE_PATTERN = "[a-zA-Z0-9]{4,32}";

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

    @GetMapping("/{shortCode:" + SHORT_CODE_PATTERN + "}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        String target = urlService.getOriginalUrl(shortCode)
                .orElseThrow(() -> new NotFoundException(shortCode));
        clickAnalyticsService.recordClick(shortCode);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(target))
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @GetMapping("/api/v1/urls/{shortCode:" + SHORT_CODE_PATTERN + "}/stats")
    public UrlStatsResponse stats(@PathVariable String shortCode) {
        return urlService.getStats(shortCode);
    }
}
