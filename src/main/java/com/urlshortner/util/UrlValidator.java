package com.urlshortner.util;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Security guard for user-supplied target URLs. Runs <em>after</em> {@link UrlNormalizer} so the
 * scheme/host are already lowercased and the default {@code https://} has been applied.
 *
 * <ul>
 *     <li><b>Open redirect</b> — refuses any scheme other than {@code http} / {@code https}.</li>
 *     <li><b>SSRF</b> — refuses hostnames in a static blocklist and any host that resolves to
 *         loopback / link-local / private / multicast address ranges.</li>
 * </ul>
 *
 * <p>DNS resolution goes through {@link HostResolver}, which bounds the latency budget so a
 * slow/hostile resolver can't pin request threads. If DNS resolution fails (unknown host or
 * timeout), we <b>fail open</b> at that step — the service never fetches the URL itself, it only
 * hands it to the client, so an unresolvable host at shorten-time doesn't create an SSRF risk for
 * us. It merely means the caller shortened a URL that may not resolve later. The static hostname
 * and scheme guards still apply.
 */
@Component
public class UrlValidator {

    private static final Logger log = LoggerFactory.getLogger(UrlValidator.class);

    private static final int MAX_URL_LENGTH = 2048;
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "localhost",
            "metadata.google.internal",
            "metadata.goog"
    );

    private final HostResolver hostResolver;

    public UrlValidator(HostResolver hostResolver) {
        this.hostResolver = hostResolver;
    }

    public ValidationResult validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return ValidationResult.invalid("URL must not be blank.");
        }
        if (rawUrl.length() > MAX_URL_LENGTH) {
            return ValidationResult.invalid("URL exceeds " + MAX_URL_LENGTH + "-character limit.");
        }

        URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (URISyntaxException ex) {
            return ValidationResult.invalid("URL is not syntactically valid: " + ex.getReason());
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            return ValidationResult.invalid("URL scheme must be http or https.");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return ValidationResult.invalid("URL must include a host.");
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (BLOCKED_HOSTS.contains(normalizedHost)) {
            return ValidationResult.invalid("URL host is blocked.");
        }

        Optional<InetAddress> resolved = hostResolver.resolve(host);
        if (resolved.isEmpty()) {
            // Fail-open on resolution failure: we don't fetch the URL, we only serve it back
            // as a Location. Someone else's browser will do the fetch. Log for observability.
            log.info("Accepting URL with unresolvable/slow host='{}' — skipping address-range check", host);
            return ValidationResult.ok();
        }
        if (isBlockedAddress(resolved.get())) {
            return ValidationResult.invalid("URL host resolves to a disallowed address range.");
        }
        return ValidationResult.ok();
    }

    private static boolean isBlockedAddress(InetAddress address) {
        return address.isLoopbackAddress()          // 127.0.0.0/8, ::1
                || address.isAnyLocalAddress()      // 0.0.0.0, ::
                || address.isLinkLocalAddress()     // 169.254.0.0/16 (incl. AWS/GCP metadata), fe80::/10
                || address.isSiteLocalAddress()     // 10/8, 172.16/12, 192.168/16
                || address.isMulticastAddress();    // 224.0.0.0/4, ff00::/8
    }

    public record ValidationResult(boolean valid, String reason) {

        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(String reason) {
            return new ValidationResult(false, reason);
        }
    }
}
