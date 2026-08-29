// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Os;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The Windows command-line snapshot: the parse contract everywhere, the live query where there is
 * a Windows to query.
 */
class WindowsCommandLinesTest {

    @Test
    void rows_are_pid_tab_command_line() {
        Map<Long, String> parsed = WindowsCommandLines.parse(
                "1234\tC:\\jdk\\bin\\java.exe -cp x cc.jumpkick.engine.EngineMain\r\n" + "56\tsihost.exe\r\n");

        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(1234L)).isEqualTo("C:\\jdk\\bin\\java.exe -cp x cc.jumpkick.engine.EngineMain");
        assertThat(parsed.get(56L)).isEqualTo("sihost.exe");
    }

    @Test
    void a_row_that_is_not_pid_tab_something_is_skipped_rather_than_guessed_at() {
        // A command line can carry an embedded newline; the continuation must not be read as a row,
        // and no line may be attributed to a pid it does not name.
        Map<Long, String> parsed = WindowsCommandLines.parse("1234\tjava.exe -Dnote=first\n"
                + "second line of that same argument\n"
                + "\tno pid at all\n"
                + "notanumber\twhatever\n"
                + "99\t\n"
                + "\n"
                + "77\tjavaw.exe");

        assertThat(parsed).containsOnlyKeys(1234L, 77L);
        assertThat(parsed.get(1234L)).isEqualTo("java.exe -Dnote=first");
    }

    @Test
    void empty_and_null_output_are_an_empty_snapshot() {
        assertThat(WindowsCommandLines.parse(null)).isEmpty();
        assertThat(WindowsCommandLines.parse("")).isEmpty();
        assertThat(WindowsCommandLines.parse("   \n  ")).isEmpty();
    }

    @Test
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
    void off_windows_the_snapshot_is_empty_and_costs_nothing() {
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
            WindowsCommandLines.resetForTests();

            String cmd = WindowsCommandLines.of(child.pid());

            assertThat(cmd).contains("jk-marker-cc.jumpkick.engine.EngineMain");
            assertThat(cmd).contains("java");
        } finally {
            child.destroyForcibly();
            child.waitFor();
        }
    }
}
