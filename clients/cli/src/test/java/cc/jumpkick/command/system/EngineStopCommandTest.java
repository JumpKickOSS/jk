// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.EngineFleet;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.testing.NoAnsi;
import cc.jumpkick.model.command.Exit;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code jk engine stop}'s human uptime formatting and the chip each outcome settles with. */
class EngineStopCommandTest {

    @Test
    void uptime_drops_leading_zero_units_but_always_shows_seconds() {
        assertThat(EngineStopCommand.uptime(0)).isEqualTo("0s");
        assertThat(EngineStopCommand.uptime(13_000)).isEqualTo("13s");
        assertThat(EngineStopCommand.uptime((37 * 60 + 13) * 1000L)).isEqualTo("37m 13s");
        assertThat(EngineStopCommand.uptime((3 * 3600 + 37 * 60 + 13) * 1000L)).isEqualTo("3h 37m 13s");
    }

    @Test
    void uptime_full_days_hours_minutes_seconds() {
        long ms = ((14L * 86_400) + (3 * 3600) + (37 * 60) + 13) * 1000L;
        assertThat(EngineStopCommand.uptime(ms)).isEqualTo("14d 3h 37m 13s");
    }

    @Test
    void uptime_keeps_zero_inner_units_once_a_larger_unit_is_present() {
        // 2h 0m 5s — the minutes component stays even though it's zero.
        assertThat(EngineStopCommand.uptime((2 * 3600 + 5) * 1000L)).isEqualTo("2h 0m 5s");
    }

    @Test
    void a_pid_that_is_not_a_number_settles_red_on_stderr() throws Exception {
        Capture.Streams settled = NoAnsi.forced(() -> Capture.both(() ->
                assertThat(new EngineStopCommand().stopByPid("abc", false)).isEqualTo(Exit.FAILURE)));

        assertThat(settled.err()).contains("jk: ! Engine >").contains("not a pid: abc");
        assertThat(settled.out()).isEmpty();
    }

    @Test
    void a_survivor_never_prints_a_cheerful_stopped() throws Exception {
        // Only the pid is read out of the member, so the on-disk identity of a synthetic one is
        // irrelevant — and going through EngineFleet would need a wedged engine on this machine.
        EngineFleet.StopResult survivor = new EngineFleet.StopResult(
                new EngineFleet.Member(null, null, null, 4242L, false), EngineFleet.Outcome.SURVIVED);

        Capture.Streams settled = NoAnsi.forced(() -> Capture.both(() ->
                assertThat(new EngineStopCommand().report(List.of(survivor))).isEqualTo(Exit.FAILURE)));

        assertThat(settled.err()).contains("jk: ! Engine >").contains("did NOT exit: pid [4242]");
        // The green chip is the defect: a failure exit must not carry it on either stream.
        assertThat(settled.err()).doesNotContain("jk: + ");
        assertThat(settled.out()).isEmpty();
    }
}
