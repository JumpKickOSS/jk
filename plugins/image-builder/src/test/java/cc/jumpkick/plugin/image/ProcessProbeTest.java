// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two probes that ask a foreign binary a question — the image's {@code java -version} and
 * {@code docker info} — each carry a deadline, and the deadline has to be reachable: a probe that
 * reads the child to EOF before it starts waiting cannot expire while a wedged child keeps its
 * pipe open, and the step hangs with it.
 */
@DisabledOnOs(OS.WINDOWS)
class ProcessProbeTest {

    private static final Duration PROBE = Duration.ofMillis(500);

    @Test
    void a_java_that_never_answers_is_killed_when_the_probe_expires(@TempDir Path tmp) throws Exception {
        Path pid = tmp.resolve("pid");
        Path java = wedged(tmp.resolve("java"), pid);

        long start = System.nanoTime();
        boolean runs = BaseJre.runsVersion(java, PROBE);

        assertThat(runs).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .as("the probe returns at its deadline, not at the child's")
                .isLessThan(Duration.ofSeconds(10));
        assertThat(alive(pid))
                .as("the child is destroyed on expiry, not left behind")
                .isFalse();
    }

    @Test
    void a_java_that_answers_is_accepted(@TempDir Path tmp) throws Exception {
        Path java = script(tmp.resolve("java"), "echo 'openjdk version \"25\"' >&2\nexit 0\n");

        assertThat(BaseJre.runsVersion(java, Duration.ofSeconds(30))).isTrue();
    }

    @Test
    void a_docker_that_never_answers_is_killed_and_assumed_rootful(@TempDir Path tmp) throws Exception {
        Path pid = tmp.resolve("pid");
        Path docker = wedged(tmp.resolve("docker"), pid);

        long start = System.nanoTime();
        boolean rootful = AotCacheTrainer.rootfulDocker(docker.toString(), PROBE);

        assertThat(rootful).as("no answer is treated like a failed probe").isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
        assertThat(alive(pid)).isFalse();
    }

    @Test
    void a_rootless_docker_is_recognised_from_its_security_options(@TempDir Path tmp) throws Exception {
        Path docker = script(tmp.resolve("docker"), "echo '[name=seccomp,profile=builtin name=rootless]'\n");

        assertThat(AotCacheTrainer.rootfulDocker(docker.toString(), Duration.ofSeconds(30)))
                .isFalse();
    }

    /** A tool that records its pid and then holds its stdout open without ever writing to it. */
    private static Path wedged(Path file, Path pid) throws IOException {
        return script(file, "echo $$ > '" + pid + "'\nexec sleep 30\n");
    }

    private static Path script(Path file, String body) throws IOException {
        Files.writeString(file, "#!/bin/sh\n" + body, StandardCharsets.UTF_8);
        assertThat(file.toFile().setExecutable(true)).isTrue();
        return file;
    }

    /** Whether the process whose pid the child recorded is still running, after giving a kill time to land. */
    private static boolean alive(Path pidFile) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (true) {
            Optional<ProcessHandle> handle = Files.isRegularFile(pidFile)
                    ? ProcessHandle.of(Long.parseLong(
                            Files.readString(pidFile, StandardCharsets.UTF_8).strip()))
                    : Optional.empty();
            boolean alive = handle.map(ProcessHandle::isAlive).orElse(false);
            if (!alive || System.nanoTime() > deadline) return alive;
            Thread.sleep(50);
        }
    }
}
