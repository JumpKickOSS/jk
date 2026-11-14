// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.EngineTransport;
import org.junit.jupiter.api.Test;

/** Which of the client's {@code jk.*} properties ride into the engine JVM. */
class EngineSpawnForwardedPropertiesTest {

    @Test
    void jar_overrides_the_cache_switch_env_overlays_and_the_owner_pid_are_forwarded() {
        assertThat(EngineSpawn.forwarded("jk.test.runner.jar")).isTrue();
        assertThat(EngineSpawn.forwarded("jk.worker.aot")).isTrue();
        assertThat(EngineSpawn.forwarded("jk.env.JK_HOME")).isTrue();
        assertThat(EngineSpawn.forwarded(EngineSpawn.OWNER_PID_PROPERTY)).isTrue();
    }

    /**
     * A client whose transport is forced by property (a test pinning {@code unix} under a tier
     * that exports {@code JK_ENGINE_TRANSPORT=tcp}) spawns an engine on that same lane: without
     * the property the engine read the environment, bound loopback TCP and wrote its port where
     * the client expected a socket, and every connect was refused until the start ceiling.
     */
    @Test
    void the_transport_override_rides_into_the_engine_it_spawns() {
        String before = System.getProperty(EngineTransport.TRANSPORT_PROPERTY);
        System.setProperty(EngineTransport.TRANSPORT_PROPERTY, "unix");
        try {
            assertThat(EngineSpawn.forwardedJvmArgs()).contains("-D" + EngineTransport.TRANSPORT_PROPERTY + "=unix");
        } finally {
            if (before == null) System.clearProperty(EngineTransport.TRANSPORT_PROPERTY);
            else System.setProperty(EngineTransport.TRANSPORT_PROPERTY, before);
        }
    }

    @Test
    void host_signals_and_foreign_properties_stay_behind() {
        assertThat(EngineSpawn.forwarded("jk.plugin.class")).isFalse();
        assertThat(EngineSpawn.forwarded("jk.engine.jar.something")).isFalse();
        assertThat(EngineSpawn.forwarded("java.home")).isFalse();
        assertThat(EngineSpawn.forwarded("user.dir")).isFalse();
    }
}
