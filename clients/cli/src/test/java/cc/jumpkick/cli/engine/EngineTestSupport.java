// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.VersionStore;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Suite fixture for wire-only CLI tests: materialize {@code jk-engine} into the test {@code JK_HOME}
 * and stop the resident engine so {@code @TempDir} can delete project trees that held open CAS
 * links (ticket-1021).
 */
public final class EngineTestSupport {

    private static final AtomicBoolean MATERIALIZED = new AtomicBoolean();

    private EngineTestSupport() {}

    /**
     * Idempotent materialize of the Gradle-provided {@code -Djk.engine.jar} into VersionStore under
     * the test {@code JK_HOME}.
     */
    public static void ensureEngineMaterialized() {
        if (MATERIALIZED.get()) return;
        synchronized (MATERIALIZED) {
            if (MATERIALIZED.get()) return;
            String jarProp = System.getProperty("jk.engine.jar");
            if (jarProp == null || jarProp.isBlank()) {
                throw new IllegalStateException(
                        "jk.engine.jar system property is not set — :cli tests must dependsOn(:engine:shadowJar)");
            }
            Path engineJar = Path.of(jarProp);
            if (!Files.isRegularFile(engineJar)) {
                throw new IllegalStateException("engine jar not found: " + engineJar);
            }
            try {
                VersionStore store = VersionStore.current();
                if (store.resolve(JkVersion.VERSION).isEmpty()) {
                    Path cacheRoot = JkDirs.cache();
                    Files.createDirectories(cacheRoot);
                    Cas cas = new Cas(cacheRoot);
                    store.materializeFromFiles(JkVersion.VERSION, cas, engineJar, null);
                }
            } catch (IOException e) {
                throw new IllegalStateException("failed to materialize engine jar into JK_HOME", e);
            }
            MATERIALIZED.set(true);
        }
    }

    /** Force-stop the engine if running (no state-dir cleanup). Safe when already stopped. */
    public static void stopEngineOnly() {
        try {
            EnginePaths.Paths paths = EnginePaths.current();
            Path socket = EnginePaths.activeSocket(paths);
            var status = EngineClient.status(socket);
            if (status.isPresent()) {
                if (!EngineClient.forceStop(socket)) {
                    EngineClient.hardKill(status.get().pid());
                }
            }
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }

    /**
     * Force-stop any engine serving this test process's {@link EnginePaths} and best-effort delete
     * the short {@code JK_STATE_DIR} (UDS parent). Safe when no engine is running.
     */
    public static void stopEngineAndRelease() {
        stopEngineOnly();
        // Release the short state dir so concurrent suites / next runs don't collide.
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
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
