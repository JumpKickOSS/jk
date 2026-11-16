// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.host.Log;
import java.nio.file.Path;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Where a request gets its remote cache tier: one {@link RemoteTier}, so one circuit breaker, per
 * request, bound around the request's whole body ({@link RemoteCaches#where}) so its forecast, its
 * steps and {@code jk explain} ask the same tier.
 *
 * <p>The only store jk can bind today is a directory standing in for a shared cache ({@link
 * DirectoryCacheBackend}), named by the experimental {@value #DIR} setting of the shell that ran
 * the command; {@value #WRITE}{@code =true} also uploads what the request stores. Both are for
 * exercising the remote tier between two homes and are not a supported feature: the names and the
 * behaviour may change or go. With neither set, nothing is bound and the request is the local
 * cache alone.
 */
public final class RemoteCacheBinding {

    /** The directory a request reads cache records from, as if it were a shared store. Experimental. */
    public static final String DIR = "JK_EXPERIMENTAL_REMOTE_CACHE_DIR";

    /** {@code true} to also upload what the request stores to {@link #DIR}. Experimental. */
    public static final String WRITE = "JK_EXPERIMENTAL_REMOTE_CACHE_WRITE";

    /** How long a writing request waits, at its end, for its uploads to land. */
    static final Duration DRAIN = Duration.ofMinutes(2);

    private RemoteCacheBinding() {}

    /** The tier the current request's settings name, or null when they name none. */
    static @Nullable RemoteTier forRequest() {
        String dir = EnvValues.string(BuildEnv::get, DIR).orElse(null);
        if (dir == null) return null;
        boolean writes = EnvValues.bool(BuildEnv::get, WRITE).orElse(false);
        return new RemoteTier(new DirectoryCacheBackend(Path.of(dir)), writes);
    }

    /**
     * Run {@code body} with the request's remote tier bound, and wait for its uploads before
     * returning; run it as it is when the request names no tier.
     */
    public static <T extends @Nullable Object, X extends Throwable> T around(ScopedValue.CallableOp<T, X> body)
            throws X {
        RemoteTier tier = forRequest();
        if (tier == null) return body.call();
        try {
            return RemoteCaches.call(tier, body);
        } finally {
            drain(tier);
        }
    }

    private static void drain(RemoteTier tier) {
        try {
            if (tier.writes() && !tier.drain(DRAIN)) {
                Log.warn("jk: remote cache " + tier.name() + " uploads still pending after " + DRAIN.toSeconds()
                        + " s; left unfinished");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
