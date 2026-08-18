// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.VersionStore;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Suite fixture for wire-only CLI tests: materialize {@code jk-engine} into the test {@code JK_HOME}
 * and stop the resident engine so {@code @TempDir} can delete project trees that held open CAS
 * links.
 */
public final class EngineTestSupport {

    private static final AtomicBoolean MATERIALIZED = new AtomicBoolean();

    private EngineTestSupport() {}

    /**
     * Idempotent materialize of the engine assembly into VersionStore under the test {@code
     * JK_HOME}. Prefers {@code -Djk.engine.jar} (Gradle / pure-jk run-tests); falls back to a
     * workspace-relative assembly jar so a miswired fork fails with a path hint rather than a bare
     * missing-property error.
     */
    public static void ensureEngineMaterialized() {
        if (MATERIALIZED.get()) return;
        synchronized (MATERIALIZED) {
            if (MATERIALIZED.get()) return;
            Path engineJar = resolveEngineJar();
            if (engineJar == null || !Files.isRegularFile(engineJar)) {
                throw new IllegalStateException(
                        "jk.engine.jar system property is not set (and no workspace engine assembly found) — "
                                + "CLI tests need -Djk.engine.jar=… (Gradle :engine:shadowJar / pure-jk nested isolation)");
            }
            try {
                // Ensure UDS parent exists (pure-jk isolation creates it once; do not rely on a
                // previous class having left it intact).
                String state = System.getenv("JK_STATE_DIR");
                if (state != null && !state.isBlank()) {
                    Files.createDirectories(Path.of(state));
                }
                VersionStore store = VersionStore.current();
                // ALWAYS materialize — VersionStore is content-aware (same bytes return
                // immediately; same version + different bytes replaces the tree).
                // The old presence-check skipped the refresh, so a persistent test JK_HOME
                // kept serving a STALE engine across rebuilds (every
                // :cli:integrationTest run tonight resolved with last week's resolver).
                String wantSha = cc.jumpkick.util.Hashing.sha256Hex(engineJar);
                boolean bitsChanged =
                        !store.engineSha(JkVersion.VERSION).map(wantSha::equals).orElse(false);
                Path cacheRoot = JkDirs.cache();
                Files.createDirectories(cacheRoot);
                Cas cas = new Cas(cacheRoot);
                store.materializeFromFiles(JkVersion.VERSION, cas, engineJar, null);
                if (bitsChanged) {
                    // Same version string, different bits: a resident engine surviving from a
                    // previous test invocation still serves the OLD jar off its socket — the
                    // version handshake cannot catch it, so stop the fleet (scoped to this
                    // test JK_HOME) and let the next connect spawn fresh (JK-2175).
                    EngineFleet.stopAll(true);
                }
            } catch (IOException e) {
                throw new IllegalStateException("failed to materialize engine jar into JK_HOME", e);
            }
            MATERIALIZED.set(true);
        }
    }

    /**
     * {@code -Djk.engine.jar}, workspace assembly (module-local or Mill-style {@code
     * target/<rel>/}), or the installed engine under {@link VersionStore}.
     */
    static Path resolveEngineJar() {
        String jarProp = System.getProperty("jk.engine.jar");
        if (jarProp != null && !jarProp.isBlank()) {
            Path p = Path.of(jarProp);
            if (Files.isRegularFile(p)) return p;
        }
        Path cwd = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        String ver = JkVersion.VERSION;
        // Walk cwd → parent → grandparent so pure-jk (user.dir = clients/cli) and monorepo-root
        // Gradle runs both find the jar. Layouts: Mill-style target/<rel>/, module-local target/,
        // Gradle build/libs, dist/.
        Path walk = cwd;
        for (int up = 0; up < 3 && walk != null; up++, walk = walk.getParent()) {
            for (Path cand : List.of(
                    walk.resolve("target/server/engine/jk-engine-" + ver + "-all.jar"),
                    walk.resolve("target/server/engine/jk-engine-" + ver + ".jar"),
                    walk.resolve("server/engine/target/jk-engine-" + ver + "-all.jar"),
                    walk.resolve("server/engine/target/jk-engine-" + ver + ".jar"),
                    walk.resolve("server/engine/build/libs/jk-engine-" + ver + ".jar"),
                    walk.resolve("build/dist/lib/jk-engine-" + ver + ".jar"))) {
                if (Files.isRegularFile(cand)) return cand.normalize();
            }
        }
        try {
            var mat = VersionStore.current().resolve(JkVersion.VERSION);
            if (mat.isPresent() && Files.isRegularFile(mat.get().engineJar())) {
                return mat.get().engineJar().toAbsolutePath().normalize();
            }
        } catch (RuntimeException ignored) {
            // isolated JK_HOME with no versions tree
        }
        return null;
    }

    /** Force-stop the engine if running (no state-dir cleanup). Safe when already stopped. */
    public static void stopEngineOnly() {
        try {
            EnginePaths.Paths paths = EnginePaths.current();
            Path socket = EnginePaths.activeSocket(paths);
            // forceStop waits for pid death when the pid file is present.
            long pid = EngineClient.readPidForSocket(socket);
            if (pid <= 0) {
                var status = EngineClient.status(socket);
                if (status.isEmpty()) return;
                pid = status.get().pid();
            }
            if (!EngineClient.forceStop(socket)) {
                EngineClient.hardKill(pid);
                EngineClient.waitForDeathOrKill(pid, Duration.ofMillis(1_500));
            }
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }

    /**
     * Force-stop any engine serving this test process's {@link EnginePaths}. Safe when no engine is
     * running.
     *
     * <p>Does <strong>not</strong> delete {@code JK_STATE_DIR}: the suite shares one short state
     * dir for the whole JVM (Gradle and pure-jk). Deleting it after every class left later tests
     * with a missing UDS parent and {@code no build engine} / exit 70 under pure-jk. Suite-end
     * cleanup is the test task's job ({@code /tmp/jk-cli-*} is ephemeral).
     */
    public static void stopEngineAndRelease() {
        stopEngineOnly();
    }

    /**
     * Best-effort delete of an isolated {@code /tmp/jk-cli-*} state dir (suite-end / manual). Not
     * used between classes.
     */
    public static void deleteIsolatedStateDirIfPresent() {
        String state = System.getenv("JK_STATE_DIR");
        if (state != null && !state.isBlank() && state.startsWith("/tmp/jk-cli-")) {
            deleteRecursively(Path.of(state));
        }
    }

    private static void deleteRecursively(Path root) {
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // engine may still be tearing down
                }
            });
        } catch (IOException | UncheckedIOException ignored) {
            // Engine may delete socket/pid/lock while we walk — Files.walk wraps a mid-walk
            // disappearance as UncheckedIOException (not IOException). Best-effort only.
        }
    }
}
