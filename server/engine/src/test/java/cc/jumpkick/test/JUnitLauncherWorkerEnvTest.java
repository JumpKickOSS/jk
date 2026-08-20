// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Per-worker env isolation (JK-2183): with W&gt;1 each worker JVM gets its own temp root and —
 * for nested-engine suites — its own {@code JK_STATE_DIR}, so engine identity (keyed on state +
 * store) differs per worker and one worker's engine stop cannot abort a sibling's request.
 */
class JUnitLauncherWorkerEnvTest {

    @Test
    void each_worker_gets_its_own_state_dir_suffix() {
        Map<String, String> base = Map.of("JK_STATE_DIR", "/tmp/jk-cli-abc", "JK_HOME", "/x/test-jk-home");

        Map<String, String> w0 = JUnitLauncher.workerEnv(base, 0, Path.of("/tmp/t0"));
        Map<String, String> w1 = JUnitLauncher.workerEnv(base, 1, Path.of("/tmp/t1"));

        assertThat(w0.get("JK_STATE_DIR")).isEqualTo("/tmp/jk-cli-abc-w0");
        assertThat(w1.get("JK_STATE_DIR")).isEqualTo("/tmp/jk-cli-abc-w1");
        // Shared store/home stays shared — only engine identity splits.
        assertThat(w0.get("JK_HOME")).isEqualTo("/x/test-jk-home");
        assertThat(w0.get("TMPDIR")).isEqualTo("/tmp/t0");
        assertThat(w1.get("TMPDIR")).isEqualTo("/tmp/t1");
    }

    @Test
    void suites_without_a_state_dir_are_untouched() {
        Map<String, String> env = JUnitLauncher.workerEnv(Map.of("FOO", "bar"), 3, Path.of("/tmp/t3"));
        assertThat(env).doesNotContainKey("JK_STATE_DIR");
        assertThat(env.get("FOO")).isEqualTo("bar");
    }
}
