// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A lock's {@code project-id} names a file under jk's state directory, so a path-like one refuses the lock. */
class LockProjectIdTest {

    private static Path lock(Path dir, String id) throws Exception {
        Path lock = dir.resolve("jk-lock.toml");
        Files.writeString(lock, """
                version = 1
                generated-by = "jk 0.1.0"
                resolution-algorithm = "pubgrub-v1"
                project-id = "ID"
                """.replace("ID", id));
        return lock;
    }

    @ParameterizedTest
    @ValueSource(strings = {"../../config", "/etc/passwd", "a/b", "a\\\\b", "..", "x y"})
    void a_path_like_project_id_refuses_the_lock(String id, @TempDir Path dir) throws Exception {
        Path lock = lock(dir, id);
        assertThatThrownBy(() -> LockfileReader.read(lock)).hasMessageContaining("`project-id` is not a valid id");
    }

    @Test
    void a_minted_project_id_reads_back(@TempDir Path dir) throws Exception {
        assertThat(LockfileReader.read(lock(dir, "0123abcd-ef_9")).projectId()).isEqualTo("0123abcd-ef_9");
    }
}
