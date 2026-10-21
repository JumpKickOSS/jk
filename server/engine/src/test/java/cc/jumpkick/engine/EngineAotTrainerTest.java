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

    @Test
    void the_trainer_engine_is_owned_by_this_engine_and_kept_apart_from_its_state(@TempDir Path dir) {
        Map<String, String> env = EngineAotTrainer.env(dir, dir.resolve("engine.aot"));
        assertThat(env)
                .containsEntry(
                        OwnerWatchdog.ENV, Long.toString(ProcessHandle.current().pid()))
                .containsEntry("JK_STATE_DIR", dir.resolve("state").toString())
                .containsEntry("JK_CACHE_DIR", dir.resolve("cache").toString());
    }

    @Test
    void a_process_still_running_out_of_the_scratch_is_killed_and_others_are_not(@TempDir Path dir) throws Exception {
        Path scratch = dir.resolve("engine-aot-train-1");
        Process inside = loop(scratch.resolve("state/engine"));
        Process outside = loop(dir.resolve("elsewhere"));
        try {
            EngineAotTrainer.killStragglers(scratch);
            assertThat(inside.isAlive()).isFalse();
            assertThat(outside.isAlive()).isTrue();
        } finally {
            inside.destroyForcibly();
            outside.destroyForcibly();
        }
    }

    @Test
    void a_killed_engines_scratch_is_dropped_before_the_next_recording(@TempDir Path tmp) throws Exception {
        Path stale = Files.createDirectories(tmp.resolve("engine-aot-train-7/state/engine"));
        Path other = Files.createDirectories(tmp.resolve("worker-gc"));
        Process trainer = loop(stale);
        try {
            EngineAotTrainer.dropStaleScratch(tmp);
            assertThat(trainer.isAlive()).isFalse();
            assertThat(tmp.resolve("engine-aot-train-7")).doesNotExist();
            assertThat(other).isDirectory();
        } finally {
            trainer.destroyForcibly();
        }
    }

    /** A process that runs until killed with {@code arg} among its arguments. */
    private static Process loop(Path arg) throws Exception {
        return new ProcessBuilder("sh", "-c", "while :; do sleep 1; done", "loop", arg.toString()).start();
    }

    private static Path script(Path dir, String body) throws Exception {
        Path cli = dir.resolve("jk");
        Files.writeString(cli, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(cli, PosixFilePermissions.fromString("rwxr-xr-x"));
        return cli;
    }
}
