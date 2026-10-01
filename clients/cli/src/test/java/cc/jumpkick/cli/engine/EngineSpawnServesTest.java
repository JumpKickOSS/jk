// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Which engine a client accepts as its own — the rule the ensure probe and the post-spawn wait
 * share, so a takeover never returns the displaced engine's handshake as "up".
 */
class EngineSpawnServesTest {

    private static final String POINTER = "ab12cd34ef56" + "0".repeat(52);

    private static EngineProbe.Handshake engine(String version, String buildId) {
        return new EngineProbe.Handshake(version, 4242L, 1L, false, buildId);
    }

    @Test
    void the_engine_running_the_pointed_jar_serves_the_client() {
        assertThat(EngineSpawn.serves(engine("0.13.3", "ab12cd34ef56"), "0.13.3", Optional.of(POINTER)))
                .isTrue();
    }

    @Test
    void the_displaced_engine_on_the_same_version_does_not_serve() {
        assertThat(EngineSpawn.serves(engine("0.13.3", "ffffffffffff"), "0.13.3", Optional.of(POINTER)))
                .isFalse();
    }

    @Test
    void another_version_never_serves_whatever_its_jar() {
        assertThat(EngineSpawn.serves(engine("0.13.2", "ab12cd34ef56"), "0.13.3", Optional.of(POINTER)))
                .isFalse();
        assertThat(EngineSpawn.serves(engine("0.13.2", ""), "0.13.3", Optional.empty()))
                .isFalse();
    }

    /**
     * A draining engine answers its socket for status and cancel but refuses new jobs, so the
     * client takes over rather than use it — even when it runs exactly the pointed jar.
     */
    @Test
    void a_draining_engine_never_serves_so_the_client_takes_over() {
        EngineProbe.Handshake draining = new EngineProbe.Handshake("0.13.3", 4242L, 1L, true, "ab12cd34ef56");
        assertThat(EngineSpawn.serves(draining, "0.13.3", Optional.of(POINTER))).isFalse();
        assertThat(EngineSpawn.serves(draining, "0.13.3", Optional.empty())).isFalse();
    }

    @Test
    void a_side_without_an_identity_leaves_the_version_rule_alone() {
        // An engine run from a classes directory has no jar to identify itself by.
        assertThat(EngineSpawn.serves(engine("0.13.3", ""), "0.13.3", Optional.of(POINTER)))
                .isTrue();
        // A home with no pointer names no jar to compare against.
        assertThat(EngineSpawn.serves(engine("0.13.3", "ab12cd34ef56"), "0.13.3", Optional.empty()))
                .isTrue();
    }
}
