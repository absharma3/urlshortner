package com.urlshortner.util;

import java.util.Objects;

import org.springframework.stereotype.Component;

/**
 * Base62 codec used to render short codes. The alphabet is
 * {@code 0-9A-Za-z} — 62 case-sensitive characters — so a numeric value in {@code [0, 62ⁿ)} maps
 * to exactly {@code n} characters when zero-padded.
 *
 * <p>Stateless and thread-safe: no instance state, all constants are {@code static final}.
 * Callers should reuse the single Spring-managed bean rather than instantiate.
 *
 * <p>{@link #encode} accepts any non-negative {@code long} (up to {@code Long.MAX_VALUE}, which
 * encodes to 11 chars); {@link #decode} rejects any character outside the alphabet.
 */
@Component
public final class Base62Encoder {

    private static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BASE = ALPHABET.length();
    private static final int[] DECODE_TABLE = buildDecodeTable();

    private static int[] buildDecodeTable() {
        int[] table = new int[128];
        for (int i = 0; i < table.length; i++) {
            table[i] = -1;
        }
        for (int i = 0; i < ALPHABET.length(); i++) {
            table[ALPHABET.charAt(i)] = i;
        }
        return table;
    }

    public String encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative, got " + value);
        }
        if (value == 0) {
            return String.valueOf(ALPHABET.charAt(0));
        }
        char[] buffer = new char[11];
        int position = buffer.length;
        while (value > 0) {
            buffer[--position] = ALPHABET.charAt((int) (value % BASE));
            value /= BASE;
        }
        return new String(buffer, position, buffer.length - position);
    }

    public long decode(String code) {
        Objects.requireNonNull(code, "code must not be null");
        if (code.isEmpty()) {
            throw new IllegalArgumentException("code must not be empty");
        }
        long result = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            int digit = c < DECODE_TABLE.length ? DECODE_TABLE[c] : -1;
            if (digit < 0) {
                throw new IllegalArgumentException("invalid Base62 character: '" + c + "'");
            }
            long next = result * BASE + digit;
            if (next < result) {
                throw new IllegalArgumentException("Base62 overflow decoding '" + code + "'");
            }
            result = next;
        }
        return result;
    }
}
