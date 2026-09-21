// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectIdsTest {

    @TempDir
    Path stateDir;

    @BeforeEach
    void sandboxState() {
        System.setProperty("jk.env.JK_STATE_DIR", stateDir.toString());
        ProjectIds.clear();
    }

    @AfterEach
    void restoreState() {
        System.clearProperty("jk.env.JK_STATE_DIR");
        ProjectIds.clear();
    }

    /**
     * A first build memoizes the lockless id at admission, then its lock step mints {@code
     * project-id}. The memo must follow the write inside the same build, or every key the rest of
     * that build stores is tagged with an id no later build resolves to.
     */
    @Test
    void writing_a_lock_that_mints_an_id_refreshes_the_memo(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "demo"
                version = "0.1.0"
                """);
        String before = ProjectIds.idOf(dir.toString());
        assertThat(before).isNotNull();

        LockfileWriter.write(Lockfile.empty("test"), dir.resolve("jk-lock.toml"));
        String minted = ProjectIdentity.recordedId(dir.resolve("jk-lock.toml")).orElseThrow();

        assertThat(minted).isNotEqualTo(before);
        assertThat(ProjectIds.idOf(dir.toString()))
                .as("the memo names the lock's id inside the build that minted it")
                .isEqualTo(minted);
        assertThat(ProjectIds.idOf(dir.toRealPath().toString())).isEqualTo(minted);
    }

    @Test
    void a_memoized_id_is_answered_from_the_memo_until_refreshed(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), "name = \"demo\"\n");
        String first = ProjectIds.idOf(dir.toString());
        // A lock written behind the memo's back (no writer of ours) is invisible until a refresh.
        Files.writeString(
                dir.resolve("jk-lock.toml"), "version = 1\nproject-id = \"0123456789abcdef0123456789abcdef\"\n");
        assertThat(ProjectIds.idOf(dir.toString())).isEqualTo(first);
        assertThat(ProjectIds.refresh(dir.toString())).isEqualTo("0123456789abcdef0123456789abcdef");
        assertThat(ProjectIds.idOf(dir.toString())).isEqualTo("0123456789abcdef0123456789abcdef");
    }
}
