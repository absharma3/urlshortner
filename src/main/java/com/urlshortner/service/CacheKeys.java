package com.urlshortner.service;

/**
 * Centralised Redis key namespaces. Prefixes are private — external callers construct keys via
 * the helper methods so no code outside this class hardcodes {@code "url:"} or {@code "clicks:"}.
 * The two public constants are keys that stand alone (no parameters), so exposing them is safe.
 */
public final class CacheKeys {

    private static final String URL_PREFIX = "url:";
    private static final String CLICKS_PREFIX = "clicks:";
    private static final String RATELIMIT_PREFIX = "ratelimit:";
    private static final String STAMPEDE_LOCK_PREFIX = "lock:url:";

    public static final String CLICKS_ACTIVE_SET = "clicks:active";

    private CacheKeys() {
    }

    public static String redirect(String shortCode) {
        return URL_PREFIX + shortCode;
    }

    public static String clicks(String shortCode) {
        return CLICKS_PREFIX + shortCode;
    }

    public static String rateLimit(String clientId, long bucket) {
        return RATELIMIT_PREFIX + clientId + ":" + bucket;
    }

    public static String stampedeLock(String shortCode) {
        return STAMPEDE_LOCK_PREFIX + shortCode;
    }
}
