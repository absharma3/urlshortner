package com.urlshortner.service;

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
