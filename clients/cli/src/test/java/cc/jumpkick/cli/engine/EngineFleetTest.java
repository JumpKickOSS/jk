// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.Sleepers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

            assertThat(EngineFleet.waitForExit(sleeper.pid())).isFalse();
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
    void the_outcome_vocabulary_distinguishes_the_case_a_user_must_act_on() {
        // SURVIVED exists so a stop that did not work cannot be reported as one that did.
        assertThat(EngineFleet.Outcome.values())
                .containsExactly(
                        EngineFleet.Outcome.STOPPED,
                        EngineFleet.Outcome.KILLED,
                        EngineFleet.Outcome.DRAINING,
                        EngineFleet.Outcome.SURVIVED);
    }

    @Test
    void listing_never_throws_when_nothing_is_running() {
        // Called from `jk engine status` on every invocation, including a machine with no engine at all.
        assertThat(EngineFleet.list()).isNotNull();
    }

    @Test
    // The null command line is deliberate: a process whose command line could not be read.
    @SuppressWarnings("NullAway")
    void a_resident_engine_is_recognized_without_this_jk_home_on_the_command_line() {
        String production = "/home/u/.jdks/temurin-25/bin/java -cp /home/u/.jk/lib/jk-engine/jk-engine-0.12.0.jar"
                + " cc.jumpkick.engine.EngineMain";
        String testHome = "/home/u/.jdks/temurin-25/bin/java -cp /tmp/test-jk-home/lib/jk-engine/jk-engine-0.12.0.jar"
                + " cc.jumpkick.engine.EngineMain";
        assertThat(EngineFleet.isResidentEngine(production)).isTrue();
        assertThat(EngineFleet.isResidentEngine(testHome)).isTrue();
        assertThat(EngineFleet.isResidentEngine("/usr/bin/java -jar some-app.jar"))
                .isFalse();
        assertThat(EngineFleet.isResidentEngine("")).isFalse();
        assertThat(EngineFleet.isResidentEngine(null)).isFalse();
    }

    @Test
    void stop_scope_is_this_home_not_a_foreign_jk_home() {
        // Under JK_HOME=/tmp/test-jk-home the engine jar lives at <JK_HOME>/lib/jk-engine/.
        Path home = Path.of("/tmp/test-jk-home");
        Path state = Path.of("/tmp/test-jk-home/state");
        String local = "java -cp /tmp/test-jk-home/lib/jk-engine/jk-engine-0.12.0.jar cc.jumpkick.engine.EngineMain";
        String production = "java -cp /home/u/.jk/lib/jk-engine/jk-engine-0.12.0.jar"
                + " -Xmx512m"
                + " cc.jumpkick.engine.EngineMain";
        assertThat(EngineFleet.belongsToThisHome(local, home, state)).isTrue();
        assertThat(EngineFleet.belongsToThisHome(production, home, state)).isFalse();
    }

    /** The engine jar path identifies its owning home root. */
    @Test
    void home_root_is_parsed_from_the_engine_jar_on_the_command_line() {
        assertThat(EngineFleet.homeFromCommandLine("java -cp /tmp/test-jk-home/lib/jk-engine/"
                        + "jk-engine-0.12.0.jar cc.jumpkick.engine.EngineMain"))
                .isEqualTo(Path.of("/tmp/test-jk-home"));
        assertThat(EngineFleet.homeFromCommandLine("java -cp /home/u/.jk/lib/jk-engine/jk-engine-0.12.0.jar"))
                .isEqualTo(Path.of("/home/u/.jk"));
        assertThat(EngineFleet.homeFromCommandLine(
                        "java -cp \"C:\\Users\\Bryan Sant\\AppData\\Local\\jk\\data\\lib\\jk-engine\\engine.jar\""
                                + " cc.jumpkick.engine.EngineMain"))
                .isEqualTo(Path.of("C:/Users/Bryan Sant/AppData/Local/jk/data"));
        assertThat(EngineFleet.homeFromCommandLine("java -jar other.jar")).isNull();
    }

    @Test
    void retired_platform_defaults_are_exact_layouts() {
        Path unixHome = Path.of("/home/user");
        var unix = RetiredEngineLayouts.platformDefault(name -> null, unixHome, false);
        assertThat(unix.engineHome())
                .isEqualTo(unixHome.resolve(".local").resolve("share").resolve("jk"));
        assertThat(unix.stateDir())
                .isEqualTo(unixHome.resolve(".local").resolve("state").resolve("jk"));

        Path windowsHome = Path.of("C:/Users/user");
        var windows = RetiredEngineLayouts.platformDefault(Map.of("LOCALAPPDATA", "D:/Local")::get, windowsHome, true);
        assertThat(windows.engineHome()).isEqualTo(Path.of("D:/Local/jk/data"));
        assertThat(windows.stateDir()).isEqualTo(Path.of("D:/Local/jk/state"));
    }

    @Test
    void retirement_kills_only_a_resident_engine_in_the_exact_retired_home(@TempDir Path tmp) throws Exception {
        var retired = new RetiredEngineLayouts.Layout(tmp.resolve("retired"), tmp.resolve("state"));
        Path jar = retired.engineHome().resolve("lib/jk-engine/jk-engine-test.jar");
        Process oldEngine = SleepMain.spawn(30_000, jar.toString(), "cc.jumpkick.engine.EngineMain");
        try {
            // The Windows command-line snapshot predates this child; the test knows the table moved.
            WindowsCommandLines.resetForTests();
            var member = new EngineFleet.Member(null, null, null, oldEngine.pid(), false);

            var results = EngineFleet.retireOldDefaultLayoutEngines(List.of(member), tmp.resolve("current"), retired);

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
            WindowsCommandLines.resetForTests();
            var member = new EngineFleet.Member(null, null, null, currentEngine.pid(), false);

            assertThat(EngineFleet.retireOldDefaultLayoutEngines(List.of(member), explicitHome, retired))
                    .isEmpty();
            assertThat(currentEngine.isAlive()).isTrue();
        } finally {
            currentEngine.destroyForcibly();
            currentEngine.waitFor();
        }
    }

    @Test
    void a_generation_pid_stem_drops_the_gen_suffix() {
        assertThat(EngineFleet.keyFromPidStem("3fa429a3357ac034.gen1")).isEqualTo("3fa429a3357ac034");
        assertThat(EngineFleet.keyFromPidStem("3fa429a3357ac034")).isEqualTo("3fa429a3357ac034");
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
            WindowsCommandLines.resetForTests();
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
            WindowsCommandLines.resetForTests();
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
}
