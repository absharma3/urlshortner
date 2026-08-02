package com.urlshortner.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Canonicalises target URLs so that syntactic variations of the same resource ({@code
 * https://Example.com}, {@code HTTPS://example.com/}, {@code example.com}) collapse to a single
 * fingerprint. The output feeds directly into {@link UrlHashGenerator}, so any pair of inputs that
 * differ only in the transformations below must produce byte-identical strings after normalisation.
 *
 * <p>Transformations applied, in order:
 * <ol>
 *     <li>Trim leading/trailing whitespace.</li>
 *     <li>Default the scheme to {@code https} when none is present.</li>
 *     <li>Lowercase the scheme and host (both are case-insensitive per RFC 3986).</li>
 *     <li>Strip trailing {@code /} from the path (except when the path <em>is</em> {@code /}).</li>
 * </ol>
 *
 * <p>Deliberately conservative: query strings, fragments, and port numbers are preserved
 * verbatim. Two URLs that share every path segment but differ in query ordering are treated as
 * distinct resources — reordering would be surprising and can change semantics for many APIs.
 *
 * <p><b>Note on trailing-slash stripping.</b> This class strips the root path {@code /} as well
 * as any deeper trailing slash, so {@code https://example.com/} and {@code https://example.com}
 * collapse to the same fingerprint. RFC 3986 says an <em>empty</em> path SHOULD be normalised to
 * {@code /}; we diverge because the whole point here is deterministic dedup, and treating those
 * two forms as different resources would double-write. Every observed variation still points at
 * the same origin.
 */
@Component
public class UrlNormalizer {

    private static final String DEFAULT_SCHEME = "https";
    private static final Pattern HAS_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://.*");

    public String normalize(String rawUrl) {
        if (rawUrl == null) {
            throw new IllegalArgumentException("URL must not be null");
        }
        String trimmed = rawUrl.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("URL must not be blank");
        }

        if (!HAS_SCHEME.matcher(trimmed).matches()) {
            trimmed = DEFAULT_SCHEME + "://" + trimmed;
        }

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Invalid URL syntax: " + ex.getReason(), ex);
        }

        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL must include a scheme and host");
        }

        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        // Requirement: remove trailing '/' entirely, including the bare-root case so
        // "https://example.com/" and "https://example.com" collapse to the same fingerprint.
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        StringBuilder out = new StringBuilder(trimmed.length());
        out.append(normalizedScheme).append("://").append(normalizedHost);
        if (uri.getPort() != -1) {
            out.append(':').append(uri.getPort());
        }
        out.append(path);
        if (uri.getRawQuery() != null) {
            out.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null) {
            out.append('#').append(uri.getRawFragment());
        }
        return out.toString();
    }
}
