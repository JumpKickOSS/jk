// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.plugin.StartupCaches;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineMaintenanceTest {

    @Test
    void config_delete_and_recreate_both_count_as_changes(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config.toml");
        List<String> log = new ArrayList<>();
        try (EngineMaintenance m =
                new EngineMaintenance(log::add, new StoreFeedRefresh(s -> {}), () -> {}, dir.resolve("stamp"), cfg)) {

            Files.writeString(cfg, "[engine]\n");
            m.maybeReloadConfig(); // first tick: baseline, no log
            assertThat(log).isEmpty();

            Files.delete(cfg);
            m.maybeReloadConfig();
            assertThat(log).hasSize(1);
            assertThat(log.get(0)).contains("removed");
            m.maybeReloadConfig(); // still absent: no repeat
            assertThat(log).hasSize(1);

            Files.writeString(cfg, "[engine]\nauto-warmup = false\n");
            m.maybeReloadConfig();
            assertThat(log).hasSize(2);
            assertThat(log.get(1)).contains("created");

            Files.setLastModifiedTime(cfg, FileTime.fromMillis(System.currentTimeMillis() + 5_000));
            m.maybeReloadConfig();
            assertThat(log).hasSize(3);
            assertThat(log.get(2)).contains("reloaded user config");
        }
    }

    @Test
    void absent_config_on_first_tick_stays_quiet(@TempDir Path dir) throws Exception {
        List<String> log = new ArrayList<>();
        try (EngineMaintenance m = new EngineMaintenance(
                log::add, new StoreFeedRefresh(s -> {}), () -> {}, dir.resolve("stamp"), dir.resolve("config.toml"))) {
            m.maybeReloadConfig();
            m.maybeReloadConfig();
            assertThat(log).isEmpty();
        }
    }

    /**
     * The 12 h cycle runs the startup-cache sweep: another version's cache goes, and on a snapshot
     * build one unmapped for 12 hours too. On the base only an engine start swept.
     */
    @Test
    void on_a_snapshot_the_maintenance_cycle_sweeps_other_versions_and_unused_caches(@TempDir Path dir)
            throws Exception {
        Swept s = cycle(dir, "9.9.9-SNAPSHOT");
        assertThat(s.otherVersion()).doesNotExist();
        assertThat(s.unused()).as("a snapshot's cache unmapped for 12 h").doesNotExist();
    }

    @Test
    void on_a_release_the_maintenance_cycle_sweeps_only_other_versions(@TempDir Path dir) throws Exception {
        Swept s = cycle(dir, "9.9.9");
        assertThat(s.otherVersion()).doesNotExist();
        assertThat(s.unused()).as("a release deletes nothing by age").exists();
    }

    /** Another version's cache and one of {@code version} unmapped for 13 h, after one tick under {@code version}. */
    private record Swept(Path otherVersion, Path unused) {}

    private static Swept cycle(Path dir, String version) throws Exception {
        Path state = Files.createDirectories(dir.resolve("state"));
        Path aot = Files.createDirectories(state.resolve("aot"));
        Path otherVersion = Files.writeString(aot.resolve("graph-0.1.0-aaaaaaaa-0123456789abcdef.aot"), "x");
        Path unused = Files.writeString(aot.resolve("graph-" + version + "-aaaaaaaa-0123456789abcdef.aot"), "x");
        Files.setLastModifiedTime(
                unused,
                FileTime.fromMillis(
                        System.currentTimeMillis() - Duration.ofHours(13).toMillis()));
        List<String> due = new ArrayList<>();
        System.setProperty("jk.env.JK_STATE_DIR", state.toString());
        StartupCaches.pinVersionForTests(version);
        try (EngineMaintenance m = new EngineMaintenance(
                s -> {},
                () -> due.add("fetches"),
                () -> due.add("due"),
                dir.resolve("stamp"),
                dir.resolve("config.toml"))) {
            m.tickQuietly();
        } finally {
            StartupCaches.pinVersionForTests(null);
            System.clearProperty("jk.env.JK_STATE_DIR");
        }
        assertThat(due).containsExactly("fetches", "due");
        return new Swept(otherVersion, unused);
    }
}
