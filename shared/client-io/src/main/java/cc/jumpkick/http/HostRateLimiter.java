// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
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

    /**
     * Google's GCS-hosted Central mirror tolerates far more concurrency than Sonatype does — it is object
     * storage, not a metered service with a per-IP quota. Capping it at Central's 6 would leave most of the
     * benefit of routing there unused.
     */
    public static final int MIRROR_PERMITS = 20;

    /** Hosts whose cap differs from {@link #DEFAULT_PERMITS}. */
    private static final Map<String, Integer> PERMIT_OVERRIDES =
            Map.of(URI.create(CentralMirror.MIRROR_BASE).getHost(), MIRROR_PERMITS);

    private static final HostRateLimiter SHARED = new HostRateLimiter(DEFAULT_PERMITS);

    /** Process-wide shared limiter at the default capacity. */
    public static HostRateLimiter shared() {
        return SHARED;
    }

    private final int permitsPerHost;
    private final ConcurrentMap<String, Semaphore> semaphores = new ConcurrentHashMap<>();

    /** The cap for {@code host}: an override when one applies, else this limiter's default. */
    int permitsFor(String host) {
        // The override lifts the DEFAULT limiter for a host known to tolerate more. A limiter constructed
        // at any other capacity was an explicit choice by its caller and is left exactly as asked — being
        // "helpfully" widened to 20 is the last thing a caller that said 2 wants.
        if (host == null || permitsPerHost != DEFAULT_PERMITS) return permitsPerHost;
        Integer override = PERMIT_OVERRIDES.get(host.toLowerCase(Locale.ROOT));
        return override != null ? override : permitsPerHost;
    }

    public HostRateLimiter(int permitsPerHost) {
        if (permitsPerHost < 1) throw new IllegalArgumentException("permitsPerHost must be >= 1");
        this.permitsPerHost = permitsPerHost;
    }

    /** Acquire a permit for {@code host}, run {@code work}, release on return or throw. */
    public <T, E extends Exception> T run(String host, ThrowingSupplier<T, E> work) throws E, InterruptedException {
        Semaphore sem = semaphores.computeIfAbsent(host, h -> new Semaphore(permitsFor(h)));
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
