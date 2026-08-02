package com.urlshortner.service;

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
