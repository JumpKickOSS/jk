// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.runtime.base.LockMode;
import cc.jumpkick.wire.protocol.LockRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Which {@link LockMode} each shape of {@code lock-request} resolves to. */
class LockVerbModeTest {

    @Test
    void a_bare_lock_keeps_the_pins_already_on_disk() {
        assertThat(LockVerb.modeFor(request(false, false, false))).isEqualTo(new LockMode.Keep(false));
    }

    @Test
    void force_floats_the_pins() {
        assertThat(LockVerb.modeFor(request(true, false, false))).isEqualTo(new LockMode.Latest(false));
    }

    @Test
    void sources_rides_along_with_either_pin_policy() {
        assertThat(LockVerb.modeFor(request(false, false, true))).isEqualTo(new LockMode.Keep(true));
        assertThat(LockVerb.modeFor(request(true, false, true))).isEqualTo(new LockMode.Latest(true));
    }

    /**
     * A lock some other command ran for the user must never rewrite their pins, so {@code -F} on
     * {@code jk tree} stays a re-fetch — it does not turn the invisible freshen into an upgrade.
     */
    @Test
    void a_freshen_keeps_pins_even_under_force() {
        assertThat(LockVerb.modeFor(request(false, true, false))).isEqualTo(new LockMode.Freshen());
        assertThat(LockVerb.modeFor(request(true, true, false))).isEqualTo(new LockMode.Freshen());
    }

    private static LockRequest request(boolean force, boolean freshen, boolean sources) {
        return new LockRequest("/w", "/c", List.of(), false, sources, null, false, force, false, freshen);
    }
}
