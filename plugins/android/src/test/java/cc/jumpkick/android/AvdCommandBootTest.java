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
 * emulator keeps writing to its stdout after that line; the pipe it writes into is the command's,
 * and closing it turns the emulator's next log line into a broken pipe.
 */
@DisabledOnOs(OS.WINDOWS)
class AvdCommandBootTest {

    @Test
    void the_emulators_output_stays_writable_after_the_boot_line(@TempDir Path tmp) throws Exception {
        Path marker = tmp.resolve("wrote-after-boot");
        // The line after the sleep reaches a reader only while the pipe is open; on a closed one
        // the write fails and the marker is never created.
        Path emulator = script(tmp.resolve("emulator"), """
                echo "emulator: INFO: boot completed"
                sleep 1
                echo "emulator: INFO: adb connected" || exit 3
                : > '%s'
                """.formatted(marker));
        Process process = new TaskExec.ToolRun(emulator).start();
        List<String> out = new ArrayList<>();

        long start = System.nanoTime();
        int exit = AvdCommand.awaitBoot(process, out::add);

        assertThat(exit).isZero();
        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .as("the command returns at the boot line, not at the emulator's exit")
                .isLessThan(Duration.ofSeconds(5));
        assertThat(out).anyMatch(line -> line.contains("boot completed"));
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue())
                .as("the emulator's write after the boot line succeeds")
                .isZero();
        assertThat(marker).exists();
    }

    @Test
    void an_emulator_that_exits_before_booting_reports_its_exit_status(@TempDir Path tmp) throws Exception {
        Path emulator = script(tmp.resolve("emulator"), "echo 'emulator: ERROR: no accel'\nexit 7\n");
        List<String> out = new ArrayList<>();

        int exit = AvdCommand.awaitBoot(new TaskExec.ToolRun(emulator).start(), out::add);

        assertThat(exit).isEqualTo(7);
        assertThat(out).anyMatch(line -> line.contains("no accel"));
    }

    private static Path script(Path file, String body) throws IOException {
        Files.writeString(file, "#!/bin/sh\n" + body, StandardCharsets.UTF_8);
        assertThat(file.toFile().setExecutable(true)).isTrue();
        return file;
    }
}
