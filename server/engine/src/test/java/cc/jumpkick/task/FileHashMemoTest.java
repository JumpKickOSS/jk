// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileHashMemoTest {

    /** Run {@code body} with the session cache rooted at {@code cache}, on a memo that starts cold. */
    private static void withCache(Path cache, Runnable body) {
        SessionContext.runWhere(Session.defaults().withCacheDir(cache), () -> {
            FileHashMemo.reset();
            FileHashMemo.resetStats();
            body.run();
        });
    }

    /** Push {@code f}'s mtime a minute into the past, out of the settle window. */
    private static void settle(Path f) throws Exception {
        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() - 60_000));
    }

    @Test
    void a_settled_file_is_hashed_once(@TempDir Path dir) throws Exception {
        Path f = Files.writeString(dir.resolve("Src.java"), "class Src {}");
        settle(f);
        withCache(dir.resolve("cache"), () -> {
            try {
                String a = FileHashMemo.contentHash(f);
                String b = FileHashMemo.contentHash(f);
                assertThat(a).isEqualTo(b);
                assertThat(FileHashMemo.contentHashInvocations()).isEqualTo(2);
                assertThat(FileHashMemo.contentReads())
                        .as("second call must not re-read the bytes")
                        .isEqualTo(1);
                assertThat(FileHashMemo.memoHits()).isEqualTo(1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * The claim the whole store exists for: a hit costs the caller's stat and nothing else. Proven
     * by taking the file away — anything that reached for the filesystem would fail instead of
     * answering. A file-backed entry could not pass this, which is why it cost more on NTFS than
     * the hash it replaced.
     */
    @Test
    void a_hit_touches_no_filesystem_beyond_the_caller_stat(@TempDir Path dir) throws Exception {
        Path f = Files.writeString(dir.resolve("Src.java"), "class Src {}");
        settle(f);
        BasicFileAttributes attrs = Files.readAttributes(f, BasicFileAttributes.class);
        withCache(dir.resolve("cache"), () -> {
            try {
                String hex = FileHashMemo.contentHash(f, attrs);
                Files.delete(f);
                assertThat(FileHashMemo.contentHash(f, attrs)).isEqualTo(hex);
                assertThat(FileHashMemo.contentReads()).isEqualTo(1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void a_stat_change_invalidates_the_entry(@TempDir Path dir) throws Exception {
        Path f = Files.writeString(dir.resolve("Src.java"), "class Src {}");
        settle(f);
        withCache(dir.resolve("cache"), () -> {
            try {
                String first = FileHashMemo.contentHash(f);
                Files.writeString(f, "class Src { int longer; }"); // new size and mtime
                settle(f);
                assertThat(FileHashMemo.contentHash(f)).isNotEqualTo(first);
                assertThat(FileHashMemo.contentReads()).isEqualTo(2);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void a_freshly_written_file_is_never_memoized(@TempDir Path dir) throws Exception {
        // Filesystem mtimes are truncated: a file modified "just now" could change again within
        // the same tick without the stat noticing. Inside the settle window the memo stands aside.
        Path f = Files.writeString(dir.resolve("Same.java"), "AAAAAA");
        withCache(dir.resolve("cache"), () -> {
            try {
                String first = FileHashMemo.contentHash(f);
                Files.writeString(f, "BBBBBB"); // same length
                assertThat(FileHashMemo.contentHash(f))
                        .as("a same-size rewrite inside the settle window must re-hash")
                        .isNotEqualTo(first);
                assertThat(FileHashMemo.memoHits()).isZero();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void a_restore_seed_is_trusted_without_waiting_out_settle(@TempDir Path dir) throws Exception {
        // What keeps stamps and package keys cheap after `jk clean` + an action-cache restore:
        // the bytes are known, so the digest is usable immediately rather than 2s later.
        Path f = Files.writeString(dir.resolve("restored.jar"), "AA");
        String known = cc.jumpkick.util.Hashing.sha256Hex(f);
        withCache(dir.resolve("cache"), () -> {
            try {
                FileHashMemo.rememberContent(f, known);
                assertThat(FileHashMemo.contentHash(f)).isEqualTo(known);
                assertThat(FileHashMemo.contentReads())
                        .as("a seeded digest is not re-read, settle window or not")
                        .isZero();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void a_seed_is_void_once_the_nanosecond_stamp_moves(@TempDir Path dir) throws Exception {
        // Compilers rewrite restored class files in place, and such a rewrite can land inside the
        // same millisecond tick. The seed's provenance is the nanosecond stamp, not the tick.
        Path f = Files.writeString(dir.resolve("restored.class"), "AA");
        long second = Instant.now().minusSeconds(60).getEpochSecond();
        Files.setLastModifiedTime(f, FileTime.from(Instant.ofEpochSecond(second, 400_000)));
        long before = Files.getLastModifiedTime(f).to(TimeUnit.NANOSECONDS);
        Files.setLastModifiedTime(f, FileTime.from(Instant.ofEpochSecond(second, 900_000)));
        long after = Files.getLastModifiedTime(f).to(TimeUnit.NANOSECONDS);
        assumeTrue(before != after, "filesystem keeps sub-millisecond mtime precision");

        withCache(dir.resolve("cache"), () -> {
            try {
                FileHashMemo.rememberContent(f, "0".repeat(64));
                Files.setLastModifiedTime(f, FileTime.from(Instant.ofEpochSecond(second, 400_000)));
                assertThat(FileHashMemo.contentHash(f))
                        .as("the stamp moved, so the seed is not the file's digest any more")
                        .isNotEqualTo("0".repeat(64));
                assertThat(FileHashMemo.contentReads()).isEqualTo(1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void the_store_is_one_file_and_survives_a_restart(@TempDir Path dir) throws Exception {
        Path f = Files.writeString(dir.resolve("Src.java"), "class Src {}");
        settle(f);
        Path cache = dir.resolve("cache");
        String[] first = new String[1];
        withCache(cache, () -> {
            try {
                first[0] = FileHashMemo.contentHash(f);
                FileHashMemo.flush();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Path tier = cache.resolve("hash-memo");
        try (var walk = Files.walk(tier)) {
            assertThat(walk.filter(Files::isRegularFile).map(Path::getFileName).map(Path::toString))
                    .as("one entry per path would be one file per path; the store is one file")
                    .containsExactly("memo.v1");
        }

        // A cold process: nothing in memory, everything in that file.
        withCache(cache, () -> {
            try {
                assertThat(FileHashMemo.contentHash(f)).isEqualTo(first[0]);
                assertThat(FileHashMemo.contentReads())
                        .as("the reloaded store answers without touching the bytes")
                        .isZero();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void an_unreadable_store_fails_open(@TempDir Path dir) throws Exception {
        Path f = Files.writeString(dir.resolve("Src.java"), "class Src {}");
        settle(f);
        Path cache = dir.resolve("cache");
        Files.createDirectories(cache.resolve("hash-memo"));
        Files.writeString(cache.resolve("hash-memo/memo.v1"), "not\na\0store\nat all\n");
        withCache(cache, () -> {
            try {
                assertThat(FileHashMemo.contentHash(f)).isEqualTo(cc.jumpkick.util.Hashing.sha256Hex(f));
                assertThat(FileHashMemo.contentReads()).isEqualTo(1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
