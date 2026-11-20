// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.DiskGuard;
import cc.jumpkick.config.JkCacheConfig;
import cc.jumpkick.testing.Sleepers;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The startup caches' retention: a snapshot keeps what was mapped in the last 12 hours, a release
 * keeps everything of its own version, another version's go, and a critical volume takes unused
 * ones oldest first. Every case runs on an injected clock, versions and file times.
 */
class StartupCachesTest {

    private static final long HOUR = Duration.ofHours(1).toMillis();
    private static final long NOW = 1_800_000_000_000L;
    private static final String SNAPSHOT = "0.54.0-SNAPSHOT";
    private static final String RELEASE = "0.54.0";
    private static final StartupCaches.Deleter DELETE = Files::deleteIfExists;

    private int keys;

    /** A worker cache of {@code tool} for {@code version} and JDK tag {@code jdk}, last mapped {@code hoursAgo}. */
    private Path worker(Path state, String tool, String version, String jdk, long hoursAgo, int bytes)
            throws IOException {
        String key = HexFormat.of().toHexDigits((long) ++keys);
        Path dir = Files.createDirectories(state.resolve("aot"));
        return aged(
                Files.write(dir.resolve(tool + "-" + version + "-" + jdk + "-" + key + ".aot"), new byte[bytes]),
                hoursAgo);
    }

    private Path engine(Path state, String version, long hoursAgo) throws IOException {
        String key = HexFormat.of().toHexDigits((long) ++keys);
        Path dir = Files.createDirectories(state.resolve("engine-aot"));
        return aged(Files.write(dir.resolve("engine-" + version + "-" + key + ".aot"), new byte[10]), hoursAgo);
    }

    private static Path aged(Path p, long hoursAgo) throws IOException {
        Files.setLastModifiedTime(p, FileTime.fromMillis(NOW - hoursAgo * HOUR));
        return p;
    }

    private static List<Path> left(Path state) throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path dir : StartupCaches.dirs(state)) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.list(dir)) {
                s.forEach(out::add);
            }
        }
        return out;
    }

    private static Set<Path> held(Path... paths) {
        Set<Path> s = new HashSet<>();
        for (Path p : paths) s.add(p.toAbsolutePath().normalize());
        return s;
    }

    /**
     * Ten installs of one snapshot version two hours apart, each a new key for every tool and a new
     * engine jar: what stays is the live set and what was mapped in the last 12 hours, nothing older.
     * On the base every one of them stayed for 14 days.
     */
    @Test
    void repeated_installs_of_one_snapshot_leave_the_live_set_and_the_last_12_hours(@TempDir Path state)
            throws IOException {
        List<Path> recent = new ArrayList<>();
        List<Path> old = new ArrayList<>();
        for (int install = 0; install < 10; install++) {
            long hoursAgo = 2L * (10 - install) + 1; // 21, 19, … 3 hours ago
            List<Path> set = List.of(
                    worker(state, "java-compiler", SNAPSHOT, "aaaaaaaa", hoursAgo, 10),
                    worker(state, "formatter", SNAPSHOT, "aaaaaaaa", hoursAgo, 10),
                    worker(state, "graph", SNAPSHOT, "aaaaaaaa", hoursAgo, 10),
                    engine(state, SNAPSHOT, hoursAgo));
            (hoursAgo < 12 ? recent : old).addAll(set);
        }
        Path liveWorker = worker(state, "java-compiler", SNAPSHOT, "aaaaaaaa", 30, 10);
        Path liveEngine = engine(state, SNAPSHOT, 30);

        StartupCaches.Report r = StartupCaches.sweep(state, SNAPSHOT, held(liveWorker, liveEngine), NOW, DELETE);

        List<Path> expected = new ArrayList<>(recent);
        expected.add(liveWorker);
        expected.add(liveEngine);
        assertThat(left(state)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(r.deleted()).isEqualTo(old.size());
        assertThat(r.skipped()).isZero();
    }

    @Test
    void a_cache_in_use_survives_whatever_its_age(@TempDir Path state) throws IOException {
        Path mapped = worker(state, "kotlinc", SNAPSHOT, "aaaaaaaa", 24 * 30, 10);
        Path unmapped = worker(state, "kotlinc", SNAPSHOT, "aaaaaaaa", 13, 10);
        Path recorded = worker(state, "kotlinc", SNAPSHOT, "aaaaaaaa", 0, 10);

        StartupCaches.sweep(state, SNAPSHOT, held(mapped), NOW, DELETE);

        assertThat(mapped).exists();
        assertThat(unmapped).doesNotExist();
        assertThat(recorded)
                .as("another engine of this home recorded it seconds ago: its mtime is fresh")
                .exists();
    }

    /** A release deletes nothing by age, not even another JDK's cache; on the base both went after 14 days. */
    @Test
    void a_release_keeps_its_caches_unmapped_for_60_days_on_any_jdk(@TempDir Path state) throws IOException {
        Path sameJdk = worker(state, "java-compiler", RELEASE, "aaaaaaaa", 24 * 60, 10);
        Path otherJdk = worker(state, "java-compiler", RELEASE, "bbbbbbbb", 24 * 60, 10);
        Path sibling = worker(state, "java-compiler", RELEASE, "aaaaaaaa", 24 * 61, 10);
        Path ownEngine = engine(state, RELEASE, 24 * 60);

        StartupCaches.Report r = StartupCaches.sweep(state, RELEASE, Set.of(), NOW, DELETE);

        assertThat(r.deleted()).isZero();
        assertThat(left(state)).containsExactlyInAnyOrder(sameJdk, otherJdk, sibling, ownEngine);
    }

    /** A new version deletes every cache of the old one, fresh or not: snapshot to release and back. */
    @Test
    void an_engine_of_another_version_deletes_all_of_the_old_versions(@TempDir Path state) throws IOException {
        Path snapWorker = worker(state, "graph", SNAPSHOT, "aaaaaaaa", 0, 10);
        Path snapEngine = engine(state, SNAPSHOT, 0);
        Path olderRelease = worker(state, "graph", "0.53.0", "aaaaaaaa", 0, 10);
        Path legacy = Files.writeString(state.resolve("aot").resolve("kotlinc-0.13.8-3d5aa37c6f2e823d.aot"), "x");
        Path notACache = Files.writeString(state.resolve("aot").resolve("aot.toml"), "x");

        StartupCaches.sweep(state, RELEASE, Set.of(), NOW, DELETE);

        assertThat(left(state)).containsExactly(notACache);
        assertThat(snapWorker).doesNotExist();
        assertThat(snapEngine).doesNotExist();
        assertThat(olderRelease).doesNotExist();
        assertThat(legacy).doesNotExist();

        Path release = worker(state, "graph", RELEASE, "aaaaaaaa", 0, 10);
        Path releaseEngine = engine(state, RELEASE, 0);
        StartupCaches.sweep(state, "0.55.0-SNAPSHOT", Set.of(), NOW, DELETE);
        assertThat(release).doesNotExist();
        assertThat(releaseEngine).doesNotExist();
    }

    @Test
    void a_trainer_temp_goes_after_a_day_and_a_live_one_stays(@TempDir Path state) throws IOException {
        Path dir = Files.createDirectories(state.resolve("aot"));
        Path dead = aged(
                Files.writeString(dir.resolve("graph-" + RELEASE + "-aaaaaaaa-0000000000000001.aot.tmp-7"), "x"), 25);
        Path live = aged(
                Files.writeString(dir.resolve("graph-" + RELEASE + "-aaaaaaaa-0000000000000002.aot.tmp-8"), "x"), 1);

        StartupCaches.sweep(state, RELEASE, Set.of(), NOW, DELETE);

        assertThat(dead).doesNotExist();
        assertThat(live).exists();
    }

    /** Under the disk guard: oldest unmapped first, the mapped one spared, and no more than the goal. */
    @Test
    void the_threshold_pass_deletes_unmapped_caches_oldest_first_and_spares_mapped_ones(@TempDir Path state)
            throws IOException {
        Path oldestMapped = worker(state, "kotlinc", RELEASE, "aaaaaaaa", 100, 100);
        Path oldest = worker(state, "kotlinc", RELEASE, "aaaaaaaa", 90, 100);
        Path oldEngine = engine(state, RELEASE, 80);
        Path middle = worker(state, "formatter", RELEASE, "aaaaaaaa", 50, 100);
        Path newest = worker(state, "graph", RELEASE, "bbbbbbbb", 1, 100);

        StartupCaches.Report r = StartupCaches.reclaim(state, held(oldestMapped), 150, false, DELETE);

        assertThat(oldestMapped).exists();
        assertThat(oldest).doesNotExist();
        assertThat(oldEngine).doesNotExist();
        assertThat(middle).as("100 + 10 bytes were short of 150").doesNotExist();
        assertThat(newest).as("the goal was met").exists();
        assertThat(r.deleted()).isEqualTo(3);
        assertThat(r.freedBytes()).isEqualTo(210);

        StartupCaches.reclaim(state, held(oldestMapped), Long.MAX_VALUE, false, DELETE);
        assertThat(left(state)).containsExactly(oldestMapped);
    }

    @Test
    void the_goal_is_what_the_volume_lacks_to_be_off_the_critical_line() {
        long gib = 1L << 30;
        assertThat(StartupCaches.shortfall(null)).isZero();
        assertThat(StartupCaches.shortfall(new JkCacheConfig.DiskSpace(228 * gib, 30 * gib)))
                .isZero();
        var justUnder = new JkCacheConfig.DiskSpace(228 * gib, (long) (228 * gib * 0.099));
        assertThat(DiskGuard.critical(justUnder)).isTrue();
        assertThat(StartupCaches.shortfall(justUnder))
                .isEqualTo((long) Math.ceil(228 * gib * 0.10) - justUnder.freeBytes());
        assertThat(StartupCaches.shortfall(new JkCacheConfig.DiskSpace(10 * gib, gib)))
                .as("a small disk: the 2 GiB floor decides")
                .isEqualTo(gib);
    }

    /** A delete the platform refuses (Windows, another process maps it) is counted and passed over. */
    @Test
    void a_file_that_cannot_be_deleted_is_skipped_without_failing_anything(@TempDir Path state) throws IOException {
        Path stuck = worker(state, "graph", "0.53.0", "aaaaaaaa", 0, 10);
        Path other = worker(state, "formatter", "0.53.0", "aaaaaaaa", 0, 10);
        StartupCaches.Deleter refusing = p -> {
            if (p.equals(stuck)) throw new AccessDeniedException(p.toString());
            Files.deleteIfExists(p);
        };

        StartupCaches.Report swept = StartupCaches.sweep(state, RELEASE, Set.of(), NOW, refusing);
        assertThat(swept.skipped()).isEqualTo(1);
        assertThat(swept.deleted()).isEqualTo(1);
        assertThat(stuck).exists();
        assertThat(other).doesNotExist();

        StartupCaches.Report reclaimed = StartupCaches.reclaim(state, Set.of(), Long.MAX_VALUE, false, refusing);
        assertThat(reclaimed.skipped()).isEqualTo(1);
        assertThat(reclaimed.freedBytes()).isZero();
    }

    @Test
    void a_dry_run_counts_and_deletes_nothing(@TempDir Path state) throws IOException {
        Path a = worker(state, "graph", RELEASE, "aaaaaaaa", 5, 40);
        Path b = engine(state, RELEASE, 5);

        StartupCaches.Report r = StartupCaches.reclaim(state, Set.of(), Long.MAX_VALUE, true, DELETE);

        assertThat(r.deleted()).isEqualTo(2);
        assertThat(r.freedBytes()).isEqualTo(50);
        assertThat(a).exists();
        assertThat(b).exists();
        assertThat(StartupCaches.usage(state)).containsExactly(2L, 50L);
    }

    /** {@code jk storage clean}: every startup cache of this home the engine does not use, any version or age. */
    @Test
    void storage_clean_takes_every_cache_this_engine_does_not_use(@TempDir Path state) throws IOException {
        Path fresh = worker(state, "graph", RELEASE, "aaaaaaaa", 0, 10);
        Path engineCache = engine(state, SNAPSHOT, 0);
        Path notACache = Files.writeString(state.resolve("aot").resolve("aot.toml"), "x");
        System.setProperty("jk.env.JK_STATE_DIR", state.toString());
        StartupCaches.Report r;
        try {
            r = StartupCaches.clear(false);
        } finally {
            System.clearProperty("jk.env.JK_STATE_DIR");
        }
        assertThat(r.deleted()).isEqualTo(2);
        assertThat(fresh).doesNotExist();
        assertThat(engineCache).doesNotExist();
        assertThat(notACache).exists();
    }

    /** In use is read off command lines: the flag a worker maps its cache with, nothing else. */
    @Test
    void a_command_line_names_the_caches_it_maps(@TempDir Path state) {
        Path cache = state.resolve("aot").resolve("graph-0.54.0-aaaaaaaa-0123456789abcdef.aot");
        assertThat(StartupCaches.mappedBy(List.of("java", "-XX:AOTCache=" + cache, "-Xlog:aot=off", "-cp", "w.jar")))
                .containsExactly(cache.toAbsolutePath().normalize());
        assertThat(StartupCaches.mappedBy(List.of("java", "-XX:AOTCacheOutput=" + cache, "-Dx=-XX:AOTCache=y")))
                .as("a recording's output and a mention inside another flag map nothing")
                .isEmpty();
    }

    /** A cache a live process maps is in use, whichever process it is. */
    @Test
    void a_cache_a_live_process_maps_is_in_use(@TempDir Path state) throws Exception {
        Assumptions.assumeTrue(
                ProcessHandle.current().info().arguments().isPresent(),
                "this platform shows no process arguments; a mapped file there refuses its delete instead");
        Path cache = worker(state, "graph", SNAPSHOT, "aaaaaaaa", 30, 10);
        Process p = Sleepers.sleeperMentioning(60, "-XX:AOTCache=" + cache).start();
        try {
            assertThat(StartupCaches.inUse()).contains(cache.toAbsolutePath().normalize());
            StartupCaches.sweep(state, SNAPSHOT, StartupCaches.inUse(), NOW, DELETE);
            assertThat(cache).exists();
        } finally {
            p.destroyForcibly().waitFor();
        }
        assertThat(StartupCaches.inUse()).doesNotContain(cache.toAbsolutePath().normalize());
    }
}
