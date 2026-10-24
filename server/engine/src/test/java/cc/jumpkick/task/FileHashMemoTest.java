// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Settled;
import cc.jumpkick.host.StatIdentity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileHashMemoTest {

    /** Long enough after its writes that every file this test wrote reads as {@link Settled}. */
    private static final Duration SETTLED = Duration.ofSeconds(5);

    @AfterEach
    void realClock() {
        Settled.aheadForTests(null);
    }

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
        Settled.aheadForTests(SETTLED);
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
    /**
     * {@code touch -r}, {@code cp -p}, {@code rsync -a}: different bytes behind the size and mtime
     * the memo recorded. The change time moves, so the memo reads the file again.
     */
    @Test
    void a_same_size_rewrite_that_restores_the_mtime_is_read_again(@TempDir Path dir) throws Exception {
        Path f = Files.writeString(dir.resolve("Greet.java"), "hello");
        settle(f);
        FileTime mtime = Files.getLastModifiedTime(f);
        withCache(dir.resolve("cache"), () -> {
            try {
                String before = FileHashMemo.contentHash(f);
                Thread.sleep(20);
                Files.writeString(f, "HELLO");
                Files.setLastModifiedTime(f, mtime);

                assertThat(FileHashMemo.contentHash(f))
                        .isEqualTo(Hashing.sha256Hex(f))
                        .isNotEqualTo(before);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * A caller hashing against an identity it read before the file changed gets the bytes there now,
     * but those bytes are never filed under the stale identity: a later caller holding that identity
     * would be handed them for a file it never saw.
     */
    @Test
    void a_hash_is_not_recorded_under_an_identity_the_file_no_longer_has(@TempDir Path dir) throws Exception {
        Path f = Files.writeString(dir.resolve("Src.java"), "class Src {}");
        settle(f);
        StatIdentity stale = StatIdentity.of(f);
        Thread.sleep(20);
        Files.writeString(f, "class Src { int edited; }");
        settle(f);
        withCache(dir.resolve("cache"), () -> {
            try {
                FileHashMemo.contentHash(f, stale);
                FileHashMemo.contentHash(f, stale);
                assertThat(FileHashMemo.contentReads())
                        .as("nothing was recorded under the stale identity")
                        .isEqualTo(2);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void a_hit_touches_no_filesystem_beyond_the_caller_stat(@TempDir Path dir) throws Exception {
        Settled.aheadForTests(SETTLED);
        Path f = Files.writeString(dir.resolve("Src.java"), "class Src {}");
        settle(f);
        BasicFileAttributes attrs = Files.readAttributes(f, BasicFileAttributes.class);
        // Windows' stat carries no file key: the identity reads the file id through a handle.
        assumeTrue(attrs.fileKey() != null, "the walk's stat carries the file key");
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
        String known = Hashing.sha256Hex(f);
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
        Settled.aheadForTests(SETTLED);
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

    /** A flush writes what changed since the last one, after what is already there. */
    @Test
    void a_flush_appends_only_what_changed(@TempDir Path dir) throws Exception {
        Settled.aheadForTests(SETTLED);
        Path a = Files.writeString(dir.resolve("A.java"), "class A {}");
        Path b = Files.writeString(dir.resolve("B.java"), "class B {}");
        settle(a);
        settle(b);
        Path cache = dir.resolve("cache");
        Path log = cache.resolve("hash-memo/memo.v1");
        String[] before = new String[1];
        withCache(cache, () -> {
            try {
                FileHashMemo.contentHash(a);
                FileHashMemo.flush();
                before[0] = Files.readString(log);
                FileHashMemo.contentHash(b);
                FileHashMemo.flush();
                String after = Files.readString(log);
                assertThat(after).startsWith(before[0]);
                assertThat(after.substring(before[0].length()).split("\n")).hasSize(1);
                assertThat(after.substring(before[0].length()))
                        .startsWith(b.toAbsolutePath().toString());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * A build that only re-confirms what the store holds writes nothing. Every no-op build used to
     * rewrite the whole store, megabytes for one stale entry.
     */
    @Test
    void the_same_fingerprint_again_leaves_the_store_unwritten(@TempDir Path dir) throws Exception {
        Settled.aheadForTests(SETTLED);
        Path f = Files.writeString(dir.resolve("Src.java"), "class Src {}");
        settle(f);
        Path cache = dir.resolve("cache");
        Path log = cache.resolve("hash-memo/memo.v1");
        withCache(cache, () -> {
            try {
                String sha = FileHashMemo.contentHash(f);
                FileHashMemo.flush();
                FileTime written = Files.getLastModifiedTime(log);
                Files.setLastModifiedTime(log, FileTime.fromMillis(written.toMillis() - 60_000));
                FileTime sentinel = Files.getLastModifiedTime(log);

                FileHashMemo.reset();
                FileHashMemo.contentHash(f);
                FileHashMemo.rememberContent(f, sha);
                FileHashMemo.flush();

                assertThat(Files.getLastModifiedTime(log)).isEqualTo(sentinel);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** An append cut short is not an entry, and the next flush starts the log clean. */
    @Test
    void a_torn_tail_is_never_read_as_a_fingerprint(@TempDir Path dir) throws Exception {
        Settled.aheadForTests(SETTLED);
        Path f = Files.writeString(dir.resolve("Src.java"), "class Src {}");
        settle(f);
        Path abs = f.toAbsolutePath().normalize();
        BasicFileAttributes attrs = Files.readAttributes(abs, BasicFileAttributes.class);
        String real = Hashing.sha256Hex(f);
        Path cache = dir.resolve("cache");
        Path log = Files.createDirectories(cache.resolve("hash-memo")).resolve("memo.v1");
        // The same path and stat, the token cut mid-way: a crash during an append.
        Files.writeString(
                log,
                abs + "\0" + attrs.size() + "\0" + attrs.lastModifiedTime().toMillis() + "\0-1\0"
                        + real.substring(0, 40));
        withCache(cache, () -> {
            try {
                assertThat(FileHashMemo.contentHash(f)).isEqualTo(real);
                assertThat(FileHashMemo.contentReads())
                        .as("the torn line answered nothing")
                        .isEqualTo(1);
                FileHashMemo.flush();
                String clean = Files.readString(log);
                assertThat(clean).endsWith("\n").contains(real).doesNotContain(real.substring(0, 40) + "\n");
                assertThat(clean.split("\n")).hasSize(1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void only_a_sha256_is_a_token() {
        assertThat(FileHashMemo.isSha256("a".repeat(64))).isTrue();
        assertThat(FileHashMemo.isSha256("a".repeat(63))).isFalse();
        assertThat(FileHashMemo.isSha256("A".repeat(64))).isFalse();
        assertThat(FileHashMemo.isSha256("g".repeat(64))).isFalse();
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
                assertThat(FileHashMemo.contentHash(f)).isEqualTo(Hashing.sha256Hex(f));
                assertThat(FileHashMemo.contentReads()).isEqualTo(1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Eviction ranks one snapshot of the use ticks, oldest first: the extremes of the range and a
     * tick shared by several entries order totally, the tie in key order.
     */
    @Test
    void eviction_ranks_a_snapshot_of_use_ticks_and_breaks_a_tie_on_the_key() {
        Map<String, FileHashMemo.Entry> entries = new HashMap<>();
        long[] ticks = {5, Long.MIN_VALUE, 5, 0, Long.MAX_VALUE, -1, 5, 3};
        for (int i = 0; i < ticks.length; i++) {
            FileHashMemo.Entry e = new FileHashMemo.Entry(new StatIdentity(1, 1, 0, ""), false, "t" + i);
            e.used = ticks[i];
            entries.put("k" + i, e);
        }
        assertThat(FileHashMemo.victims(entries, 3))
                .extracting(Map.Entry::getKey)
                .containsExactly("k1", "k5", "k3", "k7", "k0");
        assertThat(FileHashMemo.victims(entries, ticks.length)).isEmpty();
        assertThat(FileHashMemo.victims(entries, ticks.length + 1)).isEmpty();
    }

    /**
     * Hits move the use ticks while an eviction sorts. The ranking reads each tick once, so no sort
     * ever sees a key change under it; a ranking on the live field is the one TimSort refuses.
     */
    @Test
    void hits_that_move_use_ticks_during_an_eviction_never_break_its_order() throws Exception {
        Map<String, FileHashMemo.Entry> entries = new ConcurrentHashMap<>();
        List<FileHashMemo.Entry> all = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            FileHashMemo.Entry e = new FileHashMemo.Entry(new StatIdentity(1, 1, 0, ""), false, "t");
            entries.put("k" + i, e);
            all.add(e);
        }
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong tick = new AtomicLong();
        List<Thread> hitters = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            Thread h = new Thread(() -> {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                while (!stop.get()) all.get(rnd.nextInt(all.size())).used = tick.incrementAndGet();
            });
            h.setDaemon(true);
            h.start();
            hitters.add(h);
        }
        try {
            for (int round = 0; round < 40; round++) {
                assertThat(FileHashMemo.victims(entries, 10_000)).hasSize(10_000);
            }
        } finally {
            stop.set(true);
            for (Thread h : hitters) h.join();
        }
    }

    /**
     * Every cache root has a store of its own, each bounded on its own, so the number of roots in
     * memory is bounded too: a third root drops the coldest store, persisted first, and a later
     * build in that root reloads it rather than re-hashing.
     */
    @Test
    void a_third_cache_root_persists_and_drops_the_coldest_store(@TempDir Path dir) throws Exception {
        Settled.aheadForTests(SETTLED);
        Path f = Files.writeString(dir.resolve("Src.java"), "class Src {}");
        settle(f);
        Path first = dir.resolve("cache-1");
        FileHashMemo.reset();
        FileHashMemo.resetStats();
        for (Path cache : List.of(first, dir.resolve("cache-2"), dir.resolve("cache-3"))) {
            SessionContext.runWhere(Session.defaults().withCacheDir(cache), () -> {
                try {
                    FileHashMemo.contentHash(f);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        assertThat(FileHashMemo.loadedStores()).isEqualTo(CacheRootStores.MAX_LOADED);
        assertThat(first.resolve("hash-memo/memo.v1"))
                .as("the dropped store was persisted")
                .exists();
        assertThat(FileHashMemo.contentReads()).isEqualTo(3);

        SessionContext.runWhere(Session.defaults().withCacheDir(first), () -> {
            try {
                FileHashMemo.contentHash(f);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(FileHashMemo.contentReads()).as("the reloaded store answers").isEqualTo(3);
        assertThat(FileHashMemo.loadedStores()).isEqualTo(CacheRootStores.MAX_LOADED);
    }
}
