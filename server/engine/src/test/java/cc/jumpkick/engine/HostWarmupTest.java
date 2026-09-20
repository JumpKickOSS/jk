// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.runtime.Calibration;
import cc.jumpkick.testing.RepoRoot;
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
        Path cfg = dir.resolve("config.toml");
        Files.writeString(cfg, "# empty\n");
        assertThat(HostWarmup.enabled(cfg, k -> null)).isTrue();
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

    /**
     * Both answers have to be reachable, and an assertion of {@code isIn(true, false)} says
     * nothing: it admits every boolean, so a predicate pinned at {@code true} passes it. A pinned
     * {@code true} here means the engine finds warmup work at every idle boundary forever instead
     * of settling once a measured probe is on disk.
     */
    @Test
    void needsCalibration_settles_once_a_measured_probe_is_on_disk(@TempDir Path home) throws Exception {
        withJkHome(home, () -> {
            Calibration.invalidateMemo();
            assertThat(HostWarmup.needsCalibration()).as("nothing on disk yet").isTrue();

            // Written as text rather than through HostMetricsFile (package-private, and a
            // round-trip through the writer would only prove the writer agrees with itself).
            Path file = JkDirs.builds().resolve("host-metrics.toml");
            Files.createDirectories(file.getParent());
            Files.writeString(file, """
                    [calibration]
                    schema        = 1
                    ms-per-weight = 120.0
                    measured      = true
                    jk-version    = "%s"
                    updated       = %d
                    """.formatted(JkVersion.VERSION, System.currentTimeMillis()));
            Calibration.invalidateMemo();

            assertThat(HostWarmup.needsCalibration())
                    .as("a measured current-version probe is the end of it")
                    .isFalse();
        });
        Calibration.invalidateMemo();
    }

    /**
     * The off-switch covers the whole pass. Driven through the composition seam rather than the
     * live pass: the real {@code runIdle} would need a network to prove the negative, and a step
     * that no-ops offline would not show whether the switch actually skipped the step.
     */
    @Test
    void the_off_switch_stops_every_step() {
        List<String> ran = new ArrayList<>();
        List<Runnable> steps = steps(ran, "calibration", "trim");

        HostWarmup.runIdle(false, steps);
        assertThat(ran).as("warmup disabled: nothing runs").isEmpty();

        HostWarmup.runIdle(true, steps);
        assertThat(ran).containsExactly("calibration", "trim");
    }

    /** Feed and template refresh belong to the maintenance cycle; the live pass never runs them. */
    @Test
    void the_live_pass_is_calibration_only() throws Exception {
        String source = Files.readString(
                RepoRoot.file(HostWarmupTest.class, "server/engine/src/main/java/cc/jumpkick/engine/HostWarmup.java"));
        assertThat(source).doesNotContain("StoreFeedRefresh").doesNotContain("OfficialTemplatesFreshen");
    }

    /** One step's failure is that step's business; the rest of the pass still runs. */
    @Test
    void a_failing_step_does_not_cost_the_rest_of_the_pass() {
        List<String> ran = new ArrayList<>();
        HostWarmup.runIdle(
                true,
                List.of(
                        () -> {
                            ran.add("probe");
                            throw new IllegalStateException("no javac on this host");
                        },
                        () -> ran.add("calibration")));
        assertThat(ran).containsExactly("probe", "calibration");
    }

    /**
     * Every root an idle warmup writes under is outside {@code JK_CACHE_DIR}, so {@code jk cache
     * nuke} — which removes that root and nothing else — is not undone by the 12 h cycle. Filed as
     * "HostWarmup re-downloads worker jars into the cache CAS"; the misreading is
     * {@code PluginJar.locate()}'s {@code JkStores.storeCas()}, whose argument is ignored
     * and which resolves the <em>store</em> CAS. Asserting the two roots the pass actually uses
     * is what keeps that from quietly becoming true.
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
            assertThat(JkStores.storeCas().root()).isEqualTo(store);
            // 2. host calibration (state/builds/host-metrics.toml).
            assertThat(JkDirs.builds()).startsWithRaw(state);

            for (Path written : List.of(JkStores.storeCas().root(), JkDirs.builds())) {
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
