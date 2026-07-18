// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.VersionStore;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Suite-scoped fixture: materialize the {@code jk-engine} shadow jar (from the {@code jk.engine.jar}
 * system property set by Gradle) into the test {@code JK_HOME} VersionStore so production
 * {@link EngineClient} spawn/connect works without any in-process dual path.
 */
public final class EngineTestSupport {

    private static final AtomicBoolean MATERIALIZED = new AtomicBoolean();

    private EngineTestSupport() {}

    /**
     * Idempotent. Safe to call from any test that needs a live engine; Gradle wires
     * {@code -Djk.engine.jar=…} and {@code JK_HOME} before the suite starts.
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
}
