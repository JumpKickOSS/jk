// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.LockfileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A project's lock is written with a durable {@code project-id} and its checkout recorded. */
class ProjectLocksTest {

    @TempDir
    Path stateDir;

    @BeforeEach
    void sandboxState() {
        System.setProperty("jk.env.JK_STATE_DIR", stateDir.toString());
        ProjectIdCache.clear();
    }

    @AfterEach
    void restoreState() {
        System.clearProperty("jk.env.JK_STATE_DIR");
        ProjectIdCache.clear();
    }

    @Test
    void a_first_write_mints_the_id_it_returns_and_records_the_checkout(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), "group = \"com.example\"\nname = \"demo\"\nversion = \"0.1.0\"\n");
        Path file = dir.resolve("jk-lock.toml");

        Lockfile written = ProjectLocks.write(Lockfile.empty("test"), file);

        assertThat(written.projectId()).isNotBlank();
        assertThat(LockfileReader.read(file).projectId())
                .as("the lock on disk records the id the write returned")
                .isEqualTo(written.projectId());
        Path home = ProjectBuilds.projectHome(ProjectBuilds.buildsRoot(), ProjectIdentity.resolve(dir));
        assertThat(ProjectIdentity.IdentityFile.read(home))
                .as("the checkout is recorded under the id")
                .isPresent();
    }

    @Test
    void the_lock_writer_alone_writes_the_lock_as_given(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), "group = \"com.example\"\nname = \"demo\"\nversion = \"0.1.0\"\n");
        Path file = dir.resolve("jk-lock.toml");

        LockfileWriter.write(Lockfile.empty("test"), file);

        assertThat(LockfileReader.read(file).projectId()).isNull();
    }
}
