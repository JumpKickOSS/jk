// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;

/**
 * Two-tier content-addressed storage.
 *
 * <h2>Artifact store (long-lived)</h2>
 *
 * {@link #store()} / {@link #storeCas()} — under {@link JkDirs#store()} ({@code
 * ~/.jk/store} by default). Holds network-fetched deps, {@code repos/&lt;name&gt;/}
 * Maven-layout views, jk install publishes, worker jars, {@link JkDirs#libraryRegistry()},
 * {@link JkDirs#templates()}, and other cross-project immutable bytes. Pointing {@code
 * JK_CACHE_DIR} at a fresh directory must <em>not</em> move this root (or
 * tests and cold CI would re-hit Maven Central).
 *
 * <h2>Cache CAS (ephemeral)</h2>
 *
 * {@link #cacheCas(Path)} — under the session cache root ({@code ~/.jk/cache} by default). Holds
 * action-cache output blobs (compile/package trees). Deleting the cache dir drops index
 * <em>and</em> payloads; the artifact store is untouched.
 */
public final class JkStores {

    private JkStores() {}

    // ---- artifact store ----------------------------------------------------

    /** Artifact store root ({@link JkDirs#store()}). */
    public static Path store() {
        return JkDirs.store();
    }

    /**
     * A subdirectory of the artifact store — {@code repos}, {@code git}, {@code tools}, ….
     *
     * <p>Never a function of the session cache: an earlier version compared a caller's cache root
     * against the ambient one, which cannot work across the client/engine boundary — the client
     * resolves {@code JK_CACHE_DIR} to a concrete path and sends it, but the engine daemon does
     * not inherit the client's environment.
     */
    public static Path resolve(String entry) {
        return store().resolve(entry);
    }

    /** Artifact CAS rooted at {@link #store()}. */
    public static Cas storeCas() {
        return new Cas(store());
    }

    // ---- cache CAS ---------------------------------------------------------

    /**
     * Ephemeral action-cache CAS under {@code cacheRoot} ({@code <cacheRoot>/sha256/…}). Action
     * records under {@code <cacheRoot>/actions/} point at digests in this pool.
     */
    public static Cas cacheCas(Path cacheRoot) {
        if (cacheRoot == null) {
            throw new IllegalArgumentException("cacheRoot");
        }
        return new Cas(cacheRoot);
    }
}
