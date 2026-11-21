// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;

import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A workspace's own lock freshen that the solver refuses keeps the conflict behind its reason, on
 * its verdict and on the result the build ends with.
 */
class WorkspaceLockConflictTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    @TempDir
    Path isolatedStore;

    @BeforeEach
    void start() {
        // The product store is ambient; a test that resolves keeps its metadata out of the real one.
        System.setProperty("jk.env.JK_STORE_DIR", isolatedStore.resolve("store").toString());
        upstream.leaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        upstream.leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
        upstream.leaf("com.foo", "lib", "1.0");
    }

    @Test
    void a_freshen_with_no_solution_carries_its_conflict_as_one_object(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "com.example"
                name  = "demo"
                version = "1.0.0"
                java = 25

                [dependencies]
                lib = { group = "com.foo", name = "lib", version = "=9.9" }
                """);

        BuildService.LockGuard guard =
                WorkspaceLock.ensureWorkspaceLockFresh(tmp, tmp.resolve("cache"), true, http.base());

        assertThat(guard.status()).isEqualTo(Exit.RESOLVE);
        assertThat(guard.error()).contains("com.foo:lib");
        Map<?, ?> conflict = (Map<?, ?>) requireNonNull(MiniJson.parse(guard.conflict()));
        assertThat(conflict.get("facts")).asInstanceOf(LIST).anySatisfy(fact -> assertThat(
                        ((Map<?, ?>) fact).get("package"))
                .isEqualTo("com.foo:lib"));
    }

    @Test
    void a_workspace_build_whose_lock_has_no_solution_ends_with_its_conflict(@TempDir Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["app"]

                [repositories.stub]
                url = "%s"
                groups = ["com.foo", "org.junit.jupiter", "org.junit.platform"]
                """.formatted(http.base()));
        Path app = Files.createDirectories(ws.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25

                [dependencies]
                lib = { group = "com.foo", name = "lib", version = "=9.9" }
                """);

        WorkspaceResult result = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(ws, tmp.resolve("cache"), null, 0, null, true, false, 1, null, false, true),
                new WorkspaceBuildListener() {});

        assertThat(result.exitCode()).isEqualTo(Exit.RESOLVE);
        assertThat(result.modules()).as("no module ran").isEmpty();
        Map<?, ?> conflict = (Map<?, ?>) requireNonNull(MiniJson.parse(result.conflict()));
        assertThat(conflict.get("facts")).asInstanceOf(LIST).anySatisfy(fact -> assertThat(
                        ((Map<?, ?>) fact).get("package"))
                .isEqualTo("com.foo:lib"));
    }

    @Test
    void a_fresh_lock_has_no_conflict() {
        assertThat(BuildService.LockGuard.OK.conflict()).isEmpty();
    }
}
