// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.util.KeychainSandbox;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Per-worker env isolation: with W&gt;1 each worker JVM gets its own temp root and —
 * for nested-engine suites — its own {@code JK_STATE_DIR}, so engine identity (keyed on state +
 * store) differs per worker and one worker's engine stop cannot abort a sibling's request. A suite
 * that asks for worker homes also gets a {@code JK_HOME} per worker.
 */
class TestWorkerEnvTest {

    /** The silence a worker is allowed grows with the host's load; a window set by hand is taken as written. */
    @Test
    void the_idle_window_stretches_with_the_load_per_processor_unless_set_by_hand() {
        assertThat(TestWorkerEnv.idleTimeoutMs(null, 0.5, 8)).isEqualTo(TestWorkerEnv.DEFAULT_IDLE_MS);
        assertThat(TestWorkerEnv.idleTimeoutMs(null, 146, 24)).isEqualTo(TestWorkerEnv.DEFAULT_IDLE_MS * 7);
        assertThat(TestWorkerEnv.idleTimeoutMs("30000", 146, 24)).isEqualTo(30_000L);
        assertThat(TestWorkerEnv.idleTimeoutMs("0", 146, 24))
                .as("0 disables the watchdog")
                .isZero();
        assertThat(TestWorkerEnv.idleTimeoutMs("x", 146, 24)).isEqualTo(TestWorkerEnv.DEFAULT_IDLE_MS * 7);
    }

    @Test
    void each_worker_gets_its_own_state_dir_under_the_run_s_own() {
        WorkerEnv base =
                WorkerEnv.strict().with(Map.of("JK_STATE_DIR", "/tmp/jk-cli-abc", "JK_HOME", "/x/test-jk-home"));

        Map<String, String> w0 =
                TestWorkerEnv.forWorker(base, 0, Path.of("/tmp/t0"), false).extras();
        Map<String, String> w1 =
                TestWorkerEnv.forWorker(base, 1, Path.of("/tmp/t1"), false).extras();

        assertThat(w0.get("JK_STATE_DIR"))
                .isEqualTo(Path.of("/tmp/jk-cli-abc/w0").toString());
        assertThat(w1.get("JK_STATE_DIR"))
                .isEqualTo(Path.of("/tmp/jk-cli-abc/w1").toString());
        assertThat(Path.of(w0.get("JK_STATE_DIR")))
                .as("deleting the run's state dir must reach every worker's")
                .startsWithRaw(Path.of(base.extras().get("JK_STATE_DIR")));
        // Without worker homes the home stays shared; only engine identity splits.
        assertThat(w0.get("JK_HOME")).isEqualTo("/x/test-jk-home");
        // TMPDIR is a real OS path — compare via Path so Windows separators match.
        assertThat(w0.get("TMPDIR")).isEqualTo(Path.of("/tmp/t0").toString());
        assertThat(w1.get("TMPDIR")).isEqualTo(Path.of("/tmp/t1").toString());
    }

    @Test
    void suites_without_a_state_dir_are_untouched() {
        WorkerEnv env =
                TestWorkerEnv.forWorker(WorkerEnv.strict().with(Map.of("FOO", "bar")), 3, Path.of("/tmp/t3"), true);
        assertThat(env.extras()).doesNotContainKey("JK_STATE_DIR").doesNotContainKey("JK_HOME");
        assertThat(env.extras().get("FOO")).isEqualTo("bar");
        assertThat(env.inherit())
                .as("the module's policy rides along unchanged")
                .isFalse();
    }

    @Test
    void worker_homes_give_each_worker_its_own_home_and_keep_the_store_shared() {
        Path home = Path.of("/x/test-homes/cli");
        WorkerEnv base = WorkerEnv.strict()
                .with(Map.of(
                        "JK_HOME",
                        home.toString(),
                        "JK_STATE_DIR",
                        home.resolve("engine-state/r1").toString(),
                        "JK_STORE_DIR",
                        home.resolve("store").toString(),
                        "JK_CACHE_DIR",
                        home.resolve("cache").toString(),
                        "GIT_CONFIG_GLOBAL",
                        home.resolve("gitconfig").toString(),
                        KeychainSandbox.ENV,
                        home.resolve("keychain").toString()));

        Map<String, String> w0 =
                TestWorkerEnv.forWorker(base, 0, Path.of("/tmp/t0"), true).extras();
        Map<String, String> w1 =
                TestWorkerEnv.forWorker(base, 1, Path.of("/tmp/t1"), true).extras();

        assertThat(w0.get("JK_HOME")).isEqualTo(home.resolve("w0").toString());
        assertThat(w1.get("JK_HOME")).isEqualTo(home.resolve("w1").toString());
        assertThat(w0.get("GIT_CONFIG_GLOBAL"))
                .isEqualTo(home.resolve("w0/gitconfig").toString());
        assertThat(w1.get(KeychainSandbox.ENV))
                .isEqualTo(home.resolve("w1/keychain").toString());
        assertThat(w0.get("JK_STATE_DIR"))
                .isEqualTo(home.resolve("engine-state/r1/w0").toString());
        for (Map<String, String> w : List.of(w0, w1)) {
            assertThat(w.get("JK_STORE_DIR")).isEqualTo(home.resolve("store").toString());
            assertThat(w.get("JK_CACHE_DIR")).isEqualTo(home.resolve("cache").toString());
        }
        // An engine stop claims what names a path inside its home or state dir: a sibling's must not.
        assertThat(Path.of(w1.get("JK_HOME")).startsWith(Path.of(w0.get("JK_HOME"))))
                .isFalse();
        assertThat(Path.of(w0.get("JK_STORE_DIR")).startsWith(Path.of(w0.get("JK_HOME"))))
                .as("the shared store lies outside every worker's home")
                .isFalse();
    }

    @Test
    void a_value_outside_the_home_is_not_moved() {
        WorkerEnv base =
                WorkerEnv.strict().with(Map.of("JK_HOME", "/x/home", "GIT_CONFIG_GLOBAL", "/elsewhere/gitconfig"));

        Map<String, String> w0 =
                TestWorkerEnv.forWorker(base, 0, Path.of("/tmp/t0"), true).extras();

        assertThat(w0.get("GIT_CONFIG_GLOBAL")).isEqualTo("/elsewhere/gitconfig");
    }
}
