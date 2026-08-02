package com.urlshortner.util;

import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.google.common.hash.Hashing;

/**
 * Deterministic short-code generator.
 *
 * <p>For a given (normalisedUrl, saltAttempt) pair, {@link #generateShortCode} always returns
 * the same fixed-length alphanumeric Base62 string. This is the property that lets the shorten
 * write path skip the DB entirely on repeat requests for the same URL:
 * {@code same URL → same shortCode → DB says "already there" → return existing}.
 *
 * <p>The hash is <b>MurmurHash3 128-bit</b>, of which we keep the low 64 bits. It is not
 * cryptographic; we do not care about collision-resistance against adversarial input because
 * the collision-handling strategy is built into {@link UrlHashGenerator}'s caller — a salt loop
 * that retries with {@code url + "_salt_" + n} until an unused code is found.
 *
 * <p><b>Output shape:</b> 128-bit hash → low 64 bits → sign-masked non-negative long → Base62 →
 * left-padded with {@code '0'} to the configured length. At the default 8 chars the output space
 * is 62⁸ ≈ 2.18×10¹⁴, well under {@code Long.MAX_VALUE}, so {@link Base62Encoder} needs no
 * {@code BigInteger} arithmetic. Padding keeps every generated code the same length regardless
 * of the numeric magnitude of the hash.
 */
@Component
public class UrlHashGenerator {

    private static final String SALT_DELIMITER = "_salt_";
    private static final char PAD_CHAR = '0';

    // The upper bound of shortCodeLength is dictated by long-arithmetic: 62^10 fits in a long
    // (~8.4×10¹⁷) but 62^11 overflows. Ten characters is more than enough for any realistic
    // workload — 62^10 is ~8.4 quadrillion codes.
    private static final int MAX_SHORT_CODE_LENGTH = 10;

    private final Base62Encoder base62Encoder;
    private final int shortCodeLength;
    private final long codeSpace;

    public UrlHashGenerator(Base62Encoder base62Encoder,
                            @Value("${app.short-code.length:8}") int shortCodeLength) {
        if (shortCodeLength < 1 || shortCodeLength > MAX_SHORT_CODE_LENGTH) {
            throw new IllegalArgumentException(
                    "shortCodeLength must be between 1 and " + MAX_SHORT_CODE_LENGTH
                            + ", got " + shortCodeLength);
        }
        this.base62Encoder = base62Encoder;
        this.shortCodeLength = shortCodeLength;
        long space = 1L;
        for (int i = 0; i < shortCodeLength; i++) {
            space *= 62L;
        }
        this.codeSpace = space;
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

        long hash = Hashing.murmur3_128()
                .hashString(input, StandardCharsets.UTF_8)
                .asLong();
        // Strip the sign bit so Base62Encoder (which rejects negatives) is happy, then reduce
        // into 62^shortCodeLength so the encoded string is guaranteed to fit. The modular bias
        // is negligible: Long.MAX_VALUE / codeSpace ≥ ~42,000 for the default 8-char length.
        long unsigned = (hash & Long.MAX_VALUE) % codeSpace;

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
