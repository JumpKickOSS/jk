// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.SourceLocation;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A workspace lock freshen a member's jk.toml refuses points at the spot in that member's file. */
class WorkspaceLockRefusalTest {

    @TempDir
    Path isolatedStore;

    @BeforeEach
    void start() {
        System.setProperty("jk.env.JK_STORE_DIR", isolatedStore.resolve("store").toString());
    }

    private static Path workspace(Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["app"]
                """);
        Path app = Files.createDirectories(ws.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = "x"
                """);
        return ws;
    }

    private static SourceLocation memberLine4(Path ws) {
        return new SourceLocation(
                ws.resolve("app/jk.toml").toAbsolutePath().normalize().toString(), 4, 1);
    }

    @Test
    void a_freshen_a_member_refuses_is_located_in_the_member(@TempDir Path tmp) throws IOException {
        Path ws = workspace(tmp);

        BuildService.LockGuard guard = WorkspaceLock.ensureWorkspaceLockFresh(ws, tmp.resolve("cache"), true, null);

        assertThat(guard.status()).isNotZero();
        assertThat(guard.location()).isEqualTo(memberLine4(ws));
    }

    @Test
    void a_workspace_build_records_where_its_lock_freshen_was_refused(@TempDir Path tmp) throws IOException {
        Path ws = workspace(tmp);
        List<@Nullable SourceLocation> located = new ArrayList<>();

        var result = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(ws, tmp.resolve("cache"), null, 0, null, true, false, 1, null, false, true),
                new WorkspaceBuildListener() {
                    @Override
                    public void onPreflightFailed(
                            String stage, long millis, String reason, @Nullable SourceLocation at, String conflict) {
                        if (stage.equals("lock")) located.add(at);
                    }
                });

        assertThat(result.exitCode()).isNotEqualTo(Exit.SUCCESS);
        assertThat(located).containsExactly(memberLine4(ws));
    }
}
