// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.util.AccessLedger;
import cc.jumpkick.util.ToolUse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolsGcTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY = 24L * 60 * 60 * 1000;

    private static Path install(Path tools, String family, String version, double daysOld) throws Exception {
        Path dir = Files.createDirectories(tools.resolve(family).resolve(version));
        Files.writeString(dir.resolve("bin"), "x".repeat(100));
        Files.setLastModifiedTime(dir, FileTime.fromMillis(NOW - (long) (daysOld * DAY)));
        return dir;
    }

    @Test
    void only_the_most_recently_used_node_stays_once_the_others_pass_two_days(@TempDir Path tools) throws Exception {
        Path old = install(tools, "node", "24.21.0", 5);
        Path mid = install(tools, "node", "24.22.0", 3);
        Path newest = install(tools, "node", "24.22.1", 5);
        Files.setLastModifiedTime(newest, FileTime.fromMillis(NOW - DAY));

        ToolsGc.Report report = ToolsGc.run(tools, Set.of("node"), Map.of(), 1.0, NOW, false);

        assertThat(report.removed()).containsExactlyInAnyOrder("node/24.21.0", "node/24.22.0");
        assertThat(old).doesNotExist();
        assertThat(mid).doesNotExist();
        assertThat(newest).exists();
    }

    @Test
    void an_older_install_used_recently_by_the_ledger_stays_as_long_as_its_window(@TempDir Path tools)
            throws Exception {
        Path pinned = install(tools, "node", "22.1.0", 20);
        install(tools, "node", "24.22.1", 1);
        Map<String, AccessLedger.Use> uses =
                Map.of(ToolUse.key("node", "22.1.0"), new AccessLedger.Use(NOW - 30 * DAY, NOW - DAY / 2, 3, 3));

        ToolsGc.run(tools, Set.of("node"), uses, 1.0, NOW, false);

        assertThat(pinned).exists();
    }

    @Test
    void an_unpacked_archive_goes_a_week_after_its_last_use_and_pressure_shortens_that(@TempDir Path tools)
            throws Exception {
        Path big = install(tools, "unpacked", "abc123", 4);

        ToolsGc.run(tools, Set.of(), Map.of(), 1.0, NOW, false);
        assertThat(big).as("seven days of room").exists();

        ToolsGc.run(tools, Set.of(), Map.of(), 0.25, NOW, false);
        assertThat(big).as("under two days at pressure 0.25").doesNotExist();
    }

    @Test
    void a_dry_run_removes_nothing_and_unmanaged_families_are_left_alone(@TempDir Path tools) throws Exception {
        Path node = install(tools, "node", "1.0.0", 400);
        Path other = install(tools, "kts-host", "x", 400);

        ToolsGc.Report report = ToolsGc.run(tools, Set.of("node"), Map.of(), 1.0, NOW, true);

        assertThat(report.deleted()).isEqualTo(1);
        assertThat(report.freedBytes()).isEqualTo(100);
        assertThat(node).exists();
        assertThat(other).exists();
    }
}
