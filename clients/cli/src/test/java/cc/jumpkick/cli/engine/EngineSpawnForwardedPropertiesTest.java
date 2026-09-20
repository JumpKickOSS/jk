// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Which of the client's {@code jk.*} properties ride into the engine JVM. */
class EngineSpawnForwardedPropertiesTest {

    @Test
    void jar_overrides_env_overlays_and_the_owner_pid_are_forwarded() {
        assertThat(EngineSpawn.forwarded("jk.test.runner.jar")).isTrue();
        assertThat(EngineSpawn.forwarded("jk.env.JK_HOME")).isTrue();
        assertThat(EngineSpawn.forwarded(EngineSpawn.OWNER_PID_PROPERTY)).isTrue();
    }

    @Test
    void host_signals_and_foreign_properties_stay_behind() {
        assertThat(EngineSpawn.forwarded("jk.plugin.class")).isFalse();
        assertThat(EngineSpawn.forwarded("jk.engine.jar.something")).isFalse();
        assertThat(EngineSpawn.forwarded("java.home")).isFalse();
        assertThat(EngineSpawn.forwarded("user.dir")).isFalse();
    }
}
