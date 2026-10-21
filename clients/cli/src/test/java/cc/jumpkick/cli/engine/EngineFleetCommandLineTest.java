// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** How the fleet reads an engine's identity, home and state dir off its spawn line; no process runs. */
class EngineFleetCommandLineTest {

    @Test
    void the_outcome_vocabulary_distinguishes_the_case_a_user_must_act_on() {
        // SURVIVED exists so a stop that did not work cannot be reported as one that did.
        assertThat(EngineFleet.Outcome.values())
                .containsExactly(
                        EngineFleet.Outcome.STOPPED,
                        EngineFleet.Outcome.KILLED,
                        EngineFleet.Outcome.DRAINING,
                        EngineFleet.Outcome.LEFT_ALONE,
                        EngineFleet.Outcome.SURVIVED);
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

    /** An engine under any JK_STATE_DIR is found by its heap-dump path, which is its own directory. */
    @Test
    void state_dir_is_parsed_from_the_heap_dump_path_on_the_command_line(@TempDir Path tmp) {
        Path state = tmp.resolve("scratch dir").resolve("state");
        String line = "java -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=" + state.resolve("engine")
                + " -Xss512k -cp /home/u/.jk/lib/jk-engine/jk-engine-0.15.3.jar cc.jumpkick.engine.EngineMain";
        assertThat(EngineFleet.stateFromCommandLine(line)).isEqualTo(state);
        assertThat(EngineFleet.stateFromCommandLine("java -XX:HeapDumpPath=\"" + state.resolve("engine") + "\" -cp x"))
                .isEqualTo(state);
        assertThat(EngineFleet.stateFromCommandLine("java -XX:HeapDumpPath=relative/engine -cp x"))
                .isNull();
        assertThat(EngineFleet.stateFromCommandLine("java -jar other.jar")).isNull();
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
    void a_generation_pid_stem_drops_the_gen_suffix() {
        assertThat(EngineFleet.keyFromPidStem("3fa429a3357ac034.gen1")).isEqualTo("3fa429a3357ac034");
        assertThat(EngineFleet.keyFromPidStem("3fa429a3357ac034")).isEqualTo("3fa429a3357ac034");
    }
}
