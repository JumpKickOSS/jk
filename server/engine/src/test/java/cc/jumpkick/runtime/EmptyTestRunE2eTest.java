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
 * {@code jk test} on a workspace whose modules compile but carry no test suite is green, as {@code
 * mvn test} is: every module finishes and the run exits 0 with no error. The client's line says
 * {@code No tests to run}.
 */
// Out of the unit tier: a real compile and a real forked test step.
@Tag("integration")
class EmptyTestRunE2eTest {

    @Test
    void a_workspace_test_run_in_which_no_module_has_a_test_exits_zero(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);

        WorkspaceResult test = run(ws, tmp, true);

        assertThat(test.success()).isTrue();
        assertThat(test.exitCode()).isEqualTo(Exit.SUCCESS);
        assertThat(test.errors()).isEmpty();
        assertThat(test.modules()).hasSize(2).allMatch(m -> m.success());

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
