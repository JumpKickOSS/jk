// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LockPathsTest {

    @Test
    void standalone_project_owns_its_lock(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "solo"
                version = "1.0.0"
                """);
        assertThat(LockPaths.lockOwnerDir(dir)).isEqualTo(dir.toAbsolutePath().normalize());
        assertThat(LockPaths.lockFile(dir).getFileName().toString()).isEqualTo("jk-lock.toml");
        assertThat(LockPaths.isWorkspaceLock(dir)).isFalse();
    }

    @Test
    void workspace_member_uses_root_lock(@TempDir Path ws) throws Exception {
        Files.writeString(ws.resolve("jk.toml"), """
                group = "com.example"
                name = "ws"
                version = "1.0.0"

                [workspace]
                modules = ["mod-a"]
                """);
        Path mod = ws.resolve("mod-a");
        Files.createDirectories(mod);
        Files.writeString(mod.resolve("jk.toml"), """
                group = "com.example"
                name = "mod-a"
                version = "1.0.0"
                """);

        assertThat(LockPaths.lockOwnerDir(mod)).isEqualTo(ws.toAbsolutePath().normalize());
        assertThat(LockPaths.lockFile(mod))
                .isEqualTo(ws.resolve("jk-lock.toml").toAbsolutePath().normalize());
        assertThat(LockPaths.lockFile(ws))
                .isEqualTo(ws.resolve("jk-lock.toml").toAbsolutePath().normalize());
        assertThat(LockPaths.isWorkspaceLock(mod)).isTrue();
        assertThat(LockPaths.isWorkspaceLock(ws)).isTrue();
    }

    @Test
    void nested_but_not_listed_is_standalone(@TempDir Path ws) throws Exception {
        Files.writeString(ws.resolve("jk.toml"), """
                group = "com.example"
                name = "ws"
                version = "1.0.0"

                [workspace]
                modules = ["mod-a"]
                """);
        Path orphan = ws.resolve("orphan");
        Files.createDirectories(orphan);
        Files.writeString(orphan.resolve("jk.toml"), """
                group = "com.example"
                name = "orphan"
                version = "1.0.0"
                """);

        assertThat(LockPaths.lockOwnerDir(orphan))
                .isEqualTo(orphan.toAbsolutePath().normalize());
        assertThat(LockPaths.isWorkspaceLock(orphan)).isFalse();
    }
}
