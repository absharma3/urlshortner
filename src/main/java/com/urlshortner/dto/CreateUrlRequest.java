package com.urlshortner.dto;

import java.time.Instant;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Public shorten-URL payload.
 *
 * <p>Note the strict alphanumeric {@code customAlias} pattern — no {@code _} or {@code -}. This
 * keeps aliases within the Base62 alphabet so they never collide with reserved characters used
 * by URL schemes, HTML fragment identifiers, or common CDN routing patterns.
 *
 * <p>Full URL validity (scheme allow-list + SSRF guard) is enforced deeper in {@code UrlService}
 * via {@code UrlValidator} — annotations here handle the fast, syntactic guard so obvious garbage
 * is rejected without a service round-trip.
 */
public record CreateUrlRequest(
        @NotBlank
        @Size(max = 2048)
        @Pattern(regexp = "^https?://.+", message = "originalUrl must be an absolute http(s) URL")
        String originalUrl,

        @Pattern(regexp = "^" + CreateUrlRequest.SHORT_CODE_PATTERN + "$",
                message = "customAlias must match ^[a-zA-Z0-9]{4,32}$")
        String customAlias,

        @Future
        Instant expiresAt) {

    /**
     * Shared regex body (no anchors) for a valid short code — both auto-generated codes and
     * user-supplied {@code customAlias} values must match. {@code UrlController} uses this in the
     * {@code @GetMapping} path pattern to route only well-shaped codes to the redirect handler.
     */
    public static final String SHORT_CODE_PATTERN = "[a-zA-Z0-9]{4,32}";
}
