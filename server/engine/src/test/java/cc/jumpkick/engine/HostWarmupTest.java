// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.engine.plugin.PluginAot;
import cc.jumpkick.host.AotCacheFiles;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostWarmupTest {

    @Test
    void enabled_defaults_true_without_config(@TempDir Path dir) throws Exception {
        // Inject the AOT baseline: the real one folds in AotSettings.suppressTraining(), a
        // permanent JVM-global flag any EngineServer shutdown in this worker may have set —
        // this test asserts the config/env layer only.
        Path cfg = dir.resolve("config.toml");
        Files.writeString(cfg, "# empty\n");
        assertThat(HostWarmup.enabled(cfg, k -> null, () -> true)).isTrue();
        assertThat(HostWarmup.enabled(cfg, k -> null, () -> false)).isFalse();
    }

    @Test
    void enabled_respects_config_and_env(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config.toml");
        Files.writeString(cfg, "[engine]\nauto-warmup = false\n");
        assertThat(HostWarmup.enabled(cfg, k -> null)).isFalse();
        Files.writeString(cfg, "[engine]\nauto-warmup = true\n");
        assertThat(HostWarmup.enabled(cfg, Map.of("JK_AUTO_WARMUP", "off")::get))
                .isFalse();
        assertThat(HostWarmup.enabled(cfg, Map.of("JK_AUTO_WARMUP", "on")::get)).isTrue();
    }

    @Test
    void missingKeyNeedsTrain_respects_sticky_noaot_marker(@TempDir Path dir) throws Exception {
        Path cache = dir.resolve("java-compiler-0123456789abcdef.aot");
        // Missing cache, no marker: train.
        assertThat(HostWarmup.missingKeyNeedsTrain(cache)).isTrue();
        // Prior train failed (sticky marker): do not re-queue warmup every cycle.
        Files.createFile(AotCacheFiles.marker(cache));
        assertThat(HostWarmup.missingKeyNeedsTrain(cache)).isFalse();
        // Unresolvable key: conservative, still ask for work.
        assertThat(HostWarmup.missingKeyNeedsTrain(null)).isTrue();
    }

    @Test
    void needsCalibration_is_true_when_no_host_metrics() {
        // Without isolating JK state this is environment-dependent; only assert non-throw.
        assertThat(HostWarmup.needsCalibration()).isIn(true, false);
    }

    /**
     * The off-switch covers the whole pass, not the tail of it. It used to be consulted between the
     * second and third steps, so {@code auto-warmup = false} silenced worker AOT and calibration
     * while the feed and template refresh still went to the network and still wrote to the store
     * every 12 h cycle — with the class javadoc claiming otherwise.
     *
     * <p>Driven through the composition seam rather than the live pass on purpose: the real
     * {@code runIdle} would need a network to prove the negative, and a step that no-ops offline
     * would let this pass with the fix reverted.
     */
    @Test
    void the_off_switch_stops_every_step_not_just_the_last_two() {
        List<String> ran = new ArrayList<>();
        List<Runnable> steps = steps(ran, "feeds", "templates", "aot", "calibration");

        HostWarmup.runIdle(false, false, steps);
        assertThat(ran)
                .as("warmup disabled and not forced: nothing runs, including the feeds")
                .isEmpty();

        HostWarmup.runIdle(false, true, steps);
        assertThat(ran).containsExactly("feeds", "templates", "aot", "calibration");
    }

    /** {@code jk optimize} asks for a pass explicitly, so it overrides the switch — all four steps. */
    @Test
    void an_explicit_optimize_overrides_the_off_switch() {
        List<String> ran = new ArrayList<>();
        HostWarmup.runIdle(true, false, steps(ran, "feeds", "templates", "aot", "calibration"));
        assertThat(ran).containsExactly("feeds", "templates", "aot", "calibration");
    }

    /** One step's failure is that step's business; the rest of the pass still runs. */
    @Test
    void a_failing_step_does_not_cost_the_rest_of_the_pass() {
        List<String> ran = new ArrayList<>();
        HostWarmup.runIdle(
                false,
                true,
                List.of(
                        () -> {
                            ran.add("feeds");
                            throw new IllegalStateException("offline");
                        },
                        () -> ran.add("aot")));
        assertThat(ran).containsExactly("feeds", "aot");
    }

    /**
     * Every root an idle warmup writes under is outside {@code JK_CACHE_DIR}, so {@code jk cache
     * nuke} — which removes that root and nothing else — is not undone by the 12 h cycle. Filed as
     * "HostWarmup re-downloads worker jars into the cache CAS"; the misreading is
     * {@code PluginJar.locate()}'s {@code JkStores.cas(JkDirs.cache())}, whose argument is ignored
     * and which resolves the <em>store</em> CAS. Asserting the four roots the pass actually uses is
     * what keeps that from quietly becoming true.
     */
    @Test
    void every_root_a_warmup_writes_is_outside_the_cache_root(@TempDir Path home) throws Exception {
        withJkHome(home, () -> {
            Path cache = JkDirs.cache();
            Path store = JkDirs.store();
            Path state = JkDirs.state();
            assertThat(cache)
                    .as("the layout override did not take; comparing a path against itself proves nothing")
                    .isNotEqualTo(store)
                    .isNotEqualTo(state);

            // 1. worker jars — the root PluginJar.locate() fetches into.
            assertThat(JkStores.cas(JkDirs.cache()).root()).isEqualTo(store);
            // 2. store feeds and templates.
            assertThat(JkDirs.libraryRegistry()).startsWithRaw(store);
            assertThat(JkDirs.templates()).startsWithRaw(store);
            // 3. worker AOT caches.
            assertThat(PluginAot.dir()).startsWithRaw(state);
            // 4. host calibration (state/builds/host-metrics.toml).
            assertThat(JkDirs.builds()).startsWithRaw(state);

            for (Path written : List.of(
                    JkStores.cas(JkDirs.cache()).root(),
                    JkDirs.libraryRegistry(),
                    JkDirs.templates(),
                    PluginAot.dir(),
                    JkDirs.builds())) {
                assertThat(written.startsWith(cache))
                        .as("%s is under the cache root, which would make a nuke self-healing", written)
                        .isFalse();
            }
        });
    }

    private static List<Runnable> steps(List<String> log, String... names) {
        List<Runnable> out = new ArrayList<>();
        for (String name : names) {
            out.add(() -> log.add(name));
        }
        return List.copyOf(out);
    }

    /**
     * Relocate the whole jk layout under {@code home} for the body. {@code jk.env.<NAME>} is
     * {@link JkDirs}'s documented in-process seam — env vars are fixed at JVM start, properties are
     * not — and {@code JK_HOME} moves cache, store and state together.
     */
    private static void withJkHome(Path home, ThrowingRunnable body) throws Exception {
        String key = "jk.env.JK_HOME";
        String previous = System.getProperty(key);
        System.setProperty(key, home.toAbsolutePath().toString());
        try {
            body.run();
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
