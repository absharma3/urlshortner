package com.urlshortner.exception;

/**
 * Thrown from the shorten path when a caller supplies a {@code customAlias} that is already
 * registered against a <b>different</b> URL. (When the alias is registered against the same
 * URL the request is idempotent and returns the existing row.)
 *
 * <p>Extends {@link IllegalStateException} so it flows through {@code GlobalExceptionHandler}'s
 * catch-all conflict handler and maps to HTTP 409. Not thrown for hash-collision retries on the
 * salt loop — that path never surfaces to the caller.
 */
public class ShortCodeConflictException extends IllegalStateException {

    private final String shortCode;

    public ShortCodeConflictException(String shortCode) {
        super("Short code '" + shortCode + "' is already in use.");
        this.shortCode = shortCode;
    }

    public String getShortCode() {
        return shortCode;
    }
}
