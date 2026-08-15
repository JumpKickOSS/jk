// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code LockfileWriter.write} must be atomic — a reader polling {@code jk-lock.toml}
 * during repeated writes never observes a truncated or half-written file.
 */
class LockfileWriterAtomicTest {

    @Test
    void concurrent_reader_never_sees_a_torn_lockfile(@TempDir Path tmp) throws Exception {
        Path lockFile = tmp.resolve("jk-lock.toml");
        Lockfile lock = bigLockfile();
        LockfileWriter.write(lock, lockFile);
        int artifacts = LockfileReader.read(lockFile).artifacts().size();

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            while (!stop.get()) {
                try {
                    Lockfile seen = LockfileReader.read(lockFile);
                    if (seen.artifacts().size() != artifacts) {
                        readerFailure.set(new AssertionError(
                                "partial lock observed: " + seen.artifacts().size() + " of " + artifacts));
                        return;
                    }
                } catch (Throwable t) {
                    readerFailure.set(t);
                    return;
                }
            }
        });
        reader.start();
        for (int i = 0; i < 200 && readerFailure.get() == null; i++) {
            LockfileWriter.write(lock, lockFile);
        }
        stop.set(true);
        reader.join(10_000);

        assertThat(readerFailure.get()).isNull();
    }

    private static Lockfile bigLockfile() {
        List<Lockfile.Artifact> artifacts = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            artifacts.add(new Lockfile.Artifact(
                    "com.example:artifact-" + i,
                    "1.0." + i,
                    "https://repo.example/artifact-" + i,
                    "sha256:" + "ab".repeat(32),
                    null,
                    List.of(cc.jumpkick.model.Scope.MAIN),
                    List.of(),
                    null,
                    null));
        }
        return new Lockfile(Lockfile.CURRENT_VERSION, "jk test", Lockfile.RESOLUTION_ALGORITHM, artifacts);
    }
}
