// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import cc.jumpkick.host.Hashing;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Running jk identity for cache keys and engine election. Releases use {@link JkVersion#VERSION};
 * {@code -SNAPSHOT} builds use jar sha256 (12 hex). Empty {@link #buildId()} means no jar identity.
 */
public final class BuildIdentity {

    private static volatile String cachedBuildId;

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
     * The version string to key caches by: bare {@link JkVersion#VERSION} for releases; for
     * {@code -SNAPSHOT} builds the build id is folded in so a rebuilt engine never restores an
     * older engine's results under the same key.
     */
    public static String cacheKeyVersion() {
        return compose(JkVersion.VERSION, buildId());
    }

    /** Pure composition rule, separated for tests. */
    static String compose(String version, String id) {
        return version.endsWith("-SNAPSHOT") && !id.isEmpty() ? version + "+" + id : version;
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
