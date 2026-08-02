package com.urlshortner.dto;

import java.time.Instant;

public record UrlStatsResponse(
        String shortCode,
        String originalUrl,
        long totalClicks,
        Instant createdAt,
        Instant expiresAt) {
}
