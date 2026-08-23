// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code hash-memo} cap, which cannot rank by age: {@link FileHashMemo} rewrites an entry
 * whenever its file changes, so the oldest entries are the most stable ones. It takes the entries
 * whose source is gone instead, and only falls back to age for whatever is still over.
 *
 * <p>Every entry here is written by {@link FileHashMemo} itself rather than by hand, so the record
 * shape the pass reads cannot drift from the one the memo writes.
 */
class CacheRetentionHashMemoTest {

    private static final long SETTLED = Duration.ofMinutes(5).toMillis();
    private static final long PAST_GRACE = Duration.ofHours(6).toMillis();

    /** Bind the cap at {@code max} without seeding 32,768 entries to do it. */
    private static Map<CacheTier, Bound> capAt(int max) {
        return Map.of(
                CacheTier.HASH_MEMO, Bound.files(null, Bound.countCap(max, Bound.VictimRule.SUPERSEDED_THEN_OLDEST)));
    }

    @Test
    void the_entries_whose_source_is_gone_go_first(@TempDir Path dir) throws IOException {
        Path cache = dir.resolve("cache");
        Path src = Files.createDirectories(dir.resolve("src"));
        Path live = source(src, "Live.java");
        Path deleted = source(src, "Deleted.java");
        Path wiped = source(dir.resolve("target/classes"), "Wiped.class");
        memoize(cache, live, deleted, wiped);
        Files.delete(deleted);
        deleteTree(dir.resolve("target")); // what `jk clean` does to most of the tier
        Map<String, Path> entries = entriesBySource(cache);
        // The survivor is also the oldest, so ranking by age would have taken the wrong one.
        backdate(entries.get(live.toString()), PAST_GRACE * 2);

        CacheRetention.sweep(cache, new Cas(cache), Set.of(), false, CacheRetention.Probe.REAL, capAt(2));

        assertThat(entries.get(deleted.toString())).doesNotExist();
        assertThat(entries.get(wiped.toString())).doesNotExist();
        assertThat(entries.get(live.toString())).exists();
    }

    /** A path with nothing left above it is a volume that is not mounted, not a deleted file. */
    @Test
    void an_entry_whose_whole_ancestor_chain_is_missing_is_kept(@TempDir Path dir) throws IOException {
        Path cache = dir.resolve("cache");
        Path src = Files.createDirectories(dir.resolve("src"));
        Path live = source(src, "Live.java");
        Path deleted = source(src, "Deleted.java");
        Path unmounted = Path.of("/jk-no-such-volume/proj/Src.java").toAbsolutePath();
        memoize(cache, live, deleted);
        SessionContext.runWhere(
                Session.defaults().withCacheDir(cache),
                () -> FileHashMemo.store(unmounted, 12L, System.currentTimeMillis() - SETTLED, "deadbeef"));
        ageEntries(cache); // or it would survive on the grace window rather than on the guard
        Files.delete(deleted);
        Map<String, Path> entries = entriesBySource(cache);

        // Three entries against a cap of two: supersession runs, and must find exactly one victim.
        CacheRetention.sweep(cache, new Cas(cache), Set.of(), false, CacheRetention.Probe.REAL, capAt(2));

        assertThat(entries.get(deleted.toString())).doesNotExist();
        assertThat(entries.get(unmounted.toString())).exists();
        assertThat(entries.get(live.toString())).exists();
    }

    /** Over the cap and with no dead entries left, age is all that is left to go on. */
    @Test
    void what_is_still_over_the_cap_goes_by_age(@TempDir Path dir) throws IOException {
        Path cache = dir.resolve("cache");
        Path src = Files.createDirectories(dir.resolve("src"));
        Path oldest = source(src, "Oldest.java");
        Path newer = source(src, "Newer.java");
        memoize(cache, oldest, newer);
        Map<String, Path> entries = entriesBySource(cache);
        backdate(entries.get(oldest.toString()), PAST_GRACE * 2);
        backdate(entries.get(newer.toString()), PAST_GRACE);

        CacheRetention.sweep(cache, new Cas(cache), Set.of(), false, CacheRetention.Probe.REAL, capAt(1));

        assertThat(entries.get(oldest.toString())).doesNotExist();
        assertThat(entries.get(newer.toString())).exists();
    }

    /**
     * The common pass, and the reason the cap can afford an exact rule at all: below it, the tier
     * is counted by name and no entry is opened or stat'ed. Enumeration itself — one {@code
     * readdir} per shard — is the floor and is not what this asserts.
     */
    @Test
    void below_the_cap_no_entry_is_read_or_stat_ed(@TempDir Path dir) throws IOException {
        Path cache = dir.resolve("cache");
        Path src = Files.createDirectories(dir.resolve("src"));
        Path gone = source(src, "Gone.java");
        memoize(cache, gone, source(src, "Kept.java"));
        Files.delete(gone);
        CountingProbe probe = new CountingProbe();

        CacheRetention.sweep(cache, new Cas(cache), Set.of(), false, probe, capAt(64));

        assertThat(probe.questions())
                .as("questions asked about individual entries")
                .isZero();
        assertThat(entriesBySource(cache)).hasSize(2);
    }

    // ---------------------------------------------------------------- fixtures

    /** A source file old enough for the memo to trust its stat. */
    private static Path source(Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        Path f = Files.writeString(dir.resolve(name), "content of " + name, StandardCharsets.UTF_8);
        backdate(f, SETTLED);
        return f;
    }

    /** Memoize each file through the real store, then age the entries past the sweep's grace. */
    private static void memoize(Path cache, Path... files) throws IOException {
        SessionContext.runWhere(Session.defaults().withCacheDir(cache), () -> {
            for (Path f : files) {
                try {
                    FileHashMemo.store(
                            f, Files.size(f), Files.getLastModifiedTime(f).toMillis(), "deadbeef");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });
        ageEntries(cache);
    }

    /** Put every entry past {@link Sweep#MIN_AGE_FOR_SWEEP}, so the grace window decides nothing. */
    private static void ageEntries(Path cache) throws IOException {
        for (Path entry : entriesBySource(cache).values()) backdate(entry, PAST_GRACE);
    }

    /** Every memo entry, keyed by the source path it records on its last line. */
    private static Map<String, Path> entriesBySource(Path cache) throws IOException {
        Map<String, Path> bySource = new HashMap<>();
        Path tier = cache.resolve("hash-memo");
        if (!Files.isDirectory(tier)) return bySource;
        try (Stream<Path> walk = Files.walk(tier)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                String record = Files.readString(p, StandardCharsets.UTF_8);
                bySource.put(record.substring(record.lastIndexOf('\n') + 1), p);
            }
        }
        return bySource;
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    private static void backdate(Path p, long ageMillis) throws IOException {
        Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis() - ageMillis));
    }
}
