// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Os;
import org.junit.jupiter.api.Test;

/**
 * Which transport a process speaks. The override has two spellings and the environment one is the
 * load-bearing half: the decision is read by the client <em>and</em> by the engine it spawns, and
 * a spawned engine inherits the environment but not the spawner's system properties. Forcing the
 * lane with {@code -D} alone moves the client and leaves the engine behind, which presents as a
 * hang rather than a failure.
 */
class EngineTransportOverrideTest {

    @Test
    void tcp_and_unix_are_honoured_whatever_the_platform() {
        assertThat(EngineTransport.resolve("tcp")).isTrue();
        assertThat(EngineTransport.resolve("unix")).isFalse();
    }

    @Test
    void spelling_is_forgiving_about_case_and_padding() {
        assertThat(EngineTransport.resolve("  TCP ")).isTrue();
        assertThat(EngineTransport.resolve("Unix")).isFalse();
    }

    @Test
    void absent_blank_and_unrecognised_all_fall_back_to_the_platform() {
        // Unrecognised is deliberately the platform default and not an error: this is read on the
        // connect path of every RPC, and a typo must not be the thing that stops a build.
        assertThat(EngineTransport.resolve(null)).isEqualTo(Os.isWindows());
        assertThat(EngineTransport.resolve("")).isEqualTo(Os.isWindows());
        assertThat(EngineTransport.resolve("pipes")).isEqualTo(Os.isWindows());
    }

    @Test
    void the_property_wins_over_the_environment() {
        String prior = System.getProperty(EngineTransport.TRANSPORT_PROPERTY);
        try {
            System.setProperty(EngineTransport.TRANSPORT_PROPERTY, "unix");
            assertThat(EngineTransport.useLoopbackTcp())
                    .as("an explicit -D pins this process even under a tier-wide env default")
                    .isFalse();
            System.setProperty(EngineTransport.TRANSPORT_PROPERTY, "tcp");
            assertThat(EngineTransport.useLoopbackTcp()).isTrue();
        } finally {
            if (prior == null) System.clearProperty(EngineTransport.TRANSPORT_PROPERTY);
            else System.setProperty(EngineTransport.TRANSPORT_PROPERTY, prior);
        }
    }
}
