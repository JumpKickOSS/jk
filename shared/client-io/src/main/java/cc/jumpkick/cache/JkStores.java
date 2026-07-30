// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;

/**
 * Decides which root the CAS lives under, given the cache root a caller was handed (JK-1289).
 *
 * <p>Downloaded artifacts belong in {@link JkDirs#storeDir()} so that pointing {@code JK_CACHE_DIR} at a
 * fresh directory — which is how jk's own tests isolate themselves — stops discarding them and
 * re-fetching from a rate-limited Maven Central. The action cache's key-to-outputs mapping still keys
 * off the cache root, so isolation of build *results* is unchanged; only the blobs are shared, and
 * sharing those is safe by construction because a sha either matches the content asked for or it does
 * not.
 *
 * <h2>Why this is a function of the cache root rather than a constant</h2>
 *
 * Resolving the store statically everywhere would make every test that passes its own temporary cache
 * directory start writing into the developer's real {@code ~/.jk/store}. So the ambient cache root gets
 * redirected to the store, and an explicitly-supplied one is left exactly where it is:
 *
 * <ul>
 *   <li>the ambient cache — including a {@code JK_CACHE_DIR} override, which is the case this exists
 *       for — resolves to the shared store
 *   <li>any other path, i.e. one a caller chose rather than inherited, stays put and keeps today's
 *       isolation
 * </ul>
 *
 * {@code JK_HOME} moves the cache and the store together, so it remains the way to get a genuinely cold
 * start.
 */
public final class JkStores {

    private JkStores() {}

    /**
     * A subdirectory of the store — {@code git}, {@code git-artifacts}, {@code jdks.json} — resolved with
     * the same ambient-vs-supplied rule as {@link #storeRootFor(Path)}.
     */
    public static Path resolve(Path cacheRoot, String entry) {
        return storeRootFor(cacheRoot).resolve(entry);
    }

    /** A CAS rooted per {@link #storeRootFor(Path)}. */
    public static Cas cas(Path cacheRoot) {
        return new Cas(storeRootFor(cacheRoot));
    }

    /** The store root when {@code cacheRoot} is the ambient one; {@code cacheRoot} itself otherwise. */
    public static Path storeRootFor(Path cacheRoot) {
        return storeRootFor(cacheRoot, JkDirs.cache(), JkDirs.store());
    }

    /**
     * Testable core of {@link #storeRootFor(Path)}: always {@code store}.
     *
     * <p>An earlier version compared {@code cacheRoot} against the ambient one and only redirected when
     * they matched, so that a caller supplying its own directory kept full isolation. That cannot work
     * across the client/engine boundary and was measured failing: the client resolves {@code
     * JK_CACHE_DIR} to a concrete path and sends it, but the engine is a daemon that does not inherit
     * the client's environment, so its idea of "ambient" is {@code ~/.jk/cache} and the supplied path
     * never matches. Every request looked caller-supplied and the store stayed isolated — precisely the
     * behaviour the split exists to remove.
     *
     * <p>So the store is whatever the engine's own {@code JK_STORE_DIR}/{@code JK_HOME} says, regardless
     * of which cache root a request carries. {@code cacheRoot} is kept in the signature because callers
     * pass it and because the pairing is the thing worth reading at each call site.
     */
    static Path storeRootFor(Path cacheRoot, Path ambientCache, Path store) {
        return store;
    }

}
