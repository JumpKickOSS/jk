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
 * ~/.local/share/jk/store} by default). Holds network-fetched deps, {@code repos/&lt;name&gt;/}
 * Maven-layout views, installLocal publishes, worker jars, {@link JkDirs#libraryRegistry()},
 * {@link JkDirs#templates()}, and other cross-project immutable bytes. Pointing {@code
 * JK_CACHE_DIR} at a fresh directory must <em>not</em> move this root (or
 * tests and cold CI would re-hit Maven Central).
 *
 * <h2>Cache CAS (ephemeral)</h2>
 *
 * {@link #cacheCas(Path)} — under the session cache root ({@code ~/.cache/jk} by default). Holds
 * action-cache output blobs (compile/package trees). Deleting the cache dir drops index
 * <em>and</em> payloads; the artifact store is untouched.
 *
 * <p>The {@code cacheRoot} argument on store helpers is retained only so call sites that already
 * pass a session cache keep a readable pairing; it is ignored when resolving the store.
 */
public final class JkStores {

    private JkStores() {}

    // ---- artifact store ----------------------------------------------------

    /** Artifact store root ({@link JkDirs#store()}). */
    public static Path store() {
        return JkDirs.store();
    }

    /**
     * Artifact store root. {@code cacheRoot} is ignored (see class javadoc); kept so call sites
     * stay self-documenting.
     */
    public static Path storeRootFor(Path cacheRoot) {
        return storeRootFor(cacheRoot, JkDirs.cache(), JkDirs.store());
    }

    /**
     * Testable core of {@link #storeRootFor(Path)}: always {@code store}.
     *
     * <p>An earlier version compared {@code cacheRoot} against the ambient one and only redirected
     * when they matched. That cannot work across the client/engine boundary: the client resolves
     * {@code JK_CACHE_DIR} to a concrete path and sends it, but the engine daemon does not inherit
     * the client's environment.
     */
    static Path storeRootFor(Path cacheRoot, Path ambientCache, Path store) {
        return store;
    }

    /**
     * A subdirectory of the artifact store — {@code repos}, {@code git}, {@code tools}, … —
     * resolved with the same rule as {@link #storeRootFor(Path)}.
     */
    public static Path resolve(Path cacheRoot, String entry) {
        return storeRootFor(cacheRoot).resolve(entry);
    }

    /** Artifact CAS rooted at {@link #store()}. */
    public static Cas storeCas() {
        return new Cas(store());
    }

    /**
     * Artifact CAS. {@code cacheRoot} is ignored; prefer {@link #storeCas()} for new code. Kept as
     * the historical {@link #cas(Path)} name so call sites that resolve deps / workers stay
     * obvious without a mass rename.
     */
    public static Cas cas(Path cacheRoot) {
        return storeCas();
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
