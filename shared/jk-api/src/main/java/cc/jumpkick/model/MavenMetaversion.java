// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Maven's two metaversions, still met in published POMs: {@code LATEST} names the newest version
 * the repository's metadata lists, snapshots included where a repository serves them; {@code
 * RELEASE} names the newest non-snapshot. Both float only within a solve; the lock pins the number.
 */
public enum MavenMetaversion {
    LATEST,
    RELEASE;

    /** The metaversion {@code version} spells, case-insensitively, or {@code null} for a plain version. */
    public static @Nullable MavenMetaversion of(@Nullable String version) {
        if (version == null) return null;
        return switch (version.trim().toUpperCase(Locale.ROOT)) {
            case "LATEST" -> LATEST;
            case "RELEASE" -> RELEASE;
            default -> null;
        };
    }
}
