// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A run's lockfile and source list are shared with the run before it when they did not change. */
class RunSnapshotSharingTest {

    private static Path run(Path runs, long n) throws IOException {
        Path dir = Files.createDirectories(runs.resolve(Long.toString(n)));
        Files.writeString(dir.resolve("record.json"), "{}");
        return dir;
    }

    private static Object inode(Path p) throws IOException {
        return Files.readAttributes(p, BasicFileAttributes.class).fileKey();
    }

    @Test
    void the_previous_run_is_the_nearest_lower_number_on_disk(@TempDir Path runs) throws IOException {
        Path one = run(runs, 1);
        Files.createDirectories(runs.resolve("2")); // no record: a run that never completed
        Path three = Files.createDirectories(runs.resolve("3"));

        assertThat(BuildJournal.previousRun(three)).isEqualTo(one);
        assertThat(BuildJournal.previousRun(one)).isNull();
    }

    @Test
    void identical_bytes_link_to_the_previous_run_and_different_bytes_do_not(@TempDir Path runs) throws IOException {
        Path one = run(runs, 1);
        byte[] lock = "[lock]\nversion = 1\n".getBytes(StandardCharsets.UTF_8);
        BuildJournal.shareOrWrite(one.resolve("jk-lock.toml"), lock, null);
        Path two = Files.createDirectories(runs.resolve("2"));

        BuildJournal.shareOrWrite(two.resolve("jk-lock.toml"), lock, one);
        assertThat(Files.readAllBytes(two.resolve("jk-lock.toml"))).isEqualTo(lock);
        Object key = inode(one.resolve("jk-lock.toml"));
        if (key != null) assertThat(inode(two.resolve("jk-lock.toml"))).isEqualTo(key);

        Path three = Files.createDirectories(runs.resolve("3"));
        byte[] other = "[lock]\nversion = 2\n".getBytes(StandardCharsets.UTF_8);
        BuildJournal.shareOrWrite(three.resolve("jk-lock.toml"), other, two);
        assertThat(Files.readAllBytes(three.resolve("jk-lock.toml"))).isEqualTo(other);
        assertThat(Files.readAllBytes(two.resolve("jk-lock.toml")))
                .as("the earlier run is untouched")
                .isEqualTo(lock);
        if (key != null) assertThat(inode(three.resolve("jk-lock.toml"))).isNotEqualTo(key);
    }

    @Test
    void pruning_the_earlier_run_leaves_the_later_copy_whole(@TempDir Path runs) throws IOException {
        Path one = run(runs, 1);
        byte[] sources = "abc\t3\t0\tA.java\n".getBytes(StandardCharsets.UTF_8);
        BuildJournal.shareOrWrite(one.resolve("sources.tsv"), sources, null);
        Path two = Files.createDirectories(runs.resolve("2"));
        BuildJournal.shareOrWrite(two.resolve("sources.tsv"), sources, one);

        Files.delete(one.resolve("sources.tsv"));
        Files.delete(one.resolve("record.json"));
        Files.delete(one);

        assertThat(Files.readAllBytes(two.resolve("sources.tsv"))).isEqualTo(sources);
    }
}
