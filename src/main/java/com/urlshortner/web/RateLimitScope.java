package com.urlshortner.web;

/**
 * Rate-limit scopes routed by URL shape in {@link RateLimitingInterceptor}.
 *
 * <p>Splitting the buckets lets us charge writes (which cost a DB write, a hash, and a Redis
 * op) at a much tighter rate than reads (which are usually served from the Redis cache).
 * Otherwise a well-behaved client following its own short link burns their create quota just
 * by clicking around.
 */
public enum RateLimitScope {

    CREATE("create"),
    REDIRECT("redirect"),
    STATS("stats");

    private final String label;

    RateLimitScope(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
