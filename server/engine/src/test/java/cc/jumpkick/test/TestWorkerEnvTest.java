// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.plugin.WorkerEnv;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Per-worker env isolation: with W&gt;1 each worker JVM gets its own temp root and —
 * for nested-engine suites — its own {@code JK_STATE_DIR}, so engine identity (keyed on state +
 * store) differs per worker and one worker's engine stop cannot abort a sibling's request.
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
                TestWorkerEnv.forWorker(base, 0, Path.of("/tmp/t0")).extras();
        Map<String, String> w1 =
                TestWorkerEnv.forWorker(base, 1, Path.of("/tmp/t1")).extras();

        // Children, not siblings. `<base>-w0` sat outside the directory the run deletes, so every
        // worker's state survived cleanup and piled up under /tmp.
        assertThat(w0.get("JK_STATE_DIR"))
                .isEqualTo(Path.of("/tmp/jk-cli-abc/w0").toString());
        assertThat(w1.get("JK_STATE_DIR"))
                .isEqualTo(Path.of("/tmp/jk-cli-abc/w1").toString());
        assertThat(Path.of(w0.get("JK_STATE_DIR")))
                .as("deleting the run's state dir must reach every worker's")
                .startsWithRaw(Path.of(base.extras().get("JK_STATE_DIR")));
        // Shared store/home stays shared — only engine identity splits.
        assertThat(w0.get("JK_HOME")).isEqualTo("/x/test-jk-home");
        // TMPDIR is a real OS path — compare via Path so Windows separators match.
        assertThat(w0.get("TMPDIR")).isEqualTo(Path.of("/tmp/t0").toString());
        assertThat(w1.get("TMPDIR")).isEqualTo(Path.of("/tmp/t1").toString());
    }

    @Test
    void suites_without_a_state_dir_are_untouched() {
        WorkerEnv env = TestWorkerEnv.forWorker(WorkerEnv.strict().with(Map.of("FOO", "bar")), 3, Path.of("/tmp/t3"));
        assertThat(env.extras()).doesNotContainKey("JK_STATE_DIR");
        assertThat(env.extras().get("FOO")).isEqualTo("bar");
        assertThat(env.inherit())
                .as("the module's policy rides along unchanged")
                .isFalse();
    }
}
