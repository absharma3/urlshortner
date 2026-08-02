package com.urlshortner.web;

public class RateLimitExceededException extends RuntimeException {

    private final int limit;
    private final long retryAfterSeconds;
    private final long resetEpochSeconds;

    public RateLimitExceededException(int limit, long retryAfterSeconds, long resetEpochSeconds) {
        super("Rate limit exceeded (limit=" + limit + ", retryAfter=" + retryAfterSeconds + "s).");
        this.limit = limit;
        this.retryAfterSeconds = retryAfterSeconds;
        this.resetEpochSeconds = resetEpochSeconds;
    }

    public int getLimit() {
        return limit;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public long getResetEpochSeconds() {
        return resetEpochSeconds;
    }
}
