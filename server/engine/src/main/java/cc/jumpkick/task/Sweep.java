// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.host.Hashing;
import java.nio.file.Path;

/**
 * Constants + small helpers shared by {@link CasSweep} and {@link ActionCachePrune}.
 *
 * <p>Lives in {@code engine.task} alongside {@link ActionCache} and {@link SyncManifest} because
 * they're the writers of the roots the sweep walks. Both reclaimers honour the same write grace
 * window, so a blob is never unlinked out from under a concurrent store or restore. The names of
 * those roots are {@code cc.jumpkick.host.ActionTree}'s, not this class's.
 */
public final class Sweep {

    private Sweep() {}

    /**
     * Stable per-project identifier derived from the lockfile's absolute path. Moving the project on
     * disk (or pointing at a different lockfile) yields a different fingerprint — the old manifest
     * ages out via the regular TTL, the new one starts fresh.
     */
    public static String projectFingerprint(Path lockFile) {
        String key = lockFile.toAbsolutePath().normalize().toString();
        return Hashing.sha256Hex(key);
    }
}
