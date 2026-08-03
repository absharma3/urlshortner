package com.urlshortner.exception;

/**
 * Thrown by {@code RateLimitingInterceptor} when the caller has exhausted its per-scope quota
 * (create / redirect / stats) for the current window.
 *
 * <p>Mapped to HTTP 429 by {@code GlobalExceptionHandler}, which also emits the {@code Retry-After}
 * response header from {@link #retryAfterSeconds}. Units are load-bearing:
 * <ul>
 *   <li>{@link #limit} — the per-window cap that was exceeded (requests per window).</li>
 *   <li>{@link #retryAfterSeconds} — seconds until the window rolls over, i.e. how long the
 *       caller should back off. Suitable for the {@code Retry-After} header per RFC 9110.</li>
 *   <li>{@link #resetEpochSeconds} — Unix epoch seconds at which the window rolls over.
 *       Exposed as a problem-detail property so clients can align their own retry timers.</li>
 * </ul>
 */
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
