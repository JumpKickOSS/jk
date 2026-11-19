// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Which engines a fleet stop reaches: {@code jk engine stop -A} this home's, {@code -A -F} every home's. */
class EngineFleetStopScopeTest {

    private static final List<EngineFleet.Member> FLEET = List.of(
            new EngineFleet.Member(null, null, null, 1, false),
            new EngineFleet.Member(null, null, null, 2, false, true, Path.of("scratch-home"), null),
            new EngineFleet.Member(null, null, null, 3, false, true, null, null));

    @Test
    void stop_all_reaches_only_this_homes_engines() {
        assertThat(EngineFleet.targets(FLEET, false))
                .extracting(EngineFleet.Member::pid)
                .containsExactly(1L);
    }

    @Test
    void stop_all_with_force_reaches_every_homes_engines() {
        assertThat(EngineFleet.targets(FLEET, true))
                .extracting(EngineFleet.Member::pid)
                .containsExactly(1L, 2L, 3L);
    }
}
