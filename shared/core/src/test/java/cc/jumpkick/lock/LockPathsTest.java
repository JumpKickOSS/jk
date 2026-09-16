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

    @Test
    void a_pom_only_module_owns_its_lock_beside_its_shadow(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), POM.formatted("solo", ""));
        Path expected = dir.toAbsolutePath().normalize();
        assertThat(LockPaths.lockOwnerDir(dir)).isEqualTo(expected);
        assertThat(LockPaths.lockFile(dir)).isEqualTo(expected.resolve("target/jk/shadow/jk-lock.toml"));
        assertThat(LockPaths.isWorkspaceLock(dir)).isFalse();
    }

    @Test
    void a_reactor_leaf_uses_the_lock_beside_the_root_shadow(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), POM.formatted("reactor", """
                  <modules>
                    <module>libs/api</module>
                    <module>storage</module>
                  </modules>
                """));
        Path api = Files.createDirectories(root.resolve("libs/api"));
        Files.writeString(api.resolve("pom.xml"), POM.formatted("api", ""));
        // A nested aggregator lists its own children; the outermost root still owns the lock.
        Path storage = Files.createDirectories(root.resolve("storage"));
        Files.writeString(storage.resolve("pom.xml"), POM.formatted("storage", """
                  <modules>
                    <module>cassandra</module>
                  </modules>
                """));
        Path cassandra = Files.createDirectories(storage.resolve("cassandra"));
        Files.writeString(cassandra.resolve("pom.xml"), POM.formatted("cassandra", ""));

        Path expectedRoot = root.toAbsolutePath().normalize();
        Path expectedLock = expectedRoot.resolve("target/jk/shadow/jk-lock.toml");
        assertThat(LockPaths.lockOwnerDir(root)).isEqualTo(expectedRoot);
        assertThat(LockPaths.lockOwnerDir(api)).isEqualTo(expectedRoot);
        assertThat(LockPaths.lockOwnerDir(storage)).isEqualTo(expectedRoot);
        assertThat(LockPaths.lockOwnerDir(cassandra)).isEqualTo(expectedRoot);
        assertThat(LockPaths.lockFile(root)).isEqualTo(expectedLock);
        assertThat(LockPaths.lockFile(cassandra)).isEqualTo(expectedLock);
        assertThat(LockPaths.isWorkspaceLock(root)).isTrue();
        assertThat(LockPaths.isWorkspaceLock(api)).isTrue();
        assertThat(LockPaths.isWorkspaceLock(cassandra)).isTrue();

        Path stray = Files.createDirectories(root.resolve("stray"));
        Files.writeString(stray.resolve("pom.xml"), POM.formatted("stray", ""));
        assertThat(LockPaths.lockOwnerDir(stray))
                .isEqualTo(stray.toAbsolutePath().normalize());
    }

    /** A minimal POM: {@code %s} takes the artifactId, then the modules block or nothing. */
    private static final String POM = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>%s</artifactId>
              <version>1.0.0</version>
            %s</project>
            """;
}
