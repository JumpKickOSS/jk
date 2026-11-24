// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PerfLapsTest {

    @Test
    void lapsArePhasesInOrderEachTimedFromTheLast() throws Exception {
        Perf.Laps laps = new Perf.Laps(true);
        Thread.sleep(20);
        laps.lap("wait");
        laps.lap("stamp");
        List<Object> phases = laps.phases();
        assertEquals(4, phases.size());
        assertEquals("wait", phases.get(0));
        assertTrue((Long) phases.get(1) >= 20, "the first phase holds the sleep: " + phases);
        assertEquals("stamp", phases.get(2));
        assertTrue((Long) phases.get(3) >= 0, "the second phase is its own: " + phases);
    }

    @Test
    void aValuedLapReturnsTheValueItTimed() {
        Perf.Laps laps = new Perf.Laps(true);
        assertEquals("key", laps.lap("stamp", "key"));
        assertEquals("stamp", laps.phases().get(0));
    }

    @Test
    void lapsOffTimeNothing() {
        Perf.Laps laps = new Perf.Laps(false);
        laps.lap("wait");
        laps.end("rest", "stage");
        assertEquals(List.of(), laps.phases());
    }
}
