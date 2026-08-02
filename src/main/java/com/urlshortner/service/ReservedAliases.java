package com.urlshortner.service;

import java.util.Locale;
import java.util.Set;

/**
 * Reserved shortCodes that cannot be claimed as a {@code customAlias}.
 *
 * <p>Spring's route specificity means {@code /healthz} → {@code HealthController} regardless of
 * what's in the DB, so a claimed alias {@code healthz} won't actually shadow the health probe —
 * but it would still occupy a row that can never be redirected, and its stats endpoint would
 * return nonsense. Cheaper to refuse it up front.
 */
public final class ReservedAliases {

    private static final Set<String> RESERVED = Set.of(
            "actuator",
            "api",
            "error",
            "healthz",
            "swagger",
            "swaggerui",
            "v3",
            "webjars"
    );

    private ReservedAliases() {
    }

    public static boolean isReserved(String alias) {
        return alias != null && RESERVED.contains(alias.toLowerCase(Locale.ROOT));
    }
}
