// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;

/**
 * Per-host HTTP concurrency cap (default {@value #DEFAULT_PERMITS}). Acquire around the request;
 * release in {@code finally}.
 */
public final class HostRateLimiter {

    /** Per-host concurrent-request cap. Maven Central will throttle above ~8. */
    public static final int DEFAULT_PERMITS = 6;

    private static final HostRateLimiter SHARED = new HostRateLimiter(DEFAULT_PERMITS);

    /** Process-wide shared limiter at the default capacity. */
    public static HostRateLimiter shared() {
        return SHARED;
    }

    private final int permitsPerHost;
    private final ConcurrentMap<String, Semaphore> semaphores = new ConcurrentHashMap<>();

    public HostRateLimiter(int permitsPerHost) {
        if (permitsPerHost < 1) throw new IllegalArgumentException("permitsPerHost must be >= 1");
        this.permitsPerHost = permitsPerHost;
    }

    /** Acquire a permit for {@code host}, run {@code work}, release on return or throw. */
    public <T, E extends Exception> T run(String host, ThrowingSupplier<T, E> work) throws E, InterruptedException {
        Semaphore sem = semaphores.computeIfAbsent(host, h -> new Semaphore(permitsPerHost));
        sem.acquire();
        try {
            return work.get();
        } finally {
            sem.release();
        }
    }

    /** Convenience: extract the host from a URI before dispatching. */
    public <T, E extends Exception> T run(URI uri, ThrowingSupplier<T, E> work) throws E, InterruptedException {
        String host = uri.getHost() != null ? uri.getHost() : uri.toString();
        return run(host, work);
    }

    /** Functional interface that can throw a checked exception (HTTP clients throw IOException). */
    @FunctionalInterface
    public interface ThrowingSupplier<T, E extends Exception> {
        T get() throws E, InterruptedException;
    }
}
