// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The sandbox home a forked test JVM runs against. Two properties carry the weight: it is not inside
 * the project under test, and it is short.
 *
 * <p>The first is the one that cost work — a directory shaped like jk's store, inside a source tree,
 * is what a stray {@code git} command resolved out of and reset. The second is why the root is under
 * the user home rather than the platform temp dir: Windows stops at 260 characters and macOS
 * {@code TMPDIR} is deep before anything is appended to it.
 */
class TestHomesTest {

    @Test
    void the_home_is_not_inside_the_project_under_test(@TempDir Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("ws/clients/cli"));

        Path home = TestHomes.root().resolve(TestHomes.keyFor(module));

        assertThat(home.toString()).doesNotStartWith(module.toString());
        assertThat(home.toString()).doesNotStartWith(tmp.resolve("ws").toString());
        assertThat(home).isAbsolute();
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

    /**
     * The budget this move exists inside. A key is 12 hex characters and the root is one dotted name
     * under the user home, so the whole prefix is shorter than the {@code <module>/target/} it
     * replaces for any module nested at all — which is the case on Windows that matters.
     */
    @Test
    void the_prefix_is_shorter_than_the_module_relative_one_it_replaces() {
        Path module = Path.of(System.getProperty("user.home"), "src", "oss", "jk", "clients", "cli");

        int relocated =
                TestHomes.root().resolve(TestHomes.keyFor(module)).toString().length();
        int previous =
                module.resolve("target").resolve("test-jk-home").toString().length();

        assertThat(relocated)
                .as("relocated=%d previous=%d", relocated, previous)
                .isLessThan(previous);
        assertThat(TestHomes.keyFor(module)).hasSize(12).matches("[0-9a-f]+");
    }

    @Test
    void a_home_is_created_and_stamped_so_the_next_reap_spares_it(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("homes");
        Path home = Files.createDirectories(root.resolve("abc123abc123"));
        TestHomes.stamp(home);

        assertThat(TestHomes.reapStale(root, System.currentTimeMillis()))
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
        Files.setLastModifiedTime(
                stale.resolve(".used-at"),
                FileTime.from(
                        System.currentTimeMillis()
                                - Duration.ofDays(TestHomes.KEEP_DAYS + 1).toMillis(),
                        TimeUnit.MILLISECONDS));

        assertThat(TestHomes.reapStale(root, System.currentTimeMillis())).isEqualTo(1);
        assertThat(stale).doesNotExist();
    }

    /** Nothing else writes here, so an unstamped directory is a crashed run's leftover. */
    @Test
    void an_unstamped_leftover_is_reaped(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("homes"));
        Path orphan = Files.createDirectories(root.resolve("eeeeeeeeeeee/store"));
        Files.setLastModifiedTime(
                orphan.getParent(),
                FileTime.from(
                        System.currentTimeMillis()
                                - Duration.ofDays(TestHomes.KEEP_DAYS + 1).toMillis(),
                        TimeUnit.MILLISECONDS));

        assertThat(TestHomes.reapStale(root, System.currentTimeMillis())).isEqualTo(1);
        assertThat(orphan.getParent()).doesNotExist();
    }

    @Test
    void a_missing_root_reaps_nothing_and_does_not_throw(@TempDir Path tmp) {
        assertThat(TestHomes.reapStale(tmp.resolve("never-created"), System.currentTimeMillis()))
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

        assertThat(TestHomes.reapStale(root, wellPast))
                .as("a clock moved past the window reaps it; no file timestamps were touched")
                .isEqualTo(1);
        assertThat(old).doesNotExist();
    }
}
