// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.build.TaskExec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk avd boot} returns at the emulator's boot line and leaves the emulator running. The
 * emulator keeps writing after that line, so its output goes to a log file under the AVD home:
 * the command follows the file up to the boot line and then has nothing left open — no pipe the
 * emulator's next write could break on, no thread left draining it.
 */
@DisabledOnOs(OS.WINDOWS)
class AvdCommandBootTest {

    @Test
    void the_command_returns_at_the_boot_line_and_the_emulators_later_lines_land_in_the_log(@TempDir Path tmp)
            throws Exception {
        Path marker = tmp.resolve("wrote-after-boot");
        // The line after the sleep is written once the command has returned; a redirect the
        // command no longer reads still takes it, and the marker records that the write succeeded.
        Path emulator = script(tmp.resolve("emulator"), """
                echo "emulator: INFO: boot completed"
                sleep 1
                echo "emulator: INFO: adb connected" || exit 3
                : > '%s'
                """.formatted(marker));
        Path log = tmp.resolve("pixel.log");
        Process process = new TaskExec.ToolRun(emulator).start(ProcessBuilder.Redirect.appendTo(log.toFile()));
        List<String> out = new ArrayList<>();

        long start = System.nanoTime();
        int exit = AvdCommand.awaitBoot(process, log, out::add);

        assertThat(exit).isZero();
        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .as("the command returns at the boot line, not at the emulator's exit")
                .isLessThan(Duration.ofSeconds(5));
        assertThat(out).anyMatch(line -> line.contains("boot completed"));
        assertThat(out).as("the user is told where to tail").anyMatch(line -> line.contains(log.toString()));
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue())
                .as("the emulator's write after the boot line succeeds")
                .isZero();
        assertThat(marker).exists();
        assertThat(Files.readString(log, StandardCharsets.UTF_8))
                .contains("boot completed")
                .contains("adb connected");
        assertThat(out)
                .as("nothing in the command keeps reading after it has returned")
                .noneMatch(line -> line.contains("adb connected"));
    }

    @Test
    void an_emulator_that_exits_before_booting_reports_its_exit_status(@TempDir Path tmp) throws Exception {
        Path emulator = script(tmp.resolve("emulator"), "echo 'emulator: ERROR: no accel'\nexit 7\n");
        Path log = tmp.resolve("pixel.log");
        Process process = new TaskExec.ToolRun(emulator).start(ProcessBuilder.Redirect.appendTo(log.toFile()));
        List<String> out = new ArrayList<>();

        int exit = AvdCommand.awaitBoot(process, log, out::add);

        assertThat(exit).isEqualTo(7);
        assertThat(out).anyMatch(line -> line.contains("no accel"));
    }

    @Test
    void a_line_the_emulator_wrote_without_a_newline_before_dying_is_still_reported(@TempDir Path tmp)
            throws Exception {
        Path emulator = script(tmp.resolve("emulator"), "printf 'emulator: ERROR: no kvm'\nexit 5\n");
        Path log = tmp.resolve("pixel.log");
        Process process = new TaskExec.ToolRun(emulator).start(ProcessBuilder.Redirect.appendTo(log.toFile()));
        List<String> out = new ArrayList<>();

        int exit = AvdCommand.awaitBoot(process, log, out::add);

        assertThat(exit).isEqualTo(5);
        assertThat(out).anyMatch(line -> line.contains("no kvm"));
    }

    private static Path script(Path file, String body) throws IOException {
        Files.writeString(file, "#!/bin/sh\n" + body, StandardCharsets.UTF_8);
        assertThat(file.toFile().setExecutable(true)).isTrue();
        return file;
    }
}
