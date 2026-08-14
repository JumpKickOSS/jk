// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileHashMemoTest {

    /** Run {@code body} with the session cache rooted at {@code cache} (memo isolation). */
    private static void withCache(Path cache, Runnable body) {
        SessionContext.runWhere(Session.defaults().withCacheDir(cache), body);
    }

    @Test
    void roundtrip_for_a_settled_file(@TempDir Path dir) throws Exception {
        Path f = Files.writeString(dir.resolve("a.jar"), "AA");
        long mtime = System.currentTimeMillis() - 60_000; // settled long ago
        Files.setLastModifiedTime(f, FileTime.fromMillis(mtime));
        long size = Files.size(f);
        withCache(dir.resolve("cache"), () -> {
            assertThat(FileHashMemo.lookup(f, size, mtime)).as("empty memo").isNull();
            FileHashMemo.store(f, size, mtime, "jar:abc");
            assertThat(FileHashMemo.lookup(f, size, mtime)).isEqualTo("jar:abc");
        });
    }

    @Test
    void stat_mismatch_invalidates(@TempDir Path dir) throws Exception {
        Path f = Files.writeString(dir.resolve("a.jar"), "AA");
        long mtime = System.currentTimeMillis() - 60_000;
        Files.setLastModifiedTime(f, FileTime.fromMillis(mtime));
        long size = Files.size(f);
        withCache(dir.resolve("cache"), () -> {
            FileHashMemo.store(f, size, mtime, "jar:abc");
            assertThat(FileHashMemo.lookup(f, size + 1, mtime))
                    .as("size changed")
                    .isNull();
            assertThat(FileHashMemo.lookup(f, size, mtime - 5_000))
                    .as("mtime changed")
                    .isNull();
        });
    }

    @Test
    void a_freshly_modified_file_is_never_trusted_or_stored(@TempDir Path dir) throws Exception {
        // Filesystem mtimes are truncated: a file modified "just now" could change
        // again within the same tick without the stat noticing. Within the settle
        // window the memo must stand aside and let content hashing decide.
        Path f = Files.writeString(dir.resolve("a.jar"), "AA");
        long now = System.currentTimeMillis();
        long size = Files.size(f);
        withCache(dir.resolve("cache"), () -> {
            FileHashMemo.store(f, size, now, "jar:abc"); // must be a no-op
            long settled = now - 60_000;
            FileHashMemo.store(f, size, settled, "jar:settled");
            assertThat(FileHashMemo.lookup(f, size, now))
                    .as("fresh mtime — never trusted")
                    .isNull();
            assertThat(FileHashMemo.lookup(f, size, settled)).isEqualTo("jar:settled");
        });
    }

    @Test
    void contentHash_reads_bytes_once_per_thread_walk(@TempDir Path dir) throws Exception {
        Path f = Files.writeString(dir.resolve("Src.java"), "class Src {}");
        long mtime = System.currentTimeMillis() - 60_000;
        Files.setLastModifiedTime(f, FileTime.fromMillis(mtime));
        withCache(dir.resolve("cache"), () -> {
            try {
                FileHashMemo.clearThreadCache();
                FileHashMemo.resetStats();
                String a = FileHashMemo.contentHash(f);
                String b = FileHashMemo.contentHash(f);
                assertThat(a).isEqualTo(b);
                assertThat(FileHashMemo.contentHashInvocations()).isEqualTo(2);
                assertThat(FileHashMemo.contentReads())
                        .as("second call must not re-read")
                        .isEqualTo(1);
                assertThat(FileHashMemo.threadHits()).isEqualTo(1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void contentHash_same_size_mtime_tick_still_sees_rewrite_via_thread_or_rehash(@TempDir Path dir) throws Exception {
        // Same class of bug as CasPrewriter: same-size rewrite within one mtime tick must not
        // serve a stale hex from the disk memo alone. contentHash always re-stats; if mtime+size
        // match disk memo it would be wrong — settle window forces re-hash for fresh files.
        Path f = Files.writeString(dir.resolve("Same.java"), "AAAAAA");
        withCache(dir.resolve("cache"), () -> {
            try {
                FileHashMemo.clearThreadCache();
                String first = FileHashMemo.contentHash(f);
                Files.writeString(f, "BBBBBB"); // same length
                FileHashMemo.clearThreadCache(); // new walk (simulates next poll)
                // Fresh mtime → disk memo ignored → content re-read
                String second = FileHashMemo.contentHash(f);
                assertThat(second).isNotEqualTo(first);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void contentHash_same_size_rewrite_that_restores_prior_mtime_tick_is_not_stale(@TempDir Path dir) throws Exception {
        // TestStamp resource fixture pattern: hash → future mtime rewrite → content rewrite that
        // lands back on the original mtime tick. Thread cache must not serve the first digest.
        Path f = Files.writeString(dir.resolve("fixture.json"), "{\"v\":1}");
        withCache(dir.resolve("cache"), () -> {
            try {
                FileHashMemo.clearThreadCache();
                String first = FileHashMemo.contentHash(f);
                Files.writeString(f, "{\"v\":1}");
                Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() + 10_000));
                assertThat(FileHashMemo.contentHash(f)).isEqualTo(first);
                Files.writeString(f, "{\"v\":2}"); // same length; mtime often == first tick
                String second = FileHashMemo.contentHash(f);
                assertThat(second)
                        .as("same-size rewrite must not reuse a prior tick's self-hash")
                        .isNotEqualTo(first);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void clearAllThreadCaches_drops_another_threads_walk_cache(@TempDir Path dir) throws Exception {
        // The idle boundary (IdleHousekeeping.dropHeapResidue, JK-1942) clears from the
        // housekeeping thread; entries on the immortal pool threads must not survive it.
        Path f = Files.writeString(dir.resolve("Src.java"), "class Src {}");
        long mtime = System.currentTimeMillis() - 60_000;
        Files.setLastModifiedTime(f, FileTime.fromMillis(mtime));
        var pool = Executors.newSingleThreadExecutor();
        try {
            withCache(dir.resolve("cache"), () -> {
                try {
                    Callable<String> onPool = () -> FileHashMemo.contentHash(f);
                    FileHashMemo.resetStats();
                    pool.submit(() -> {
                                FileHashMemo.clearThreadCache();
                                return null;
                            })
                            .get();
                    pool.submit(onPool).get();
                    pool.submit(onPool).get();
                    assertThat(FileHashMemo.threadHits())
                            .as("second same-thread call hits the walk cache")
                            .isEqualTo(1);
                    FileHashMemo.clearAllThreadCaches(); // main thread — cross-thread clear
                    pool.submit(onPool).get();
                    assertThat(FileHashMemo.threadHits())
                            .as("after the idle-boundary clear the pool thread must miss")
                            .isEqualTo(1);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } finally {
            pool.shutdownNow();
        }
    }
}
