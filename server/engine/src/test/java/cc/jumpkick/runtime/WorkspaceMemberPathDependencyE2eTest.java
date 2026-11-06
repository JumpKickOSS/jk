// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.FeatureSelection;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.runtime.base.GraphOps;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end: a member's {@code path} dependency resolves from the member's own directory. */
@Tag("integration")
class WorkspaceMemberPathDependencyE2eTest {

    @Test
    void a_members_path_dependency_outside_the_workspace_locks_and_builds(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("member-path-dependency-cache");
        Path outside = Files.createDirectories(tmp.resolve("outside/src/main/java/o"));
        Files.writeString(tmp.resolve("outside/jk.toml"), """
                group   = "o"
                name    = "outside"
                version = "1.0.0"
                java    = 25
                javadoc = false
                """);
        Files.writeString(outside.resolve("Outside.java"), """
                package o;

                public final class Outside {
                    private Outside() {}

                    public static String hello() {
                        return "outside";
                    }
                }
                """);
        // From the root, ../../outside names a directory that does not exist.
        Path ws = workspace(tmp, """
                [dependencies]
                outside = { path = "../../outside" }
                """, "return o.Outside.hello();");

        lock(ws, cache);
        Lockfile lock = LockfileReader.read(LockPaths.lockFile(ws));
        assertThat(lock.artifacts()).anyMatch(a -> a.name().startsWith("o:outside:"));

        // The rows the member's compile reads: its path dependency's, resolved from the member.
        Path app = ws.resolve("app");
        assertThat(ClasspathResolver.moduleRows(
                                lock, ClasspathResolver.COMPILE_MAIN, JkBuildParser.parse(app.resolve("jk.toml")), app)
                        .stream()
                        .map(Lockfile.Artifact::name))
                .anyMatch(n -> n.startsWith("o:outside:"));

        String tree = GraphOps.treeRender(app, 0, false, false, List.of(), FeatureSelection.DEFAULTS);
        assertThat(tree).as(tree).contains("⟦go⟧:⟦aoutside⟧:⟦v1.0.0⟧").doesNotContain("missing");

        WorkspaceResult built = WorkspaceExecute.buildWorkspace(request(ws, cache), WorkspaceBuildListener.NOOP);
        assertThat(built.errors()).isEmpty();
        assertThat(built.success()).isTrue();
    }

    @Test
    void a_members_path_dependency_on_a_member_is_refused_from_the_members_directory(@TempDir Path tmp)
            throws Exception {
        Path cache = TestCaches.dir("member-path-dependency-cache");
        Path ws = workspace(tmp, """
                [dependencies]
                core = { path = "../core" }
                """, "return t.core.Core.hello();");

        WorkspaceResult result = WorkspaceExecute.buildWorkspace(request(ws, cache), WorkspaceBuildListener.NOOP);

        assertThat(result.success()).isFalse();
        assertThat(result.errors())
                .contains("app: `core = { path = \"../core\" }` names workspace member t:core;"
                        + " write `core = { workspace = true }`");
    }

    /** A workspace of {@code core} and {@code app}; app's manifest ends in {@code appDeps}. */
    private static Path workspace(Path tmp, String appDeps, String appBody) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "t"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["core", "app"]
                """);
        Files.createDirectories(ws.resolve("core/src/main/java/t/core"));
        Files.writeString(ws.resolve("core/jk.toml"), """
                group   = "t"
                name    = "core"
                version = "1.0.0"
                java    = 25
                javadoc = false
                """);
        Files.writeString(ws.resolve("core/src/main/java/t/core/Core.java"), """
                package t.core;

                public final class Core {
                    private Core() {}

                    public static String hello() {
                        return "core";
                    }
                }
                """);
        Files.createDirectories(ws.resolve("app/src/main/java/t/app"));
        Files.writeString(ws.resolve("app/jk.toml"), """
                group   = "t"
                name    = "app"
                version = "1.0.0"
                java    = 25
                javadoc = false

                """ + appDeps);
        Files.writeString(ws.resolve("app/src/main/java/t/app/App.java"), """
                package t.app;

                public final class App {
                    private App() {}

                    public static String hello() {
                        %s
                    }
                }
                """.formatted(appBody));
        return ws;
    }

    private static void lock(Path ws, Path cache) throws Exception {
        LockPlans.LockScope scope = LockPlans.lockScope(ws);
        BuildPlan lock = LockPlans.lockBuildPlan(
                scope.lockDir(), scope.effective(), cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("workspace lock").isTrue();
    }

    private static WorkspaceRequest request(Path ws, Path cache) {
        return new WorkspaceRequest(ws, cache, null, 0, null, true, false, 2, null, false, false);
    }
}
