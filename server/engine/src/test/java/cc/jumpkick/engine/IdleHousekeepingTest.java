// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.runtime.TestClassWalls;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The idle boundary releases per-build memos, but the class-wall buffer is the only cross-build
 * test ETA source when history is off, so it survives the boundary then.
 */
class IdleHousekeepingTest {

    @Test
    void the_idle_boundary_keeps_test_walls_when_history_is_off() {
        TestClassWalls.put("/w/idle-test", Map.of("com.example.SlowTest", 1_200L));

        IdleHousekeeping.dropHeapResidue(false);
        assertThat(TestClassWalls.get("/w/idle-test"))
                .as("no harvest wrote these walls anywhere else")
                .containsEntry("com.example.SlowTest", 1_200L);

        IdleHousekeeping.dropHeapResidue(true);
        assertThat(TestClassWalls.get("/w/idle-test"))
                .as("with history on the finish path already harvested them")
                .isEmpty();
    }
}
