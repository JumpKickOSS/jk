// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.EngineFleet;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.testing.NoAnsi;
import cc.jumpkick.model.command.Exit;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Engines of other homes in {@code jk engine status} and {@code jk engine stop -A}: status marks them
 * with their home, and a stop that leaves them running says how many and how to stop them.
 */
class EngineOtherHomesTest {

    private static final Path SCRATCH = Path.of("scratch-home").toAbsolutePath();

    private static EngineFleet.Member ours(long pid) {
        return new EngineFleet.Member(null, null, null, pid, false);
    }

    private static EngineFleet.Member other(long pid, @Nullable Path home) {
        return new EngineFleet.Member(null, null, null, pid, false, true, home);
    }

    @Test
    void jsonl_marks_an_engine_of_another_home_with_that_home() {
        String json = EngineStatusCommand.enginesJson(List.of(ours(1), other(2, SCRATCH), other(3, null)));

        assertThat(json)
                .contains("\"pid\":1,\"current\":false,\"responsive\":false,\"otherHome\":false}")
                .contains("\"pid\":2,\"current\":false,\"responsive\":false,\"otherHome\":true,\"home\":"
                        + quoted(SCRATCH) + "}")
                .contains("\"pid\":3,\"current\":false,\"responsive\":false,\"otherHome\":true,\"home\":null}");
    }

    @Test
    void the_status_hint_offers_stop_all_only_for_this_homes_engines() {
        assertThat(EngineStatusCommand.stopHint(List.of(ours(1), ours(2))))
                .isEqualTo("Stop this home's engines with `jk engine stop -A`, or one engine with"
                        + " `jk engine stop -p <pid>`.");
        assertThat(EngineStatusCommand.stopHint(List.of(ours(1), other(2, SCRATCH), other(3, null))))
                .isEqualTo("`jk engine stop -A` stops this home's 1 engine and leaves the others."
                        + " The engines of other homes stop with `jk engine stop -p 2,3`.");
        assertThat(EngineStatusCommand.stopHint(List.of(other(7, SCRATCH))))
                .isEqualTo("None of these belong to this home. The engine of another home stops with"
                        + " `jk engine stop -p 7`.");
    }

    @Test
    void stop_all_names_the_engines_of_other_homes_it_left_and_the_command_that_stops_them() throws Exception {
        EngineFleet.StopResult stopped = new EngineFleet.StopResult(ours(1), EngineFleet.Outcome.STOPPED);
        List<EngineFleet.Member> left = List.of(other(2, SCRATCH), other(3, SCRATCH), other(4, null));

        Capture.Streams settled = NoAnsi.forced(
                () -> Capture.both(() -> assertThat(new EngineStopCommand().report(List.of(stopped), left))
                        .isEqualTo(Exit.SUCCESS)));

        assertThat(settled.out())
                .contains("1 engine stopped; left 3 engines of other homes running (" + SCRATCH
                        + ": pid 2, 3; home unknown: pid 4); `jk engine stop -p 2,3,4` stops them");
    }

    @Test
    void stop_all_with_only_other_homes_running_says_so_instead_of_no_engines() throws Exception {
        Capture.Streams settled = NoAnsi.forced(() ->
                Capture.both(() -> assertThat(new EngineStopCommand().report(List.of(), List.of(other(9, SCRATCH))))
                        .isEqualTo(Exit.SUCCESS)));

        assertThat(settled.out())
                .contains("no engines of this home running; left 1 engine of another home running (" + SCRATCH
                        + ": pid 9); `jk engine stop -p 9` stops it");
    }

    @Test
    void a_pid_list_with_a_non_number_settles_red_naming_it() throws Exception {
        Capture.Streams settled = NoAnsi.forced(() -> Capture.both(() ->
                assertThat(new EngineStopCommand().stopByPid("12, x", false)).isEqualTo(Exit.FAILURE)));

        assertThat(settled.err()).contains("not a pid: x");
    }

    private static String quoted(Path path) {
        return "\"" + path.toString().replace("\\", "\\\\") + "\"";
    }
}
