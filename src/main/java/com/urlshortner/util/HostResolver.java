package com.urlshortner.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bounded-latency DNS resolver used by {@link UrlValidator}.
 *
 * <p>{@link InetAddress#getByName(String)} is a blocking system call with no timeout — a slow or
 * hostile resolver can pin a request thread for tens of seconds. Wrapping the call in an executor
 * task with a {@link CompletableFuture#get(long, TimeUnit)} deadline caps the write-path latency
 * penalty at {@code app.security.dns-timeout-ms}.
 *
 * <p>Existing as its own component also gives tests a seam: {@link UrlValidator} depends on this
 * interface, so a fake {@code HostResolver} in tests can simulate slow resolution or unreachable
 * hosts without ever touching real DNS.
 */
@Component
public class HostResolver {

    private static final Logger log = LoggerFactory.getLogger(HostResolver.class);

    private final Duration timeout;
    private final Executor executor;

    public HostResolver(@Value("${app.security.dns-timeout-ms:1500}") long timeoutMs) {
        this.timeout = Duration.ofMillis(timeoutMs);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Resolve {@code host} to an {@link InetAddress} with a bounded latency budget.
     *
     * @return Optional of the resolved address, or {@link Optional#empty()} if resolution failed
     *         (unknown host, timeout, or interrupted). Callers decide the failure semantics —
     *         {@link UrlValidator} treats "unknown host" as fail-open and only rejects when the
     *         address itself lies in a blocked range.
     */
    public Optional<InetAddress> resolve(String host) {
        CompletableFuture<InetAddress> future = CompletableFuture.supplyAsync(() -> {
            try {
                return InetAddress.getByName(host);
            } catch (UnknownHostException ex) {
                throw new CompletionUnknownHost(ex);
            }
        }, executor);

        try {
            return Optional.ofNullable(future.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException ex) {
            future.cancel(true);
            log.warn("DNS resolution timed out for host='{}' after {}ms", host, timeout.toMillis());
            return Optional.empty();
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof CompletionUnknownHost) {
                log.debug("DNS resolution failed for host='{}': unknown host", host);
                return Optional.empty();
            }
            log.warn("DNS resolution errored for host='{}': {}", host, ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage());
            return Optional.empty();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** Sentinel used to smuggle {@link UnknownHostException} through {@link CompletableFuture}. */
    private static final class CompletionUnknownHost extends RuntimeException {
        CompletionUnknownHost(UnknownHostException cause) {
            super(cause);
        }
    }
}
