// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk test} on a workspace whose modules compile but carry no test suite is not green: every
 * module finishes, and the run's own verdict is {@code no tests ran} with exit 2, so an empty run
 * cannot be mistaken for a passing suite. The same workspace still builds.
 */
// Out of the unit tier: a real compile and a real forked test step.
@Tag("integration")
class EmptyTestRunE2eTest {

    @Test
    void a_workspace_test_run_in_which_no_module_ran_a_test_fails_with_no_tests_ran(@TempDir Path tmp)
            throws Exception {
        Path ws = workspace(tmp);

        WorkspaceResult test = run(ws, tmp, true);

        assertThat(test.success()).isFalse();
        assertThat(test.exitCode()).isEqualTo(Exit.CONFIG);
        assertThat(test.errors())
                .containsExactly(
                        "no tests ran: none of the 2 modules has a test suite (com.example:lib, com.example:app)");
        assertThat(test.modules())
                .as("every module finished; the verdict is the run's")
                .allMatch(m -> m.success());

        WorkspaceResult build = run(ws, tmp, false);
        assertThat(build.success()).as("the same workspace builds").isTrue();
        assertThat(build.errors()).isEmpty();
    }

    private static WorkspaceResult run(Path ws, Path tmp, boolean testOnly) throws Exception {
        Path cache = tmp.resolve("cache");
        WorkspaceRequest request = new WorkspaceRequest(ws, cache, null, 1, null, false, false, 2, null, false, false)
                .withTestOnly(testOnly);
        return SessionContext.where(
                Session.defaults(), () -> WorkspaceExecute.buildWorkspace(request, WorkspaceBuildListener.NOOP));
    }

    /** lib and app have main sources and no test tree; app depends on lib. Locked once at the root. */
    private static Path workspace(Path tmp) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["lib", "app"]
                """);
        Path lib = Files.createDirectories(ws.resolve("lib"));
        Files.writeString(lib.resolve("jk.toml"), "name = \"lib\"\n");
        Files.createDirectories(lib.resolve("src/com/example"));
        Files.writeString(lib.resolve("src/com/example/Lib.java"), """
                package com.example;
                public final class Lib {
                    public static int twice(int n) { return n * 2; }
                }
                """);
        Path app = Files.createDirectories(ws.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                name = "app"

                [dependencies]
                lib = { workspace = true }
                """);
        Files.createDirectories(app.resolve("src/com/example"));
        Files.writeString(app.resolve("src/com/example/App.java"), """
                package com.example;
                public final class App {
                    public static int useLib(int n) { return Lib.twice(n); }
                }
                """);
        JkBuild root = JkBuildParser.parse(ws.resolve("jk.toml"));
        BuildPlan lock =
                LockPlans.lockBuildPlan(ws, root, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("workspace lock").isTrue();
        Files.copy(ws.resolve("jk-lock.toml"), lib.resolve("jk-lock.toml"));
        Files.copy(ws.resolve("jk-lock.toml"), app.resolve("jk-lock.toml"));
        return ws;
    }
}
