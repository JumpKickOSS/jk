// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The sandbox home a forked test JVM runs against. Three properties carry the weight: it is not
 * inside the project under test, it is short, and it moves with {@code JK_HOME}.
 *
 * <p>The first is the one that costs work — a directory shaped like jk's store, inside a source
 * tree, is what a stray {@code git} command resolves out of and resets. The second is why the root is
 * a fixed short suffix of the product home rather than something under the platform temp dir.
 */
class TestHomesTest {

    private static final long NO_CAP = Long.MAX_VALUE;

    @Test
    void the_home_is_not_inside_the_project_under_test(@TempDir Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("ws/clients/cli"));

        Path home = TestHomes.pathFor(module);

        assertThat(home.toString()).doesNotStartWith(module.toString());
        assertThat(home.toString()).doesNotStartWith(tmp.resolve("ws").toString());
        assertThat(home).isAbsolute();
    }

    /** An engine already running in a sandbox keeps its own sandboxes inside it, not in the real home. */
    @Test
    void the_root_moves_with_jk_home(@TempDir Path tmp) {
        System.setProperty("jk.env.JK_HOME", tmp.resolve("relocated").toString());
        try {
            assertThat(TestHomes.root()).isEqualTo(tmp.resolve("relocated").resolve("test-homes"));
        } finally {
            System.clearProperty("jk.env.JK_HOME");
        }
    }

    /**
     * Two checkouts of one repository have identically named modules. Sharing a store between them
     * would race, so the key is the path and not the name.
     */
    @Test
    void modules_of_the_same_name_in_different_checkouts_get_different_homes(@TempDir Path tmp) throws Exception {
        Path a = Files.createDirectories(tmp.resolve("a/jk/clients/cli"));
        Path b = Files.createDirectories(tmp.resolve("b/jk/clients/cli"));

        assertThat(TestHomes.keyFor(a)).isNotEqualTo(TestHomes.keyFor(b));
        assertThat(TestHomes.keyFor(a))
                .isEqualTo(TestHomes.keyFor(a.toAbsolutePath().normalize()));
    }

    /** One directory reached two ways is one module, so it gets one slot. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void a_module_reached_through_a_link_keys_as_the_directory_itself(@TempDir Path tmp) throws Exception {
        Path real = Files.createDirectories(tmp.resolve("real/module"));
        Path link = Files.createSymbolicLink(tmp.resolve("link"), real.getParent());

        assertThat(TestHomes.keyFor(link.resolve("module"))).isEqualTo(TestHomes.keyFor(real));
    }

    /**
     * The budget: whatever the product home costs, this class adds a fixed short suffix to it —
     * {@code test-homes/<12 hex>/home} — which is what keeps a Windows path under its ceiling.
     */
    @Test
    void the_path_beneath_the_home_is_a_fixed_short_suffix() {
        Path module = Path.of(System.getProperty("user.home"), "src", "oss", "jk", "clients", "cli");

        Path home = TestHomes.pathFor(module);

        assertThat(home).startsWithRaw(JkDirs.home());
        assertThat(JkDirs.home().relativize(home).toString()).hasSizeLessThanOrEqualTo(28);
        assertThat(TestHomes.keyFor(module)).hasSize(12).matches("[0-9a-f]+");
    }

    @Test
    void a_home_is_created_and_stamped_so_the_next_reap_spares_it(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("homes");
        Path home = Files.createDirectories(root.resolve("abc123abc123"));
        TestHomes.stamp(home);

        assertThat(TestHomes.reapStale(root, System.currentTimeMillis(), NO_CAP))
                .as("a home in use is not reaped")
                .isZero();
        assertThat(home).isDirectory();
    }

    /** Warm is not unbounded: a project that moved on stops paying rent. */
    @Test
    void a_home_untouched_past_the_window_is_reaped(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("homes"));
        Path stale = Files.createDirectories(root.resolve("dddddddddddd"));
        Files.createDirectories(stale.resolve("store/templates"));
        TestHomes.stamp(stale);
        age(stale.resolve(".used-at"), Duration.ofDays(TestHomes.KEEP_DAYS + 1));

        assertThat(TestHomes.reapStale(root, System.currentTimeMillis(), NO_CAP))
                .isEqualTo(1);
        assertThat(stale).doesNotExist();
    }

    /** Nothing else writes here, so an unstamped directory is a crashed run's leftover. */
    @Test
    void an_unstamped_leftover_is_reaped(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("homes"));
        Path orphan = Files.createDirectories(root.resolve("eeeeeeeeeeee"));
        Files.createDirectories(orphan.resolve("store"));
        age(orphan, Duration.ofDays(TestHomes.KEEP_DAYS + 1));

        assertThat(TestHomes.reapStale(root, System.currentTimeMillis(), NO_CAP))
                .isEqualTo(1);
        assertThat(orphan).doesNotExist();
    }

    /** Past the byte cap, the least recently used slots go first and the reap stops once the rest fit. */
    @Test
    void over_the_byte_cap_the_least_recently_used_slot_goes_first(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("homes"));
        Path older = slotWithBytes(root, "aaaaaaaaaaaa", 100);
        Path newer = slotWithBytes(root, "bbbbbbbbbbbb", 100);
        age(older.resolve(".used-at"), Duration.ofDays(1));

        assertThat(TestHomes.reapStale(root, System.currentTimeMillis(), 150)).isEqualTo(1);
        assertThat(older).doesNotExist();
        assertThat(newer).isDirectory();
    }

    /**
     * Several gates share one machine: a slot stamped within the hold window is one a running suite
     * may be reading its dependency jars out of, so the byte cap never takes it.
     */
    @Test
    void over_the_byte_cap_a_slot_inside_the_hold_window_is_kept(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("homes"));
        Path running = slotWithBytes(root, "aaaaaaaaaaaa", 100);
        Path launching = slotWithBytes(root, "bbbbbbbbbbbb", 100);
        age(running.resolve(".used-at"), Duration.ofHours(TestHomes.HOLD_HOURS - 1));

        assertThat(TestHomes.reapStale(root, System.currentTimeMillis(), 150)).isZero();
        assertThat(running).isDirectory();
        assertThat(launching).isDirectory();
    }

    /** The workspace's shared m2 lives in a slot with no home; stamped, it reads as in use, not as a leftover. */
    @Test
    void a_slot_prepared_without_a_home_is_stamped(@TempDir Path tmp) throws Exception {
        Path workspace = Files.createDirectories(tmp.resolve("ws"));
        Path slot = TestHomes.prepareSlot(workspace);

        assertThat(slot).isEqualTo(TestHomes.slotFor(workspace));
        assertThat(slot.resolve(".used-at")).isRegularFile();
        assertThat(slot.resolve("home")).doesNotExist();
    }

    @Test
    void under_the_byte_cap_nothing_recent_is_reaped(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("homes"));
        slotWithBytes(root, "aaaaaaaaaaaa", 100);
        slotWithBytes(root, "bbbbbbbbbbbb", 100);

        assertThat(TestHomes.reapStale(root, System.currentTimeMillis(), 1000)).isZero();
    }

    @Test
    void a_missing_root_reaps_nothing_and_does_not_throw(@TempDir Path tmp) {
        assertThat(TestHomes.reapStale(tmp.resolve("never-created"), System.currentTimeMillis(), NO_CAP))
                .isZero();
    }

    /**
     * The reap window is the one thing here that reads a clock, and it decides whether a directory is
     * deleted — so it is settled, not arranged around whatever now happens to be.
     */
    @Test
    void prepare_reaps_on_the_supplied_clock(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("homes"));
        Path old = Files.createDirectories(root.resolve("aaaaaaaaaaaa"));
        TestHomes.stamp(old);
        long wellPast = Files.getLastModifiedTime(old.resolve(".used-at")).toMillis()
                + Duration.ofDays(TestHomes.KEEP_DAYS + 5).toMillis();

        assertThat(TestHomes.reapStale(root, wellPast, NO_CAP))
                .as("a clock moved past the window reaps it; no file timestamps were touched")
                .isEqualTo(1);
        assertThat(old).doesNotExist();
    }

    private static Path slotWithBytes(Path root, String key, int bytes) throws Exception {
        Path slot = Files.createDirectories(root.resolve(key));
        Files.write(Files.createDirectories(slot.resolve("home/store")).resolve("blob"), new byte[bytes]);
        TestHomes.stamp(slot);
        return slot;
    }

    private static void age(Path path, Duration by) throws Exception {
        Files.setLastModifiedTime(
                path, FileTime.from(System.currentTimeMillis() - by.toMillis(), TimeUnit.MILLISECONDS));
    }
}
