// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A build that arrives while a prune works through one tier, or through scratch, stops the pass at
 * the next victim, not at the end of the tier: a tier of thousands of expired files is otherwise one
 * stretch the build waits out whole. Each victim goes whole, so the stop leaves nothing half taken.
 */
class CacheRetentionYieldTest {

    private static final int VICTIMS = 20;

    @Test
    void a_file_tier_stops_after_the_victim_it_was_taking(@TempDir Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < VICTIMS; i++) files.add(stale(root.resolve("format/freshness/f" + i)));
        PruneYield yield = new PruneYield(() -> files.stream().anyMatch(Files::notExists));

        assertThatThrownBy(() -> CacheRetention.sweep(root, new Cas(root), Set.of(), false, yield))
                .isInstanceOf(PruneYield.Yielded.class);

        assertThat(yield.yielded()).isTrue();
        assertThat(files.stream().filter(Files::notExists))
                .as("taken before the stop")
                .hasSize(1);
    }

    @Test
    void a_tier_of_trees_stops_after_the_tree_it_was_taking(@TempDir Path root) throws IOException {
        List<Path> trees = new ArrayList<>();
        for (int i = 0; i < VICTIMS; i++) {
            Path tree = root.resolve("generated/t" + i);
            stale(tree.resolve("a/A.java"));
            stale(tree.resolve("b/B.java"));
            trees.add(tree);
        }
        PruneYield yield = new PruneYield(() -> trees.stream().anyMatch(Files::notExists));

        assertThatThrownBy(() -> CacheRetention.sweep(root, new Cas(root), Set.of(), false, yield))
                .isInstanceOf(PruneYield.Yielded.class);

        assertThat(trees.stream().filter(Files::notExists))
                .as("taken whole before the stop")
                .hasSize(1);
        assertThat(trees.stream().filter(Files::exists))
                .allMatch(t -> Files.exists(t.resolve("a/A.java")) && Files.exists(t.resolve("b/B.java")));
    }

    @Test
    void the_scratch_sweep_stops_after_the_file_it_was_taking(@TempDir Path tmp) throws IOException {
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < VICTIMS; i++) files.add(stale(tmp.resolve("s" + i + "/import.md")));
        PruneYield yield = new PruneYield(() -> files.stream().anyMatch(Files::notExists));

        assertThatThrownBy(() -> TmpGc.sweep(tmp, TmpGc.DEFAULT_TTL, false, yield))
                .isInstanceOf(PruneYield.Yielded.class);

        assertThat(files.stream().filter(Files::notExists)).hasSize(1);
    }

    /** A file written 40 days ago: past every window it is seeded under, and past the sweep grace. */
    private static Path stale(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
        Files.setLastModifiedTime(
                file,
                FileTime.fromMillis(
                        System.currentTimeMillis() - Duration.ofDays(40).toMillis()));
        return file;
    }
}
