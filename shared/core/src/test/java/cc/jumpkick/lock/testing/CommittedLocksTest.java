// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock.testing;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.LockFreshness;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A fixture's committed lock reads as fresh, so a build over it never re-locks. */
class CommittedLocksTest {

    @Test
    void a_standalone_projects_lock_is_fresh_until_its_manifest_moves(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), "group = \"com.example\"\nname = \"app\"\nversion = \"1.0.0\"\n");
        Path lock = CommittedLocks.write(dir);
        assertThat(LockFreshness.isStale(dir, lock)).isFalse();
        String id = LockfileReader.read(lock).projectId();
        assertThat(id).isNotBlank();

        Files.writeString(dir.resolve("jk.toml"), "\njava = 25\n", StandardOpenOption.APPEND);
        assertThat(LockFreshness.isStale(dir, lock))
                .as("the stamp is of the manifests it was written over")
                .isTrue();
    }

    @Test
    void each_fixture_is_a_project_of_its_own(@TempDir Path tmp) throws Exception {
        // Build history and build numbers are kept per project id: fixtures sharing one would
        // number each other's builds.
        Set<String> ids = new HashSet<>();
        for (String name : List.of("a", "b", "c")) {
            Path dir = Files.createDirectories(tmp.resolve(name));
            Files.writeString(
                    dir.resolve("jk.toml"), "group = \"com.example\"\nname = \"" + name + "\"\nversion = \"1.0.0\"\n");
            ids.add(LockfileReader.read(CommittedLocks.write(dir)).projectId());
        }
        assertThat(ids).hasSize(3).doesNotContainNull();
    }

    @Test
    void a_workspace_lock_covers_its_members_and_keeps_the_rows_it_is_given(@TempDir Path ws) throws Exception {
        Files.writeString(ws.resolve("jk.toml"), """
                group = "com.example"
                name = "ws"
                version = "1.0.0"

                [workspace]
                modules = ["a"]
                """);
        Files.createDirectories(ws.resolve("a"));
        Files.writeString(ws.resolve("a/jk.toml"), "name = \"a\"\n");
        Path lock = CommittedLocks.write(ws, """
                jk-min = "0.1.0"

                [node]
                version = "24.21.0"
                """);
        assertThat(LockFreshness.isStale(ws, lock)).isFalse();
        assertThat(LockFreshness.isStale(ws.resolve("a"), lock)).isFalse();
        Lockfile read = LockfileReader.read(lock);
        assertThat(read.jkMin()).isEqualTo("0.1.0");
        assertThat(read.node()).isNotNull();
    }
}
