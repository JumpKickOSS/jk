// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Os;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** The native command-line buffer contract and live Windows lookups. */
class WindowsCommandLinesTest {

    @Test
    void reads_the_counted_utf16_command_line() {
        String command = "java.exe -Dnote=日本語";
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = commandBuffer(arena, command + "extra", command.length() * 2);
            assertThat(WindowsCommandLines.decode(buffer)).isEqualTo(command);
        }
    }

    @Test
    void rejects_a_command_line_that_points_past_the_query_buffer() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = commandBuffer(arena, "java.exe", 512);
            assertThat(WindowsCommandLines.decode(buffer)).isEmpty();
        }
    }

    @Test
    void an_empty_command_line_is_unknown() {
        try (Arena arena = Arena.ofConfined()) {
            assertThat(WindowsCommandLines.decode(commandBuffer(arena, "", 0))).isEmpty();
        }
    }

    private static MemorySegment commandBuffer(Arena arena, String command, int length) {
        byte[] encoded = command.getBytes(StandardCharsets.UTF_16LE);
        int header = 16;
        MemorySegment buffer = arena.allocate(header + encoded.length, 8);
        buffer.set(ValueLayout.JAVA_SHORT, 0, (short) length);
        buffer.set(ValueLayout.ADDRESS, ValueLayout.ADDRESS.byteAlignment(), buffer.asSlice(header));
        buffer.asSlice(header).copyFrom(MemorySegment.ofArray(encoded));
        return buffer;
    }

    @Test
    // The null command is deliberate: a process with no readable executable.
    @SuppressWarnings("NullAway")
    void jvm_executables_are_recognized_by_name_on_either_separator() {
        assertThat(WindowsCommandLines.isJvmExecutable("C:\\Program Files\\jdk\\bin\\java.exe"))
                .isTrue();
        assertThat(WindowsCommandLines.isJvmExecutable("C:\\jdk\\bin\\javaw.exe"))
                .isTrue();
        assertThat(WindowsCommandLines.isJvmExecutable("/usr/lib/jvm/bin/java")).isTrue();
        assertThat(WindowsCommandLines.isJvmExecutable("C:\\Git\\usr\\bin\\sh.exe"))
                .isFalse();
        // Not a substring match: an unrelated program must not pass for a JVM.
        assertThat(WindowsCommandLines.isJvmExecutable("C:\\tools\\javascript.exe"))
                .isFalse();
        assertThat(WindowsCommandLines.isJvmExecutable("")).isFalse();
        assertThat(WindowsCommandLines.isJvmExecutable(null)).isFalse();
    }

    @Test
    void off_windows_the_query_returns_an_empty_command_line() {
        if (Os.isWindows()) return;
        assertThat(WindowsCommandLines.of(ProcessHandle.current().pid())).isEmpty();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void on_windows_the_query_finds_a_spawned_jvms_own_arguments() throws Exception {
        // The whole point: the JDK reports no arguments for this process on Windows, so anything
        // that reads a command line has to come through here.
        Process child = SleepMain.spawn(30_000, "jk-marker-cc.jumpkick.engine.EngineMain");
        try {
            assertThat(child.info().arguments())
                    .as("the platform gap this class exists to close")
                    .isEmpty();

            String cmd = WindowsCommandLines.of(child.pid());

            assertThat(cmd).contains("jk-marker-cc.jumpkick.engine.EngineMain");
            assertThat(cmd).contains("java");
            assertThat(EngineFleet.isResidentEngineProcess(child.toHandle(), cmd))
                    .isFalse();
        } finally {
            child.destroyForcibly();
            child.waitFor();
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void a_recorded_jvm_with_unreadable_arguments_is_recognized_by_its_executable() throws Exception {
        Process holder = SleepMain.spawnEngine(30_000);
        try {
            assertThat(EngineFleet.looksLikeEngine(holder.toHandle(), "")).isTrue();
        } finally {
            holder.destroyForcibly();
            holder.waitFor();
        }
    }
}
