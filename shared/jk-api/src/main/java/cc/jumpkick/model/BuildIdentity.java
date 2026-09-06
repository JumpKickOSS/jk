// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import cc.jumpkick.host.Hashing;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Running jk identity for cache keys and engine election. Releases key by {@link JkVersion#VERSION}
 * plus the cache-key salt; {@code -SNAPSHOT} builds also fold in the jar sha256 (12 hex). Empty
 * {@link #buildId()} means no jar identity.
 */
public final class BuildIdentity {

    /**
     * Cache-key salt: turn this in the same commit that changes what any action key hashes. It
     * folds into {@link #cacheKeyVersion()} on the release branch too, where the jar id does not —
     * a rebuilt release otherwise shares the official release's whole cache namespace, and a
     * key-shape change would collide with records the old shape wrote. Not a second product
     * version: never printed, never parsed, never compared on its own.
     */
    static final int CACHE_KEY_SALT = 2;

    private static volatile @Nullable String cachedBuildId;

    private BuildIdentity() {}

    /** Content identity of the running code archive, or {@code ""} when none is derivable. */
    public static String buildId() {
        String local = cachedBuildId;
        if (local != null) return local;
        synchronized (BuildIdentity.class) {
            if (cachedBuildId == null) cachedBuildId = computeBuildId();
            return cachedBuildId;
        }
    }

    /**
     * The version string to key caches by: {@link JkVersion#VERSION} plus {@link #CACHE_KEY_SALT}
     * for releases; {@code -SNAPSHOT} builds also fold the build id in so a rebuilt engine never
     * restores an older engine's results under the same key.
     */
    public static String cacheKeyVersion() {
        return compose(JkVersion.VERSION, buildId());
    }

    /** Pure composition rule, separated for tests. */
    static String compose(String version, String id) {
        return compose(version, id, CACHE_KEY_SALT);
    }

    /** As {@link #compose(String, String)} with the salt explicit, so tests can turn it. */
    static String compose(String version, String id, int salt) {
        String salted = version + "#" + salt;
        return version.endsWith("-SNAPSHOT") && !id.isEmpty() ? salted + "+" + id : salted;
    }

    private static String computeBuildId() {
        try {
            var source = BuildIdentity.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return "";
            Path location = Path.of(source.getLocation().toURI());
            if (!Files.isRegularFile(location) || !location.toString().endsWith(".jar")) {
                return ""; // classes dir (tests) or a native image — no jar identity
            }
            return Hashing.sha256Hex(location).substring(0, 12);
        } catch (Exception e) {
            return ""; // identity is best-effort; the version-string rule still applies
        }
    }
}
