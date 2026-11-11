// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Settled;
import cc.jumpkick.model.Scope;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A lock write drops the parsed lock of the file it wrote and no other project's. */
class LockfileWriterMemoTest {

    @AfterEach
    void reset() {
        Settled.aheadForTests(null);
    }

    @Test
    void a_write_keeps_every_other_lock_parsed(@TempDir Path tmp) throws Exception {
        Path written = tmp.resolve("a/jk-lock.toml");
        Path other = tmp.resolve("b/jk-lock.toml");
        LockfileWriter.write(lock("1.0.0"), written);
        LockfileWriter.write(lock("1.0.0"), other);
        // Judged as settled, so the memo keeps what it parses rather than re-reading fresh writes.
        Settled.aheadForTests(Duration.ofMinutes(1));
        LockfileReader.read(written);
        LockfileReader.read(other);
        long before = LockfileReader.parses();

        LockfileWriter.write(lock("2.0.0"), written);

        assertThat(LockfileReader.read(other).artifacts()).hasSize(1);
        assertThat(LockfileReader.parses())
                .as("the other project's lock is still parsed")
                .isEqualTo(before);
        assertThat(LockfileReader.read(written).artifacts().getFirst().version())
                .as("the written lock reads what was written")
                .isEqualTo("2.0.0");
    }

    private static Lockfile lock(String version) {
        Lockfile.Artifact artifact = new Lockfile.Artifact(
                "com.example:lib",
                version,
                "https://repo.example/lib",
                "sha256:" + "ab".repeat(32),
                null,
                List.of(Scope.MAIN),
                List.of(),
                null,
                null);
        return new Lockfile(Lockfile.CURRENT_VERSION, "jk test", Lockfile.RESOLUTION_ALGORITHM, List.of(artifact));
    }
}
