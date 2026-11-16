// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Os;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * An engine a client starts does not hold the client's stdout: a script that pipes {@code jk} (an
 * installer under {@code irm | iex}, CI, an agent) reads that pipe to its end, and on Windows the
 * engine inherited its write end, so the reader waited until the engine exited.
 */
class DetachedStartTest {

    @TempDir
    Path tmp;

    @Test
    void a_started_engine_leaves_its_starters_stdout_to_end_with_the_starter() throws Exception {
        assertThat(stdoutEndsWithTheStarter("detached"))
                .as("the starter's stdout ended with the starter, not with the process it started")
                .isTrue();
    }

    /** The cause, pinned: on Windows a plain ProcessBuilder start passes every inheritable handle on. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void on_windows_a_plain_start_passes_an_inherited_pipe_on() throws Exception {
        assertThat(stdoutEndsWithTheStarter("plain")).isFalse();
    }

    private boolean stdoutEndsWithTheStarter(String how) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", Os.isWindows() ? "java.exe" : "java")
                .toString();
        Process parent = new ProcessBuilder(List.of(
                        java,
                        "-cp",
                        System.getProperty("java.class.path"),
                        DetachedParentMain.class.getName(),
                        tmp.resolve(how + ".log").toString(),
                        how))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        BufferedReader out = new BufferedReader(new InputStreamReader(parent.getInputStream(), StandardCharsets.UTF_8));
        long sleeper = Long.parseLong(out.readLine().strip());
        try {
            assertThat(parent.waitFor(30, TimeUnit.SECONDS))
                    .as("the starter exits")
                    .isTrue();
            CompletableFuture<Boolean> ended = CompletableFuture.supplyAsync(() -> {
                try {
                    return out.readLine() == null;
                } catch (Exception e) {
                    return false;
                }
            });
            try {
                // A held pipe never ends; three seconds tells the two apart without a long wait.
                return ended.get(3, TimeUnit.SECONDS);
            } catch (TimeoutException held) {
                return false;
            }
        } finally {
            ProcessHandle.of(sleeper).ifPresent(ProcessHandle::destroyForcibly);
            ProcessHandle.of(sleeper).ifPresent(h -> h.onExit().join());
            parent.destroyForcibly();
        }
    }
}
