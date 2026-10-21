// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.time.Clock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A marked tree's units carry their last use as their directory's mtime; any other tree is left alone. */
class PoolUseTest {

    private static final long DAY = 24L * 60 * 60 * 1000;

    @Test
    void a_unit_of_a_marked_tree_is_stamped_on_use(@TempDir Path tmp) throws Exception {
        Path tree = tmp.resolve("pooled");
        PoolUse.mark(tree);
        Path unit = old(tree.resolve("org/example/lib/1.0"));

        PoolUse.touch(tree, unit);

        assertThat(Files.getLastModifiedTime(unit).toMillis())
                .isGreaterThan(Clock.SYSTEM.millis() - PoolUse.EVERY_MILLIS);
    }

    @Test
    void a_unit_is_stamped_once_per_window_by_one_process(@TempDir Path tmp) throws Exception {
        Path tree = tmp.resolve("pooled");
        PoolUse.mark(tree);
        Path unit = old(tree.resolve("org/example/lib/1.0"));
        PoolUse.touch(tree, unit);
        Files.setLastModifiedTime(unit, FileTime.fromMillis(Clock.SYSTEM.millis() - 2 * DAY));

        PoolUse.touch(tree, unit);

        assertThat(Files.getLastModifiedTime(unit).toMillis())
                .as("the second use within the window costs a map lookup, not a write")
                .isLessThan(Clock.SYSTEM.millis() - DAY);
    }

    @Test
    void an_unmarked_tree_is_never_written(@TempDir Path tmp) throws Exception {
        Path tree = Files.createDirectories(tmp.resolve("store"));
        Path unit = old(tree.resolve("org/example/lib/1.0"));
        long before = Files.getLastModifiedTime(unit).toMillis();

        PoolUse.touch(tree, unit);

        assertThat(Files.getLastModifiedTime(unit).toMillis()).isEqualTo(before);
        assertThat(tree.resolve(PoolUse.MARKER)).doesNotExist();
    }

    @Test
    void a_unit_not_there_yet_is_stamped_once_it_is(@TempDir Path tmp) throws Exception {
        Path tree = tmp.resolve("pooled");
        PoolUse.mark(tree);
        Path unit = tree.resolve("org/example/lib/2.0");

        PoolUse.touch(tree, unit);
        old(unit);
        PoolUse.touch(tree, unit);

        assertThat(Files.getLastModifiedTime(unit).toMillis())
                .isGreaterThan(Clock.SYSTEM.millis() - PoolUse.EVERY_MILLIS);
    }

    private static Path old(Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.setLastModifiedTime(dir, FileTime.fromMillis(Clock.SYSTEM.millis() - 2 * DAY));
        return dir;
    }
}
