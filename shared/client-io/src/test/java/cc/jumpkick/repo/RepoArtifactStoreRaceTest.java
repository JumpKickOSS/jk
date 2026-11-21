// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two writers storing different bytes under one path leave a memo that names the bytes on disk:
 * a writer's file and its memo land together, so one writer's digest is never stamped on the
 * other's file.
 */
class RepoArtifactStoreRaceTest {

    @Test
    @Timeout(30)
    void a_writer_landing_between_another_writers_move_and_memo_does_not_get_its_digest(@TempDir Path dir)
            throws Exception {
        Path cache = dir.resolve("cache");
        Path first = dir.resolve("first.jar");
        Path second = dir.resolve("second.jar");
        Files.writeString(first, "first bytes");
        Files.writeString(second, "other bytes");
        String firstSha = Hashing.sha256Hex(first);
        String secondSha = Hashing.sha256Hex(second);
        RepoArtifactStore store = new RepoArtifactStore(cache, "central");
        String rel = "com/example/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.jar";

        ExecutorService other = Executors.newSingleThreadExecutor();
        AtomicReference<Future<?>> secondWrite = new AtomicReference<>();
        try {
            store.materialize(rel, first, firstSha, true, () -> {
                Future<?> write = other.submit(() -> store.materialize(rel, second, secondSha));
                secondWrite.set(write);
                try {
                    write.get(500, TimeUnit.MILLISECONDS);
                } catch (TimeoutException heldOff) {
                    // the second writer waits for the first to finish publishing
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            secondWrite.get().get(10, TimeUnit.SECONDS);
        } finally {
            other.shutdownNow();
        }

        Path artifact = cache.resolve("repos/central").resolve(rel);
        String landed = Hashing.sha256Hex(artifact);
        String lost = landed.equals(firstSha) ? secondSha : firstSha;
        ArtifactMemo memo = ArtifactMemo.read(ArtifactMemo.jkPath(cache.resolve("repos/central"), rel))
                .orElseThrow();
        assertThat(memo.sha256()).as("the memo names the bytes on disk").isEqualTo(landed);
        assertThat(store.verify(rel, lost))
                .as("the other writer's digest does not verify these bytes")
                .isEqualTo(RepoArtifactStore.IndexState.MISMATCH);
        assertThat(store.verify(rel, landed)).isEqualTo(RepoArtifactStore.IndexState.VERIFIED);
    }
}
