package com.urlshortner.exception;

/**
 * Thrown when a lookup for a short code finds no matching row — either the code was never
 * shortened, or it existed and has since been expired-swept out of the {@code urls} table.
 *
 * <p>Mapped to HTTP 404 by {@code GlobalExceptionHandler}. Carries the requested short code
 * so the RFC 7807 problem detail can echo it back to the caller for diagnostics.
 */
public class NotFoundException extends RuntimeException {

    private final String shortCode;

    public NotFoundException(String shortCode) {
        super("No active short URL exists for '" + shortCode + "'.");
        this.shortCode = shortCode;
    }

    public String getShortCode() {
        return shortCode;
    }
}
