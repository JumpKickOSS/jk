// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.EngineScope.Decision;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** When the engine launch is prefixed with a delegated systemd user scope, and what it says when not. */
class EngineScopeTest {

    private static final Path SYSTEMD = Path.of("/usr/bin/systemd-run");

    @Test
    void not_linux_and_a_zero_switch_and_a_missing_tool_stay_plain() {
        Map<String, String> ready = Map.of(
                "JK_ENGINE_SCOPE", "1",
                "XDG_RUNTIME_DIR", "/run/user/1000",
                "DBUS_SESSION_BUS_ADDRESS", "unix:path=/run/user/1000/bus");

        assertThat(EngineScope.decide(false, ready, SYSTEMD, true)).isEqualTo(Decision.plain(""));
        assertThat(EngineScope.decide(true, Map.of("JK_ENGINE_SCOPE", " 0 "), SYSTEMD, true)
                        .note())
                .isEqualTo("JK_ENGINE_SCOPE=0");
        assertThat(EngineScope.decide(true, Map.of("JK_ENGINE_SCOPE", "0"), SYSTEMD, true)
                        .scoped())
                .isFalse();
        assertThat(EngineScope.decide(true, Map.of("JK_ENGINE_SCOPE", "false"), SYSTEMD, true)
                        .scoped())
                .isFalse();
        assertThat(EngineScope.decide(true, Map.of("JK_ENGINE_SCOPE", "yes"), SYSTEMD, true)
                        .scoped())
                .isTrue();
        assertThat(EngineScope.decide(true, Map.of(), null, true).note()).isEqualTo("systemd-run is not on PATH");
        assertThat(EngineScope.decide(true, Map.of(), SYSTEMD, false).note())
                .isEqualTo("systemd user manager is not reachable");
    }

    @Test
    void a_reachable_user_manager_prefixes_systemd_run_even_when_an_engine_exe_is_set() {
        Map<String, String> env = Map.of("JK_ENGINE_EXE", "/opt/engine");

        Decision decision = EngineScope.decide(true, env, SYSTEMD, true);

        assertThat(decision.scoped()).isTrue();
        assertThat(decision.note()).isEmpty();
        assertThat(decision.prefix())
                .startsWith(SYSTEMD.toString(), "--user", "--scope", "--quiet", "--collect", "-p", "Delegate=yes");
        assertThat(decision.prefix()).anyMatch(arg -> arg.matches("--unit=jk-engine-[0-9a-f]{8}"));
        assertThat(decision.prefix()).endsWith("--");
    }

    @Test
    void the_prefix_is_the_argv_before_the_engine_command() {
        List<String> prefix = EngineScope.prefix(SYSTEMD, "abcd1234");
        List<String> engine =
                List.of("/opt/jdk/bin/java", "-Xmx64m", "-cp", "engine.jar", "cc.jumpkick.engine.EngineMain");

        List<String> command = EngineScope.command(prefix, engine);

        assertThat(command).startsWith(SYSTEMD.toString(), "--user", "--scope");
        assertThat(command).contains("--unit=jk-engine-abcd1234", "--");
        assertThat(command.indexOf("--")).isLessThan(command.indexOf("/opt/jdk/bin/java"));
        assertThat(command).endsWith("cc.jumpkick.engine.EngineMain");
        assertThat(EngineScope.command(List.of(), engine)).isSameAs(engine);
    }

    @Test
    void the_bus_socket_or_the_session_address_counts_as_a_user_manager(@TempDir Path runtime) throws Exception {
        Files.writeString(runtime.resolve("bus"), "");
        assertThat(EngineScope.userManagerReachable(Map.of("XDG_RUNTIME_DIR", runtime.toString())))
                .isTrue();
        assertThat(EngineScope.userManagerReachable(
                        Map.of("XDG_RUNTIME_DIR", runtime.resolve("missing").toString())))
                .isFalse();
        assertThat(EngineScope.userManagerReachable(Map.of("DBUS_SESSION_BUS_ADDRESS", "unix:path=/run/user/1/bus")))
                .isTrue();
        assertThat(EngineScope.userManagerReachable(Map.of())).isFalse();
    }

    @Test
    void the_scope_launch_keeps_the_bus_and_a_plain_launch_keeps_the_note() {
        Map<String, String> child = new HashMap<>();
        child.put(EngineScope.NOTE, "stale");
        EngineScope.keepBus(child, Map.of("XDG_RUNTIME_DIR", "/run/user/1000", "DBUS_SESSION_BUS_ADDRESS", "unix:x"));
        EngineScope.applyNote(child, "");
        assertThat(child)
                .containsEntry("XDG_RUNTIME_DIR", "/run/user/1000")
                .containsEntry("DBUS_SESSION_BUS_ADDRESS", "unix:x");
        assertThat(child).doesNotContainKey(EngineScope.NOTE);

        EngineScope.applyNote(child, "systemd-run exited 1: Failed to connect");
        assertThat(child).containsEntry(EngineScope.NOTE, "systemd-run exited 1: Failed to connect");
    }

    @Test
    void a_failed_scope_keeps_the_first_line_of_its_output() {
        String output = "\njk engine: spawning /engine.jar (lib) at 2026-09-25T00:00:00Z\n"
                + "Failed to connect to user scope bus via local transport: bus is gone\n";

        assertThat(EngineScope.failureNote(1, output))
                .isEqualTo(
                        "systemd-run exited 1: Failed to connect to user scope bus via local transport: bus is gone");
        assertThat(EngineScope.failureNote(-1, "exec failed")).isEqualTo("systemd-run failed to start: exec failed");
        assertThat(EngineScope.failureNote(1, "  ")).isEqualTo("systemd-run exited 1");

        String longLine = "x".repeat(400);
        assertThat(EngineScope.failureNote(1, longLine)).hasSize(160).endsWith("...");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void systemd_run_is_the_first_executable_on_path(@TempDir Path root) throws Exception {
        Path bin = root.resolve("bin");
        Files.createDirectory(bin);
        Path tool = bin.resolve("systemd-run");
        Files.writeString(tool, "#!/bin/sh\n");
        Files.setPosixFilePermissions(
                tool,
                EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
        Path empty = root.resolve("empty");
        Files.createDirectory(empty);

        assertThat(EngineScope.systemdRun(null)).isNull();
        assertThat(EngineScope.systemdRun(empty + File.pathSeparator + bin)).isEqualTo(tool);
        assertThat(EngineScope.systemdRun(bin + File.pathSeparator)).isEqualTo(tool);
    }
}
