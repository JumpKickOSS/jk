// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.resolver.Versions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The lock's {@code jk-min} floor: newer jk always wins; a jk older than the floor refuses with
 * an upgrade error on every surface. Nothing ever runs an older engine to satisfy a lock — the
 * lockfile pins inputs (artifacts, checksums), never the operator.
 */
public final class LockFloor {

    private LockFloor() {}

    /**
     * Artifact-producing request types guarded by the floor. Reads (tree, why, history …) and
     * lock rewrites stay served — a re-lock by a current jk is how a checkout moves forward.
     */
    public static final Set<String> GUARDED = Set.of(
            EngineProtocol.BUILD_REQUEST,
            EngineProtocol.TEST_REQUEST,
            EngineProtocol.SINGLE_BUILD_REQUEST,
            EngineProtocol.COMPILE_REQUEST,
            EngineProtocol.NATIVE_REQUEST,
            EngineProtocol.TRAIN_REQUEST,
            EngineProtocol.IMAGE_REQUEST,
            EngineProtocol.INSTALL_REQUEST,
            EngineProtocol.PUBLISH_REQUEST);

    /**
     * The floor when it exceeds {@code running}, else {@code null} (no lock, no floor,
     * unreadable lock — the request path surfaces real errors itself).
     */
    public static @Nullable String requiredNewer(Path entryDir, String running) {
        try {
            Path lock = cc.jumpkick.lock.LockPaths.lockFile(entryDir);
            if (!Files.isRegularFile(lock)) return null;
            String floor = LockfileReader.read(lock).jkMin();
            if (floor == null || floor.isBlank()) return null;
            return Versions.compare(floor, running) > 0 ? floor : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The upgrade error, phrased the same on every surface. */
    public static String message(String floor, String running) {
        return "this lock requires jk " + floor + " or newer but this engine is " + running
                + " — upgrade with `jk self update` (or reinstall); jk never runs an older engine to satisfy a lock";
    }

    /** Throw the typed refusal when {@code wireType} is guarded and the floor exceeds {@code running}. */
    public static void refuseIfBelow(Path entryDir, String wireType, String running) {
        if (!GUARDED.contains(wireType)) return;
        String floor = requiredNewer(entryDir, running);
        if (floor != null) throw new LockFloorRefused(floor, running);
    }

    /** An artifact job submission from a jk below the lock's {@code jk-min} floor. */
    public static final class LockFloorRefused extends IllegalStateException {
        private final String requiredVersion;
        private final String runningVersion;

        LockFloorRefused(String requiredVersion, String runningVersion) {
            super(message(requiredVersion, runningVersion));
            this.requiredVersion = requiredVersion;
            this.runningVersion = runningVersion;
        }

        public String requiredVersion() {
            return requiredVersion;
        }

        public String runningVersion() {
            return runningVersion;
        }
    }
}
