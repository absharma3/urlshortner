package com.urlshortner.util;

import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.google.common.hash.Hashing;

/**
 * Deterministic short-code generator.
 *
 * <p>For a given (normalisedUrl, saltAttempt) pair, {@link #generateShortCode} always returns
 * the same six-character alphanumeric Base62 string. This is the property that lets the shorten
 * write path skip the DB entirely on repeat requests for the same URL:
 * {@code same URL → same shortCode → DB says "already there" → return existing}.
 *
 * <p>The hash is <b>MurmurHash3 32-bit</b>. It is not cryptographic; we do not care about
 * collision-resistance against adversarial input because the collision-handling strategy is
 * built into {@link UrlHashGenerator}'s caller — a salt loop that retries with
 * {@code url + "_salt_" + n} until an unused code is found.
 *
 * <p><b>Output shape:</b> 32-bit hash → unsigned long (0…2³²−1) → Base62 → left-padded with
 * {@code '0'} to a fixed 6 characters (62⁶ ≈ 5.7×10¹⁰, comfortably larger than 2³², so 6 chars
 * are enough). Padding keeps every generated code the same length regardless of the numeric
 * magnitude of the hash.
 */
@Component
public class UrlHashGenerator {

    private static final String SALT_DELIMITER = "_salt_";
    private static final char PAD_CHAR = '0';

    private final Base62Encoder base62Encoder;
    private final int shortCodeLength;

    public UrlHashGenerator(Base62Encoder base62Encoder,
                            @Value("${app.short-code.length:6}") int shortCodeLength) {
        this.base62Encoder = base62Encoder;
        this.shortCodeLength = shortCodeLength;
    }

    public String generateShortCode(String normalizedUrl, int saltAttempt) {
        if (normalizedUrl == null) {
            throw new IllegalArgumentException("normalizedUrl must not be null");
        }
        if (saltAttempt < 0) {
            throw new IllegalArgumentException("saltAttempt must be non-negative, got " + saltAttempt);
        }

        String input = saltAttempt == 0
                ? normalizedUrl
                : normalizedUrl + SALT_DELIMITER + saltAttempt;

        int hash = Hashing.murmur3_32_fixed()
                .hashString(input, StandardCharsets.UTF_8)
                .asInt();
        // Reinterpret the 32-bit hash as unsigned so Base62Encoder (which rejects negatives)
        // is happy. Cast is a no-op at the bit level.
        long unsigned = hash & 0xFFFFFFFFL;

        String encoded = base62Encoder.encode(unsigned);
        if (encoded.length() >= shortCodeLength) {
            return encoded;
        }
        StringBuilder padded = new StringBuilder(shortCodeLength);
        int missing = shortCodeLength - encoded.length();
        for (int i = 0; i < missing; i++) {
            padded.append(PAD_CHAR);
        }
        padded.append(encoded);
        return padded.toString();
    }
}
