// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(OS.WINDOWS)
class EngineAotTrainerTest {

    @Test
    void a_step_in_flight_is_killed_the_moment_a_job_arrives(@TempDir Path dir) throws Exception {
        Path cli = script(dir, "sleep 30");
        AtomicBoolean idle = new AtomicBoolean(true);
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            idle.set(false);
        });
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> EngineAotTrainer.step(cli, dir, Map.of(), idle::get, "build"))
                .isInstanceOf(EngineAotTrainer.Yielded.class);
        assertThat((System.nanoTime() - t0) / 1_000_000)
                .as("ms until the step stood down")
                .isLessThan(5_000);
    }

    @Test
    void a_busy_engine_starts_no_step_and_an_idle_one_runs_it_to_its_exit(@TempDir Path dir) throws Exception {
        Path marker = dir.resolve("ran");
        Path cli = script(dir, "touch '" + marker + "'");
        assertThatThrownBy(() -> EngineAotTrainer.step(cli, dir, Map.of(), () -> false, "build"))
                .isInstanceOf(EngineAotTrainer.Yielded.class);
        assertThat(marker).doesNotExist();
        assertThat(EngineAotTrainer.step(cli, dir, Map.of(), () -> true, "build"))
                .isTrue();
        assertThat(marker).exists();
    }

    private static Path script(Path dir, String body) throws Exception {
        Path cli = dir.resolve("jk");
        Files.writeString(cli, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(cli, PosixFilePermissions.fromString("rwxr-xr-x"));
        return cli;
    }
}
