// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.Sleepers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * stopping an engine must be reliable without the user reaching for {@code kill}.
 *
 * <p>These cover the termination contract rather than the socket protocol: the point of the fleet helper
 * is that it reports what actually happened to a process instead of assuming a request was obeyed. On
 * Windows the alternative is telling someone to identify the right JVM in Task Manager.
 */
// Serial phase: spawns real processes and asserts on live pids scoped to this home.
@Tag("integration")
class EngineFleetTest {

    /** A grace for a fake engine that ignores the drain: long enough for a hard kill to land. */
    private static final long SHORT_GRACE_MS = 1_000;

    @Test
    void a_process_that_is_already_gone_counts_as_exited() {
        // A pid nothing owns must read as gone rather than hang out the grace period, or every stop of an
        // already-dead engine would pause for seconds and then claim a kill it did not perform.
        long neverRunning = 0x7FFF_FFFFL;

        assertThat(EngineFleet.waitForExit(neverRunning)).isTrue();
    }

    @Test
    void an_unaddressable_pid_is_treated_as_gone_not_as_a_kill() {
        // pid <= 0 means "no pid recorded". Claiming to have killed it would be a lie; claiming it is gone
        // is the honest reading, since there is nothing left to stop.
        assertThat(EngineFleet.waitForExit(0)).isTrue();
        assertThat(EngineFleet.waitForExit(-1)).isTrue();
    }

    @Test
    void a_live_process_is_not_reported_as_exited() throws Exception {
        // The case that matters: waitForExit must not return true for something still running, or a stop
        // would report success over a surviving engine.
        Process sleeper = Sleepers.sleeper(30).start();
        try {
            assertThat(sleeper.isAlive()).isTrue();

            // The grace is short: the answer for a live process is the same at any deadline.
            assertThat(EngineFleet.waitGone(sleeper.pid(), SHORT_GRACE_MS)).isFalse();
            assertThat(sleeper.isAlive()).isTrue();
        } finally {
            sleeper.destroyForcibly();
            sleeper.waitFor();
        }
    }

    @Test
    void exit_is_noticed_while_waiting_rather_than_only_at_the_deadline() throws Exception {
        // Polls for the exit, so a quick shutdown returns quickly. If this waited out the full grace, every
        // `stop` would feel broken even when it worked.
        Process sleeper = Sleepers.sleeper(30).start();
        long startNanos = System.nanoTime();
        Thread killer = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            sleeper.destroyForcibly();
        });
        killer.start();
        try {
            assertThat(EngineFleet.waitForExit(sleeper.pid())).isTrue();
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            // LIVENESS, not performance. waitForExit's own grace is 8s and the kill lands at 300ms,
            // so this only fails if the wait ignored the exit and sat out the whole deadline. It is
            // deliberately half the grace rather than a tight number — a budget close to the real
            // cost would be a fact about this machine, which is the defect shape.
            assertThat(elapsedMs)
                    .as("returned when the process exited, not after sitting out the 8s grace")
                    .isLessThan(4_000);
        } finally {
            killer.join();
            sleeper.waitFor();
        }
    }

    @Test
    void stopping_a_pid_no_engine_owns_reports_nothing_rather_than_pretending() {
        // `stop --pid <wrong>` must say so. Silently succeeding would leave the real engine running while
        // telling the user it was handled.
        assertThat(EngineFleet.stopByPid(0x7FFF_FFFEL, /* now= */ true)).isEmpty();
    }

    @Test
    void listing_never_throws_when_nothing_is_running() {
        // Called from `jk engine status` on every invocation, including a machine with no engine at all.
        assertThat(EngineFleet.list()).isNotNull();
    }

    @Test
    void retirement_kills_only_a_resident_engine_in_the_exact_retired_home(@TempDir Path tmp) throws Exception {
        var retired = new RetiredEngineLayouts.Layout(tmp.resolve("retired"), tmp.resolve("state"));
        Path jar = retired.engineHome().resolve("lib/jk-engine/jk-engine-test.jar");
        Process oldEngine = SleepMain.spawn(30_000, jar.toString(), "cc.jumpkick.engine.EngineMain");
        try {
            var member = new EngineFleet.Member(null, null, null, oldEngine.pid(), false);

            var results = EngineFleet.retireOldDefaultLayoutEngines(
                    List.of(member), tmp.resolve("current"), retired, SHORT_GRACE_MS);

            assertThat(results)
                    .singleElement()
                    .extracting(EngineFleet.StopResult::outcome)
                    .isEqualTo(EngineFleet.Outcome.KILLED);
            assertThat(oldEngine.waitFor(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            oldEngine.destroyForcibly();
            oldEngine.waitFor();
        }
    }

    @Test
    void retirement_preserves_an_explicit_current_home_engine(@TempDir Path tmp) throws Exception {
        Path explicitHome = tmp.resolve("explicit");
        var retired = new RetiredEngineLayouts.Layout(tmp.resolve("retired"), tmp.resolve("state"));
        Path jar = explicitHome.resolve("lib/jk-engine/jk-engine-test.jar");
        Process currentEngine = SleepMain.spawn(30_000, jar.toString(), "cc.jumpkick.engine.EngineMain");
        try {
            var member = new EngineFleet.Member(null, null, null, currentEngine.pid(), false);

            assertThat(EngineFleet.retireOldDefaultLayoutEngines(
                            List.of(member), explicitHome, retired, SHORT_GRACE_MS))
                    .isEmpty();
            assertThat(currentEngine.isAlive()).isTrue();
        } finally {
            currentEngine.destroyForcibly();
            currentEngine.waitFor();
        }
    }

    @Test
    void a_process_that_merely_mentions_the_engine_is_not_one() throws Exception {
        // The command-line predicate is a substring match, so a shell that ran `jk`, a grep over
        // this tree, or an editor with EngineMain.java open all say yes to it. They are not
        // engines, and `stop --all` hard-kills whatever the fleet claims — this is the guard
        // between the fleet and the user's own terminal.
        Process shell =
                Sleepers.sleeperMentioning(30, "cc.jumpkick.engine.EngineMain").start();
        try {
            ProcessHandle handle = ProcessHandle.of(shell.pid()).orElseThrow();
            String cmd = EngineFleet.commandLineOf(handle);

            assertThat(EngineFleet.isResidentEngine(cmd))
                    .as("the command line alone does name the engine — which is the trap")
                    .isTrue();
            assertThat(EngineFleet.isResidentEngineProcess(handle, cmd))
                    .as("but a shell cannot host an engine")
                    .isFalse();
        } finally {
            shell.destroyForcibly();
            shell.waitFor();
        }
    }

    @Test
    void a_jvm_whose_command_line_names_the_engine_is_one() throws Exception {
        assertThat(EngineFleet.isResidentEngineProcess(
                        ProcessHandle.current(), "java -cp x cc.jumpkick.engine.EngineMain"))
                .as("this test JVM is a JVM; the command line decides the rest")
                .isTrue();
        assertThat(EngineFleet.isResidentEngineProcess(ProcessHandle.current(), "java -cp x SomethingElse"))
                .isFalse();
    }

    @Test
    void list_includes_a_live_generation_pid_that_the_endpoint_does_not_name(@TempDir Path state) throws Exception {
        // A JVM carrying the engine main class as a trailing (ignored) argument, so the dummy
        // matches the resident-engine command-line predicate on every platform. A shell $0 trick
        // only reads back out of /proc: on Windows the predicate would see `sh.exe` and the pid
        // would never be listed.
        Process dummy = SleepMain.spawn(30_000, "cc.jumpkick.engine.EngineMain");
        try {
            // The Windows command-line snapshot is taken at most once per TTL; this process table
            // is one process older than any snapshot a sibling test left behind.
            Path engineDir = Files.createDirectories(state.resolve("engine"));
            Files.writeString(engineDir.resolve("abcd1234.endpoint"), "abcd1234.gen2.sock\n");
            Files.writeString(engineDir.resolve("abcd1234.gen1.pid"), dummy.pid() + "\n");

            var members = EngineFleet.list(state, "abcd1234");
            assertThat(members.stream().map(EngineFleet.Member::pid))
                    .as("draining predecessor pid is listed even though the endpoint names gen2")
                    .contains(dummy.pid());
            assertThat(members.stream().filter(m -> m.pid() == dummy.pid()).count())
                    .as("the same pid is not listed twice (generation file + process scan)")
                    .isEqualTo(1);
        } finally {
            dummy.destroyForcibly();
            dummy.waitFor();
        }
    }

    @Test
    void an_engine_of_another_home_is_listed_as_such_and_stop_all_leaves_it(@TempDir Path tmp) throws Exception {
        Path otherHome = tmp.resolve("other-home");
        Path heapDumps = otherHome.resolve("state").resolve("engine");
        // The heap-dump flag goes last: its value runs to the next " -" or the end of the line.
        Process foreign = SleepMain.spawn(30_000, "cc.jumpkick.engine.EngineMain", "-XX:HeapDumpPath=" + heapDumps);
        try {

            var listed = EngineFleet.list(tmp.resolve("state"), "abcd1234").stream()
                    .filter(m -> m.pid() == foreign.pid())
                    .toList();
            assertThat(listed).singleElement().satisfies(m -> {
                assertThat(m.otherHome()).isTrue();
                assertThat(m.home()).isEqualTo(otherHome.toAbsolutePath().normalize());
            });
            assertThat(EngineFleet.list(tmp.resolve("state"), "abcd1234", false))
                    .extracting(EngineFleet.Member::pid)
                    .doesNotContain(foreign.pid());

            EngineFleet.FleetStop stop = EngineFleet.stopAll(listed, /* now= */ true);

            assertThat(stop.results()).isEmpty();
            assertThat(stop.otherHomes()).extracting(EngineFleet.Member::pid).containsExactly(foreign.pid());
            assertThat(foreign.isAlive())
                    .as("stop -A never touches another home's engine")
                    .isTrue();
        } finally {
            foreign.destroyForcibly();
            foreign.waitFor();
        }
    }

    @Test
    void an_engine_home_is_read_from_its_jar_or_its_state_dir(@TempDir Path tmp) {
        Path one = tmp.resolve("one").toAbsolutePath().normalize();
        Path two = tmp.resolve("two").toAbsolutePath().normalize();
        Path custom = tmp.resolve("custom").toAbsolutePath().normalize();
        String main = " cc.jumpkick.engine.EngineMain";

        assertThat(EngineFleet.engineHome("java -cp " + one.resolve("lib/jk-engine/e.jar") + main))
                .isEqualTo(one);
        assertThat(EngineFleet.engineHome(
                        "java -XX:HeapDumpPath=" + two.resolve("state/engine") + " -cp classes" + main))
                .isEqualTo(two);
        assertThat(EngineFleet.engineHome(
                        "java -XX:HeapDumpPath=" + custom.resolve("st/engine") + " -cp classes" + main))
                .as("a custom state dir names no home")
                .isNull();
        assertThat(EngineFleet.engineHome("java -cp classes" + main)).isNull();
    }
}
