// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.WindowsHostEngine.Mode;
import cc.jumpkick.engine.WindowsHostEngine.Ran;
import cc.jumpkick.engine.WindowsHostEngine.State;
import cc.jumpkick.host.Guest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A WSL engine making sure the Windows engine runs, over a fake {@code cmd.exe}, {@code wslpath} and
 * {@code jk.exe}: interop on and off, an install missing, the engine running or stopped, and a start
 * that fails. Detecting WSL and interop from {@code /proc} is {@code GuestTest}'s.
 */
class WindowsHostEngineTest {

    @TempDir
    Path windows;

    private final Map<String, String> windowsEnv = new HashMap<>(Map.of("USERPROFILE", "C:\\Users\\me"));
    private final Map<String, String> processEnv = new HashMap<>();
    private final List<String> ran = new ArrayList<>();
    private boolean running;
    private int startExit;
    private boolean statusBlind;

    private static final Guest WSL = new Guest(Guest.Kind.WSL, "Ubuntu", true, "machine-id=1");
    private static final Guest WSL_NO_INTEROP = new Guest(Guest.Kind.WSL, "Ubuntu", false, "machine-id=1");

    private @Nullable Ran shell(List<String> argv) {
        String cmd = argv.getFirst();
        if (cmd.equals("cmd.exe")) {
            String name = argv.get(3).replace("echo ", "").replace("%", "");
            return new Ran(0, windowsEnv.getOrDefault(name, "%" + name + "%") + "\r\n");
        }
        if (cmd.equals("wslpath")) {
            // C:\Users\me\.jk\bin\jk.exe -> <windows>/Users/me/.jk/bin/jk.exe
            return new Ran(0, windows.resolve(argv.get(2).substring(3).replace('\\', '/')) + "\n");
        }
        ran.add(String.join(" ", argv.subList(1, argv.size())));
        if (argv.get(2).equals("start")) {
            boolean was = running;
            if (startExit == 0) running = true;
            return new Ran(
                    startExit, was ? "Engine > Engine already running (pid 4)\n" : "Engine > Build engine started\n");
        }
        if (argv.get(2).equals("status")) {
            boolean seen = running && !statusBlind;
            return new Ran(seen ? 0 : 1, "{\"schema\":1,\"running\":" + seen + "}\n");
        }
        return null;
    }

    private void installWindowsJk() throws Exception {
        Path exe = windows.resolve("Users/me/.jk/bin/jk.exe");
        Files.createDirectories(exe.getParent());
        Files.writeString(exe, "MZ");
    }

    private WindowsHostEngine.Outcome ensure(Guest guest, Mode mode) {
        return WindowsHostEngine.ensure(guest, mode, processEnv::get, this::shell);
    }

    @Test
    void a_stopped_windows_engine_is_started_with_jk_exe() throws Exception {
        installWindowsJk();
        assertThat(ensure(WSL, Mode.DEFAULT).state()).isEqualTo(State.STARTED);
        assertThat(ran).containsExactly("engine status -O json", "engine start -u -x");
    }

    @Test
    void a_running_windows_engine_is_left_alone() throws Exception {
        installWindowsJk();
        running = true;
        assertThat(ensure(WSL, Mode.DEFAULT).state()).isEqualTo(State.RUNNING);
        assertThat(ran).containsExactly("engine status -O json");
    }

    @Test
    void with_interop_off_the_guest_is_plain_linux_and_asks_windows_nothing() throws Exception {
        installWindowsJk();
        assertThat(ensure(WSL_NO_INTEROP, Mode.AUTO).state()).isEqualTo(State.SKIPPED);
        assertThat(ensure(Guest.NONE, Mode.AUTO).state()).isEqualTo(State.SKIPPED);
        assertThat(ran).isEmpty();
    }

    @Test
    void off_a_private_home_and_ci_ask_nothing_unless_the_config_says_auto() throws Exception {
        installWindowsJk();
        assertThat(ensure(WSL, Mode.OFF).state()).isEqualTo(State.SKIPPED);
        processEnv.put("JK_HOME", "/tmp/home");
        assertThat(ensure(WSL, Mode.DEFAULT).state()).isEqualTo(State.SKIPPED);
        processEnv.clear();
        processEnv.put("CI", "true");
        assertThat(ensure(WSL, Mode.DEFAULT).state()).isEqualTo(State.SKIPPED);
        assertThat(ran).isEmpty();
        assertThat(ensure(WSL, Mode.AUTO).state()).isEqualTo(State.STARTED);
    }

    @Test
    void no_windows_install_and_a_failing_start_each_say_so() throws Exception {
        assertThat(ensure(WSL, Mode.DEFAULT).state()).isEqualTo(State.NOT_INSTALLED);
        installWindowsJk();
        startExit = 3;
        WindowsHostEngine.Outcome failed = ensure(WSL, Mode.DEFAULT);
        assertThat(failed.state()).isEqualTo(State.FAILED);
        assertThat(failed.line()).contains("exited 3");
    }

    @Test
    void a_windows_jk_home_is_where_jk_exe_is_looked_for() throws Exception {
        windowsEnv.put("JK_HOME", "C:\\jk");
        Path exe = windows.resolve("jk/bin/jk.exe");
        Files.createDirectories(exe.getParent());
        Files.writeString(exe, "MZ");
        assertThat(ensure(WSL, Mode.DEFAULT).state()).isEqualTo(State.STARTED);
    }

    @Test
    void the_status_line_is_read_among_other_output() {
        assertThat(WindowsHostEngine.running("progress\n{\"running\":true,\"pid\":4}\n"))
                .isTrue();
        assertThat(WindowsHostEngine.running("{\"running\":false}")).isFalse();
        assertThat(WindowsHostEngine.running("not json")).isFalse();
        assertThat(WindowsHostEngine.running(null)).isFalse();
    }

    @Test
    void the_config_reads_host_engine() throws Exception {
        Path cfg = windows.resolve("config.toml");
        assertThat(Mode.read(cfg)).isEqualTo(Mode.DEFAULT);
        Files.writeString(cfg, "[wsl]\nhost-engine = \"off\"\n");
        assertThat(Mode.read(cfg)).isEqualTo(Mode.OFF);
        Files.writeString(cfg, "[wsl]\nhost-engine = \"auto\"\n");
        assertThat(Mode.read(cfg)).isEqualTo(Mode.AUTO);
    }

    @Test
    void a_host_speaking_another_front_door_protocol_is_named_with_the_side_to_update() {
        String older = "HTTP/1.1 200 OK\r\n\r\n{\"id\":\"0e074694329a\",\"role\":\"host\",\"version\":\"0.51.1\"}";
        assertThat(WindowsHostEngine.mismatch(older, 1)).contains("update JumpKick for Windows", "jk 0.51.1");
        String newer = older.replace("\"version\"", "\"frontDoor\":2,\"version\"");
        assertThat(WindowsHostEngine.mismatch(newer, 1)).contains("update JumpKick in this WSL distro");
        String same = older.replace("\"version\"", "\"frontDoor\":1,\"version\"");
        assertThat(WindowsHostEngine.mismatch(same, 1)).isNull();
        assertThat(WindowsHostEngine.mismatch(same.replace("\"host\"", "\"guest\""), 2))
                .as("a guest holding 8910 is no host to compare with")
                .isNull();
        assertThat(WindowsHostEngine.mismatch("REFUSED", 1)).isNull();
        assertThat(WindowsHostEngine.mismatch(null, 1)).isNull();
    }

    @Test
    void an_engine_the_status_could_not_see_is_not_started_twice_nor_journaled() throws Exception {
        installWindowsJk();
        statusBlind = true;
        running = true;
        assertThat(ensure(WSL, Mode.DEFAULT).state()).isEqualTo(State.RUNNING);
    }
}
