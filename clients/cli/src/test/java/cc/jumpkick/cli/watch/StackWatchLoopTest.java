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
import java.util.Set;
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

    @Test
    void a_re_planned_stack_differs_when_a_member_or_a_sidecar_comes_or_goes(@TempDir Path dir) {
        ExecPlan.Sidecar db = sidecar(dir, "db");
        Map<String, String> one = Map.of("api", dir.resolve("api").toString());
        Map<String, String> two = Map.of(
                "api",
                dir.resolve("api").toString(),
                "admin",
                dir.resolve("admin").toString());
        // Shape reads a member's plan for its sidecars alone.
        List<ExecPlan> apiPlans = List.of(ExecPlan.devStack(dir.resolve("api"), Map.of(), List.of(sidecar(dir, "mq"))));
        Set<String> wanted = Set.of("api");
        StackWatchLoop.Shape started =
                StackWatchLoop.Shape.of(ExecPlan.devStack(dir, one, List.of(db)), wanted, apiPlans, false);

        assertThat(StackWatchLoop.Shape.of(ExecPlan.devStack(dir, one, List.of(db)), wanted, apiPlans, false))
                .isEqualTo(started);
        assertThat(started.sidecars()).containsOnlyKeys("db", "mq");
        assertThat(StackWatchLoop.Shape.of(ExecPlan.devStack(dir, two, List.of(db)), wanted, apiPlans, false))
                .as("a member added at the root")
                .isNotEqualTo(started);
        assertThat(StackWatchLoop.Shape.of(ExecPlan.devStack(dir, one, List.of()), wanted, apiPlans, false))
                .as("a root sidecar removed")
                .isNotEqualTo(started);
        assertThat(StackWatchLoop.Shape.of(ExecPlan.devStack(dir, one, List.of(db)), wanted, List.of(), false))
                .as("a member's own sidecar removed")
                .isNotEqualTo(started);
        assertThat(StackWatchLoop.Shape.of(ExecPlan.devStack(dir, one, List.of()), wanted, apiPlans, true))
                .as("under --no-sidecars a sidecar edit changes nothing that runs")
                .isEqualTo(StackWatchLoop.Shape.of(ExecPlan.devStack(dir, one, List.of(db)), wanted, apiPlans, true));
    }

    private static ExecPlan.Sidecar sidecar(Path dir, String name) {
        return new ExecPlan.Sidecar(
                name,
                List.of("sh", "-c", "true"),
                dir.toString(),
                Map.of(),
                Map.of(),
                new ExecPlan.Probe("", "", 5_000L),
                false,
                false,
                Sidecar.Restart.NEVER);
    }
}
