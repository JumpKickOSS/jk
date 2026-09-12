// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.kotlin.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Classpath ABI snapshots are what the incremental Kotlin compile consults to decide whether a
 * dependency's API moved. A test compile's classpath starts with the module's own
 * {@code target/classes/main}, rewritten by every build at the same path, so a snapshot that is
 * reused whenever one exists for that path never reports the change and only the tests whose own
 * sources moved get recompiled. The snapshot has to follow the entry's content, not its name.
 *
 * <p>The Build Tools API implementation is not on this test classpath; the snapshotting call is a
 * seam here, and what is asserted is when the worker asks for a fresh snapshot.
 */
class ClasspathSnapshotTest {

    /** Every entry the worker asked to have snapshotted, in order; each call writes the file. */
    private static final class RecordingSnapshotter implements KotlinCompiler.Snapshotter {
        final List<Path> requested = new ArrayList<>();

        @Override
        public void snapshot(Path entry, Path out) throws IOException {
            requested.add(entry);
            Files.writeString(out, "snapshot of " + entry, StandardCharsets.UTF_8);
        }
    }

    @Test
    void a_rewritten_class_in_a_classes_directory_gets_a_fresh_snapshot(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("target/classes/main"));
        Path api = write(classes.resolve("com/example/Api.class"), "v1");
        Path snapshots = tmp.resolve("snapshots");
        RecordingSnapshotter snapshotter = new RecordingSnapshotter();

        List<Path> first = KotlinCompiler.snapshotClasspath(List.of(classes.toFile()), snapshots, snapshotter);
        List<Path> unchanged = KotlinCompiler.snapshotClasspath(List.of(classes.toFile()), snapshots, snapshotter);
        write(api, "v2 with a new public method");
        List<Path> rewritten = KotlinCompiler.snapshotClasspath(List.of(classes.toFile()), snapshots, snapshotter);

        assertThat(snapshotter.requested)
                .as("the second build reuses the snapshot; the rewrite invalidates it")
                .containsExactly(classes, classes);
        assertThat(unchanged).isEqualTo(first);
        assertThat(rewritten)
                .as("the incremental compile is handed a snapshot that describes the rewritten classes")
                .isNotEqualTo(first);
        assertThat(rewritten.getFirst()).isRegularFile();
    }

    @Test
    void a_deleted_class_in_a_classes_directory_gets_a_fresh_snapshot(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        write(classes.resolve("Kept.class"), "kept");
        Path removed = write(classes.resolve("Removed.class"), "removed");
        Path snapshots = tmp.resolve("snapshots");
        RecordingSnapshotter snapshotter = new RecordingSnapshotter();

        KotlinCompiler.snapshotClasspath(List.of(classes.toFile()), snapshots, snapshotter);
        Files.delete(removed);
        KotlinCompiler.snapshotClasspath(List.of(classes.toFile()), snapshots, snapshotter);

        assertThat(snapshotter.requested).containsExactly(classes, classes);
    }

    @Test
    void a_stale_snapshot_of_the_same_entry_does_not_accumulate(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Path api = write(classes.resolve("Api.class"), "v1");
        Path snapshots = tmp.resolve("snapshots");
        RecordingSnapshotter snapshotter = new RecordingSnapshotter();

        KotlinCompiler.snapshotClasspath(List.of(classes.toFile()), snapshots, snapshotter);
        write(api, "v2");
        KotlinCompiler.snapshotClasspath(List.of(classes.toFile()), snapshots, snapshotter);
        write(api, "v3");
        List<Path> latest = KotlinCompiler.snapshotClasspath(List.of(classes.toFile()), snapshots, snapshotter);

        try (var files = Files.list(snapshots)) {
            assertThat(files.toList())
                    .as("the shared snapshot cache is rewritten by every build of every module; "
                            + "only the current snapshot of an entry stays")
                    .containsExactly(latest.getFirst());
        }
    }

    @Test
    void an_unchanged_jar_reuses_its_snapshot_and_a_changed_one_does_not(@TempDir Path tmp) throws Exception {
        Path jar = write(tmp.resolve("store/ab/cd/dep-1.0.jar"), "PK jar bytes");
        Path snapshots = tmp.resolve("snapshots");
        RecordingSnapshotter snapshotter = new RecordingSnapshotter();

        KotlinCompiler.snapshotClasspath(List.of(jar.toFile()), snapshots, snapshotter);
        KotlinCompiler.snapshotClasspath(List.of(jar.toFile()), snapshots, snapshotter);
        write(jar, "PK jar bytes, republished");
        KotlinCompiler.snapshotClasspath(List.of(jar.toFile()), snapshots, snapshotter);

        assertThat(snapshotter.requested).containsExactly(jar, jar);
    }

    @Test
    void a_rewrite_that_keeps_the_size_is_still_seen(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Path api = write(classes.resolve("Api.class"), "same size A");
        Path snapshots = tmp.resolve("snapshots");
        RecordingSnapshotter snapshotter = new RecordingSnapshotter();

        KotlinCompiler.snapshotClasspath(List.of(classes.toFile()), snapshots, snapshotter);
        Files.writeString(api, "same size B", StandardCharsets.UTF_8);
        // A compiler rewriting a class inside the clock's granularity is what mtime alone misses;
        // the rewrite here lands two seconds later, which every filesystem resolves.
        Files.setLastModifiedTime(api, FileTime.fromMillis(System.currentTimeMillis() + 2_000));
        KotlinCompiler.snapshotClasspath(List.of(classes.toFile()), snapshots, snapshotter);

        assertThat(snapshotter.requested).containsExactly(classes, classes);
    }

    @Test
    void a_missing_entry_is_skipped_and_a_failing_snapshot_degrades_to_none(@TempDir Path tmp) throws Exception {
        Path present = write(tmp.resolve("present.jar"), "jar");
        File absent = tmp.resolve("absent.jar").toFile();
        RecordingSnapshotter snapshotter = new RecordingSnapshotter();

        List<Path> out = KotlinCompiler.snapshotClasspath(
                List.of(absent, present.toFile()), tmp.resolve("snapshots"), snapshotter);
        List<Path> failed =
                KotlinCompiler.snapshotClasspath(List.of(present.toFile()), tmp.resolve("other"), (e, o) -> {
                    throw new IOException("boom");
                });

        assertThat(out).hasSize(1);
        assertThat(snapshotter.requested).containsExactly(present);
        assertThat(failed)
                .as("no snapshots: the compile stays source-incremental")
                .isEmpty();
    }

    private static Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
