// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.model.Sidecar;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.wire.protocol.ExecPlan;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** A workspace stack whose members are all node dev servers: nothing to recompile, only processes to wait on. */
@DisabledOnOs(OS.WINDOWS)
class StackWatchLoopTest {

    /** A stack with no JVM member never compiles; a compile reaching here is a bug. */
    private static final AppWatchLoop.Compiler NO_COMPILE = (projectDir, cache) -> {
        throw new AssertionError("a stack of node members alone compiles nothing");
    };

    @Test
    void a_stack_of_node_members_alone_waits_on_its_processes_and_ends_with_them(@TempDir Path dir) {
        ExecPlan.Sidecar web = new ExecPlan.Sidecar(
                "web",
                List.of("sh", "-c", "echo web up; sleep 0.3"),
                dir.toString(),
                Map.of(),
                Map.of(),
                new ExecPlan.Probe("", "web up", 5_000L),
                false,
                false,
                Sidecar.Restart.NEVER);
        ExecPlan stack = ExecPlan.devStack(dir, Map.of("web", dir.toString()), List.of(web));
        AppWatchLoop loop = new AppWatchLoop(
                new GlobalOptions(), null, "jk dev", false, NO_COMPILE, NO_COMPILE, NO_COMPILE, List.of());

        int exit = assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> new StackWatchLoop(loop, stack, List.of()).run(dir.resolve("cache"), List.of()),
                "the loop returns once its only process has exited");
        assertThat(exit).isEqualTo(Exit.SOFTWARE);
    }
}
