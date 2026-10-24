// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkCacheConfig;
import cc.jumpkick.testing.ShortTempDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CachePruneSchedulerTest {

    private static final JkCacheConfig ON = new JkCacheConfig(true, 7, 4.0, 0.5);

    private static final long GIB = 1024L * 1024 * 1024;

    /** A volume with room to spare, so these cases do not depend on the disk the build runs on. */
    private static final JkCacheConfig.DiskSpace ROOMY = new JkCacheConfig.DiskSpace(1000 * GIB, 700 * GIB);

    private static JkCacheConfig.DiskSpace freeGib(long gib) {
        return new JkCacheConfig.DiskSpace(1000 * GIB, gib * GIB);
    }

    private static void prunedHoursAgo(Path cacheRoot, long hours) throws IOException {
        long then = System.currentTimeMillis() - hours * 60 * 60 * 1000;
        Files.writeString(cacheRoot.resolve(".last-pruned"), Long.toString(then), StandardCharsets.UTF_8);
    }

    @Test
    void a_nearly_full_disk_brings_the_next_pass_within_the_hour(@TempDir Path cacheRoot) throws IOException {
        prunedHoursAgo(cacheRoot, 2);

        assertThat(CachePruneScheduler.shouldRun(ON, cacheRoot, freeGib(20), System.currentTimeMillis()))
                .as("2 GiB of 1000 free: an hour is the cadence")
                .isTrue();
        assertThat(CachePruneScheduler.shouldRun(ON, cacheRoot, ROOMY, System.currentTimeMillis()))
                .as("plenty of room: the week stands")
                .isFalse();
    }

    @Test
    void the_cadence_tightens_in_steps_as_the_disk_fills(@TempDir Path cacheRoot) throws IOException {
        prunedHoursAgo(cacheRoot, 8);
        long now = System.currentTimeMillis();

        assertThat(CachePruneScheduler.shouldRun(ON, cacheRoot, freeGib(150), now))
                .as("15% free: a day")
                .isFalse();
        assertThat(CachePruneScheduler.shouldRun(ON, cacheRoot, freeGib(90), now))
                .as("9% free: six hours")
                .isTrue();
    }

    @Test
    void should_run_when_stamp_missing(@TempDir Path cacheRoot) throws IOException {
        assertThat(CachePruneScheduler.shouldRun(ON, cacheRoot, ROOMY, System.currentTimeMillis()))
                .isTrue();
    }

    @Test
    void should_not_run_within_interval(@TempDir Path cacheRoot) throws IOException {
        // Stamp was 1 day ago — interval is 7 → skip.
        long oneDayAgo = System.currentTimeMillis() - (24L * 60 * 60 * 1000);
        Files.writeString(cacheRoot.resolve(".last-pruned"), Long.toString(oneDayAgo), StandardCharsets.UTF_8);

        assertThat(CachePruneScheduler.shouldRun(ON, cacheRoot, ROOMY, System.currentTimeMillis()))
                .isFalse();
    }

    @Test
    void should_run_after_interval(@TempDir Path cacheRoot) throws IOException {
        long tenDaysAgo = System.currentTimeMillis() - (10L * 24 * 60 * 60 * 1000);
        Files.writeString(cacheRoot.resolve(".last-pruned"), Long.toString(tenDaysAgo), StandardCharsets.UTF_8);

        assertThat(CachePruneScheduler.shouldRun(ON, cacheRoot, ROOMY, System.currentTimeMillis()))
                .isTrue();
    }

    @Test
    void should_run_when_stamp_is_garbage(@TempDir Path cacheRoot) throws IOException {
        Files.writeString(cacheRoot.resolve(".last-pruned"), "not-a-number", StandardCharsets.UTF_8);
        assertThat(CachePruneScheduler.shouldRun(ON, cacheRoot, ROOMY, System.currentTimeMillis()))
                .isTrue();
    }

    @Test
    void jvm_install_layout_resolves_bin_jk_from_lib_classpath(@TempDir Path home) throws IOException {
        Path lib = home.resolve("lib");
        Path bin = home.resolve("bin");
        Files.createDirectories(lib);
        Files.createDirectories(bin);
        Path jar = lib.resolve("jk-engine-0.12.0.jar");
        Files.writeString(jar, "fake");
        Path script = bin.resolve("jk");
        Files.writeString(script, "#!/bin/sh\n");

        String cp = jar.toAbsolutePath() + System.getProperty("path.separator") + "/other/classes";
        assertThat(CachePruneScheduler.resolveFromJvmInstallLayout(cp))
                .contains(script.toAbsolutePath().toString());
    }

    @Test
    void jvm_install_layout_empty_when_no_lib_sibling() {
        // Paths under ShortTempDirs.path() — never /tmp:… (colon is illegal in a Windows path).
        Path root = ShortTempDirs.path();
        String sep = System.getProperty("path.separator");
        String cp = root.resolve("classes") + sep + root.resolve("other.jar");
        assertThat(CachePruneScheduler.resolveFromJvmInstallLayout(cp)).isEmpty();
        assertThat(CachePruneScheduler.resolveFromJvmInstallLayout("")).isEmpty();
    }

    @Test
    void jvm_install_layout_prefers_jk_bat_on_windows_style(@TempDir Path home) throws IOException {
        Path lib = home.resolve("lib");
        Path bin = home.resolve("bin");
        Files.createDirectories(lib);
        Files.createDirectories(bin);
        Files.writeString(lib.resolve("app.jar"), "x");
        Path bat = bin.resolve("jk.bat");
        Files.writeString(bat, "@echo off\n");

        assertThat(CachePruneScheduler.resolveFromJvmInstallLayout(
                        lib.resolve("app.jar").toString()))
                .contains(bat.toAbsolutePath().toString());
    }
}
