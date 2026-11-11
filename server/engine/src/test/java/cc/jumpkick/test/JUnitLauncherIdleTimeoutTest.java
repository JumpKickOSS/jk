// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@code [test] idle-timeout}: how long a test JVM may print nothing before it is killed as hung. */
class JUnitLauncherIdleTimeoutTest {

    @Test
    void the_single_suite_jvm_is_killed_after_thirty_silent_minutes_by_default() {
        assertThat(TestWorkerEnv.suiteIdleTimeoutMs(new JUnitLauncher().idleTimeoutMs))
                .isEqualTo(30 * 60_000L);
    }

    @Test
    void the_manifest_setting_holds_the_suite_jvm_and_every_pool_worker() {
        long setting = new JUnitLauncher().withIdleTimeout(90_000).idleTimeoutMs;
        assertThat(TestWorkerEnv.suiteIdleTimeoutMs(setting)).isEqualTo(90_000);
        assertThat(TestWorkerEnv.poolIdleTimeoutMs(setting)).isEqualTo(90_000);
    }

    @Test
    void unset_the_pool_keeps_its_own_window() {
        assertThat(TestWorkerEnv.poolIdleTimeoutMs(0)).isEqualTo(TestWorkerEnv.idleTimeoutMs());
    }
}
