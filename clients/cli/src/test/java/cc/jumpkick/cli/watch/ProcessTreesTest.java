// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.tui.GlobalCancel;
import cc.jumpkick.host.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** One pass stops every tree; a tree that ignores the signal is force-killed and waited for. */
@DisabledOnOs(OS.WINDOWS)
class ProcessTreesTest {

    private static Process shell(String script) throws Exception {
        Process p = new ProcessBuilder("sh", "-c", script).start();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (p.descendants().findAny().isEmpty() && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(p.descendants().findAny()).as("sh forked its child").isPresent();
        return p;
    }

    private static List<ProcessHandle> family(Process... roots) {
        List<ProcessHandle> out = new ArrayList<>();
        for (Process p : roots) {
            p.descendants().forEach(out::add);
            out.add(p.toHandle());
        }
        return out;
    }

    @Test
    void every_tree_is_gone_when_the_pass_returns() throws Exception {
        Process a = shell("sleep 30; echo a");
        Process b = shell("sleep 30; echo b");
        List<ProcessHandle> all = family(a, b);
        long start = System.nanoTime();
        ProcessTrees.stop(List.of(a.toHandle(), b.toHandle()), Clock.SYSTEM);
        long tookMillis = (System.nanoTime() - start) / 1_000_000;
        assertThat(all)
                .allSatisfy(h -> assertThat(h.isAlive()).as("pid " + h.pid()).isFalse());
        assertThat(tookMillis)
                .as("both trees left on the polite signal, well inside one grace")
                .isLessThan(ProcessTrees.GRACE.toMillis());
    }

    @Test
    void a_tree_that_ignores_the_signal_is_force_killed_and_waited_for() throws Exception {
        Process stubborn = shell("trap '' TERM; sleep 30; echo x");
        List<ProcessHandle> all = family(stubborn);
        // Half a second of fake time per reading, so the grace and settle windows elapse in a few polls.
        Clock ticking = new Clock() {
            private long nanos;

            @Override
            public long millis() {
                return nanos / 1_000_000;
            }

            @Override
            public long nanos() {
                return nanos += 500_000_000L;
            }
        };
        long start = System.nanoTime();
        ProcessTrees.stop(List.of(stubborn.toHandle()), ticking);
        assertThat(all)
                .allSatisfy(h -> assertThat(h.isAlive()).as("pid " + h.pid()).isFalse());
        assertThat((System.nanoTime() - start) / 1_000_000)
                .as("the deadlines came from the clock, not the wall")
                .isLessThan(ProcessTrees.GRACE.toMillis());
    }

    @Test
    void the_whole_pass_fits_inside_the_interrupt_hook_bound() {
        assertThat(ProcessTrees.GRACE.plus(ProcessTrees.SETTLE).toMillis()).isLessThan(GlobalCancel.HOOKS_BOUND_MILLIS);
    }
}
