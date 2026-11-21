// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two writers storing different bytes under one path leave memos that name the bytes on disk:
 * a writer's file and its memo land together, so one writer's digest is never stamped on the
 * other's file, and a file read while it is replaced gets no memo.
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

    // ---- stores of two homes writing one file of the Maven local repository ----

    private static final URI CENTRAL = URI.create("https://repo.maven.apache.org/maven2/");
    private static final String JAR = "com/example/lib/1.0/lib-1.0.jar";

    @AfterEach
    void forgetTheMode() {
        System.clearProperty("jk.m2.local");
        System.clearProperty("jk.env.JK_M2_INTEGRATION");
    }

    private static RepoArtifactStore sharing(Path cache, Path m2) {
        System.setProperty("jk.m2.local", m2.toString());
        System.setProperty("jk.env.JK_M2_INTEGRATION", "share");
        return RepoArtifactStore.forRepository(cache, "central", CENTRAL);
    }

    /** A file in the local repository each store has a memo of as its own download, as after a fetch by each. */
    private static Path downloadedByBoth(Path m2, Path cacheA, Path cacheB, String content, long mtime)
            throws Exception {
        Path slot = m2.resolve(JAR);
        Files.createDirectories(slot.getParent());
        Files.writeString(slot, content);
        Files.setLastModifiedTime(slot, FileTime.fromMillis(mtime));
        for (Path cache : new Path[] {cacheA, cacheB}) {
            new ArtifactMemo(
                            RepoArtifactStore.inferGav(JAR),
                            mtime,
                            Files.size(slot),
                            Hashing.sha256Hex(slot),
                            null,
                            true)
                    .write(ArtifactMemo.jkPath(cache.resolve("repos/central"), JAR));
        }
        return slot;
    }

    @Test
    @Timeout(30)
    void two_homes_publishing_into_one_local_repository_file_take_one_lock(@TempDir Path dir) throws Exception {
        Path m2 = dir.resolve("m2");
        Path cacheA = dir.resolve("a");
        Path cacheB = dir.resolve("b");
        Path slot = downloadedByBoth(m2, cacheA, cacheB, "stale bytes", System.currentTimeMillis() - 60_000);
        RepoArtifactStore a = sharing(cacheA, m2);
        RepoArtifactStore b = sharing(cacheB, m2);
        Path first = Files.writeString(dir.resolve("first.jar"), "first bytes");
        Path second = Files.writeString(dir.resolve("second.jar"), "other bytes");
        String firstSha = Hashing.sha256Hex(first);
        String secondSha = Hashing.sha256Hex(second);

        ExecutorService other = Executors.newSingleThreadExecutor();
        AtomicReference<Future<?>> secondWrite = new AtomicReference<>();
        boolean[] landedInBetween = new boolean[1];
        try {
            a.materialize(JAR, first, firstSha, true, () -> {
                Future<?> write = other.submit(() -> b.materialize(JAR, second, secondSha));
                secondWrite.set(write);
                try {
                    write.get(500, TimeUnit.MILLISECONDS);
                    landedInBetween[0] = true;
                } catch (TimeoutException heldOff) {
                    // the other home's store waits for this one to finish publishing
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            secondWrite.get().get(10, TimeUnit.SECONDS);
        } finally {
            other.shutdownNow();
        }

        assertThat(landedInBetween[0])
                .as("the other home's store does not publish between this one's move and memo")
                .isFalse();
        assertThat(slot).hasContent("other bytes");
        assertThat(a.verify(JAR, firstSha))
                .as("the first home's memo does not describe the other home's file")
                .isEqualTo(RepoArtifactStore.IndexState.MISMATCH);
        assertThat(b.verify(JAR, secondSha)).isEqualTo(RepoArtifactStore.IndexState.VERIFIED);
        assertThat(m2.resolve(RepoArtifactStore.M2_LOCK_NAME)).exists();
    }

    /**
     * A file stamped later than the clock (a coarse or skewed one) is replaced by a file of the same
     * size: the new file's mtime is still after it, so the other store's memo does not describe it.
     */
    @Test
    void a_file_published_into_the_local_repository_is_newer_than_the_one_it_replaces(@TempDir Path dir)
            throws Exception {
        Path m2 = dir.resolve("m2");
        Path cacheA = dir.resolve("a");
        Path cacheB = dir.resolve("b");
        long ahead = System.currentTimeMillis() + 3_600_000;
        Path slot = downloadedByBoth(m2, cacheA, cacheB, "first bytes", ahead);
        String firstSha = Hashing.sha256Hex(slot);
        RepoArtifactStore a = sharing(cacheA, m2);
        RepoArtifactStore b = sharing(cacheB, m2);
        Path second = Files.writeString(dir.resolve("second.jar"), "other bytes");

        b.materialize(JAR, second, Hashing.sha256Hex(second), true);

        assertThat(slot).hasContent("other bytes");
        assertThat(Files.getLastModifiedTime(slot).toMillis()).isGreaterThan(ahead);
        assertThat(a.verify(JAR, firstSha)).isEqualTo(RepoArtifactStore.IndexState.MISMATCH);
        assertThat(b.verify(JAR, Hashing.sha256Hex(second))).isEqualTo(RepoArtifactStore.IndexState.VERIFIED);
    }

    // ---- a file with no memo, adopted by a look ----

    @Test
    void a_file_replaced_while_it_is_adopted_gets_no_memo_of_the_bytes_that_were_read(@TempDir Path dir)
            throws Exception {
        Path cache = dir.resolve("cache");
        RepoArtifactStore store = new RepoArtifactStore(cache, "central");
        Path artifact = cache.resolve("repos/central").resolve(JAR);
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, "first bytes");
        String firstSha = Hashing.sha256Hex(artifact);
        Path replacement = Files.writeString(dir.resolve("replacement.jar"), "other bytes, longer");

        assertThat(store.locate(JAR, () -> {
                    try {
                        Files.move(replacement, artifact, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }))
                .contains(artifact);

        assertThat(ArtifactMemo.jkPath(cache.resolve("repos/central"), JAR))
                .as("no memo pairs the replaced file's digest with this file's stat")
                .doesNotExist();
        assertThat(store.verify(JAR, firstSha)).isEqualTo(RepoArtifactStore.IndexState.MISMATCH);
        assertThat(store.locate(JAR)).contains(artifact);
        assertThat(store.verify(JAR, Hashing.sha256Hex(artifact))).isEqualTo(RepoArtifactStore.IndexState.VERIFIED);
    }
}
