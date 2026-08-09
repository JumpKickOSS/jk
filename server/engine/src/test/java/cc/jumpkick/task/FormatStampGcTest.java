// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FormatStampGcTest {

    @Test
    void resolveMaxFiles_defaults_to_512k() {
        assertThat(FormatStampGc.resolveMaxFiles(k -> null)).isEqualTo(FormatStampGc.DEFAULT_MAX_FILES);
        assertThat(FormatStampGc.resolveMaxFiles(Map.of("CI", "")::get)).isEqualTo(FormatStampGc.DEFAULT_MAX_FILES);
        assertThat(FormatStampGc.resolveMaxFiles(Map.of("CI", "false")::get))
                .isEqualTo(FormatStampGc.DEFAULT_MAX_FILES);
    }

    @Test
    void resolveMaxFiles_ci_true_or_1_is_1m() {
        assertThat(FormatStampGc.resolveMaxFiles(Map.of("CI", "1")::get)).isEqualTo(FormatStampGc.CI_MAX_FILES);
        assertThat(FormatStampGc.resolveMaxFiles(Map.of("CI", "true")::get)).isEqualTo(FormatStampGc.CI_MAX_FILES);
        assertThat(FormatStampGc.resolveMaxFiles(Map.of("CI", "TRUE")::get)).isEqualTo(FormatStampGc.CI_MAX_FILES);
    }

    @Test
    void absent_dir_is_no_op(@TempDir Path cache) throws IOException {
        var r = FormatStampGc.sweep(cache, FormatStampGc.DEFAULT_TTL, 100, false);
        assertThat(r.deleted()).isZero();
        assertThat(r.deletedByAge()).isZero();
        assertThat(r.deletedByCap()).isZero();
    }

    @Test
    void stamps_older_than_ttl_are_deleted(@TempDir Path cache) throws IOException {
        Path stale = stamp(cache, "aa", "bb", "stale");
        Path fresh = stamp(cache, "cc", "dd", "fresh");
        backdate(stale, 8);
        // fresh: now

        var r = FormatStampGc.sweep(cache, Duration.ofDays(7), 0, false);

        assertThat(r.deletedByAge()).isEqualTo(1);
        assertThat(r.deletedByCap()).isZero();
        assertThat(Files.exists(stale)).isFalse();
        assertThat(Files.exists(fresh)).isTrue();
    }

    @Test
    void count_cap_evicts_oldest_mtime_first(@TempDir Path cache) throws IOException {
        Path old = stamp(cache, "11", "22", "old");
        Path mid = stamp(cache, "33", "44", "mid");
        Path hot = stamp(cache, "55", "66", "hot");
        // Distinct mtimes so LRU order is deterministic (all within TTL).
        long now = System.currentTimeMillis();
        Files.setLastModifiedTime(
                old, FileTime.fromMillis(now - Duration.ofDays(3).toMillis()));
        Files.setLastModifiedTime(
                mid, FileTime.fromMillis(now - Duration.ofDays(2).toMillis()));
        Files.setLastModifiedTime(
                hot, FileTime.fromMillis(now - Duration.ofHours(1).toMillis()));

        var r = FormatStampGc.sweep(cache, Duration.ofDays(7), 2, false);

        assertThat(r.deletedByAge()).isZero();
        assertThat(r.deletedByCap()).isEqualTo(1);
        assertThat(Files.exists(old)).isFalse();
        assertThat(Files.exists(mid)).isTrue();
        assertThat(Files.exists(hot)).isTrue();
    }

    @Test
    void age_pass_runs_before_cap(@TempDir Path cache) throws IOException {
        Path ancient = stamp(cache, "aa", "11", "ancient");
        Path a = stamp(cache, "bb", "22", "a");
        Path b = stamp(cache, "cc", "33", "b");
        Path c = stamp(cache, "dd", "44", "c");
        backdate(ancient, 10);
        long now = System.currentTimeMillis();
        Files.setLastModifiedTime(a, FileTime.fromMillis(now - 3000));
        Files.setLastModifiedTime(b, FileTime.fromMillis(now - 2000));
        Files.setLastModifiedTime(c, FileTime.fromMillis(now - 1000));

        // max=2: ancient dies on age; then 3 survivors → drop 1 oldest (a).
        var r = FormatStampGc.sweep(cache, Duration.ofDays(7), 2, false);

        assertThat(r.deletedByAge()).isEqualTo(1);
        assertThat(r.deletedByCap()).isEqualTo(1);
        assertThat(r.deleted()).isEqualTo(2);
        assertThat(Files.exists(ancient)).isFalse();
        assertThat(Files.exists(a)).isFalse();
        assertThat(Files.exists(b)).isTrue();
        assertThat(Files.exists(c)).isTrue();
    }

    @Test
    void dry_run_reports_but_keeps_files(@TempDir Path cache) throws IOException {
        Path stale = stamp(cache, "ee", "ff", "stale");
        backdate(stale, 10);

        var r = FormatStampGc.sweep(cache, Duration.ofDays(7), 100, true);

        assertThat(r.deletedByAge()).isEqualTo(1);
        assertThat(Files.exists(stale)).isTrue();
    }

    @Test
    void empty_shard_dirs_are_removed_after_delete(@TempDir Path cache) throws IOException {
        Path stale = stamp(cache, "ab", "cd", "gone");
        backdate(stale, 10);

        FormatStampGc.sweep(cache, Duration.ofDays(7), 0, false);

        assertThat(Files.exists(stale)).isFalse();
        assertThat(Files.exists(cache.resolve("format-stamps/ab/cd"))).isFalse();
        assertThat(Files.exists(cache.resolve("format-stamps/ab"))).isFalse();
    }

    private static Path stamp(Path cache, String ab, String cd, String rest) throws IOException {
        Path p = cache.resolve("format-stamps").resolve(ab).resolve(cd).resolve(rest);
        Files.createDirectories(p.getParent());
        return Files.writeString(p, "");
    }

    private static void backdate(Path file, int days) throws IOException {
        Files.setLastModifiedTime(
                file,
                FileTime.fromMillis(
                        System.currentTimeMillis() - Duration.ofDays(days).toMillis()));
    }
}
