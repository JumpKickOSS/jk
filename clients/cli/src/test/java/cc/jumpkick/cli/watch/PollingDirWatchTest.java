// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cli.watch.DirWatch.Change;
import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** The watch over a network volume: what a listing each interval reports, and when it is the one opened. */
class PollingDirWatchTest {

    private static final long INTERVAL = TimeUnit.MILLISECONDS.toNanos(20);

    private static final long WAIT = TimeUnit.SECONDS.toNanos(5);

    /** Neither inotify nor FSEvents hears what another machine writes to a share, so a watch over one polls. */
    @Test
    void a_watch_over_a_volume_that_is_not_local_polls(@TempDir Path tmp) throws IOException {
        Path share = tmp.resolve("share");
        try (DirWatch watch = DirWatch.open(List.of(tmp, share), d -> !d.equals(share))) {
            assertThat(watch).isInstanceOf(PollingDirWatch.class);
        }
        try (DirWatch watch = DirWatch.open(List.of(tmp), d -> true)) {
            assertThat(watch).isNotInstanceOf(PollingDirWatch.class);
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void a_volume_is_local_on_linux_unless_its_mount_type_is_a_network_one(@TempDir Path tmp) throws IOException {
        assertThat(Files.getFileStore(tmp).type()).isNotIn(DirWatch.NETWORK_TYPES);
        assertThat(DirWatch.local(tmp)).isTrue();
        assertThat(DirWatch.local(tmp.resolve("not/made/yet")))
                .as("the nearest directory that exists")
                .isTrue();
        assertThat(DirWatch.NETWORK_TYPES).contains("nfs", "nfs4", "cifs", "smb3");
    }

    @Test
    void a_created_modified_and_deleted_entry_is_each_reported_against_its_directory(@TempDir Path tmp)
            throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("src"));
        Path kept = Files.writeString(dir.resolve("Kept.java"), "class Kept {}\n");
        Path gone = Files.writeString(dir.resolve("Gone.java"), "class Gone {}\n");
        try (PollingDirWatch watch = new PollingDirWatch(Clock.SYSTEM, INTERVAL)) {
            watch.register(dir);
            assertThat(watch.watches(dir)).isTrue();

            Path made = Files.writeString(dir.resolve("Made.java"), "class Made {}\n");
            Files.writeString(kept, "class Kept { int x; }\n");
            Files.delete(gone);

            assertThat(changes(watch))
                    .containsExactlyInAnyOrder(
                            new Change(StandardWatchEventKinds.ENTRY_CREATE, dir, made),
                            new Change(StandardWatchEventKinds.ENTRY_MODIFY, dir, kept),
                            new Change(StandardWatchEventKinds.ENTRY_DELETE, dir, gone));
            assertThat(watch.poll(INTERVAL * 3)).as("nothing changed since").isNull();
        }
    }

    @Test
    void a_save_that_keeps_the_size_is_seen_by_its_timestamp(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("src"));
        Path file = Files.writeString(dir.resolve("Main.java"), "class Main {} // 1\n");
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_000_000));
        try (PollingDirWatch watch = new PollingDirWatch(Clock.SYSTEM, INTERVAL)) {
            watch.register(dir);
            Files.writeString(file, "class Main {} // 2\n");
            Files.setLastModifiedTime(file, FileTime.fromMillis(2_000_000));
            assertThat(changes(watch)).containsExactly(new Change(StandardWatchEventKinds.ENTRY_MODIFY, dir, file));
        }
    }

    /** A subdirectory's timestamp moves with its own entries, which a watch of it reports; it is not a change. */
    @Test
    void a_change_inside_a_subdirectory_is_not_a_change_to_it(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("src"));
        Path sub = Files.createDirectories(dir.resolve("demo"));
        try (PollingDirWatch watch = new PollingDirWatch(Clock.SYSTEM, INTERVAL)) {
            watch.register(dir);
            watch.register(sub);
            Path file = Files.writeString(sub.resolve("Main.java"), "class Main {}\n");
            assertThat(changes(watch)).containsExactly(new Change(StandardWatchEventKinds.ENTRY_CREATE, sub, file));
        }
    }

    @Test
    void a_deleted_directory_reports_its_entries_gone_and_is_watched_no_longer(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("src"));
        Path file = Files.writeString(dir.resolve("Main.java"), "class Main {}\n");
        try (PollingDirWatch watch = new PollingDirWatch(Clock.SYSTEM, INTERVAL)) {
            watch.register(dir);
            Files.delete(file);
            Files.delete(dir);
            assertThat(changes(watch)).containsExactly(new Change(StandardWatchEventKinds.ENTRY_DELETE, dir, file));
            assertThat(watch.watches(dir)).isFalse();
        }
    }

    @Test
    void a_closed_watch_refuses_to_wait(@TempDir Path tmp) throws Exception {
        PollingDirWatch watch = new PollingDirWatch(Clock.SYSTEM, INTERVAL);
        watch.register(tmp);
        Thread closer = Thread.ofPlatform().start(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            watch.close();
        });
        assertThatThrownBy(watch::take).isInstanceOf(ClosedWatchServiceException.class);
        closer.join();
    }

    /** Every change of the next batches, until the watch has been quiet for a few intervals. */
    private static List<Change> changes(PollingDirWatch watch) throws InterruptedException {
        List<Change> out = new ArrayList<>();
        List<Change> batch = watch.poll(WAIT);
        while (batch != null) {
            out.addAll(batch);
            batch = watch.poll(INTERVAL * 3);
        }
        return out;
    }
}
