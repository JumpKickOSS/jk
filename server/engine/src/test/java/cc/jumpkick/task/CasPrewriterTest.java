// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.testing.Await;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class CasPrewriterTest {

    /**
     * The watcher's only externally observable effect: a file that held still for a tick is hashed
     * and its blob copied into the CAS. Two tests ({@code finish_uses_latest_content_...}, {@code
     * finish_rehashes_when_content_changes_...}) exercise a rewrite <em>after</em> pre-processing
     * and would pass unchanged if pre-processing never happened, because {@code finish}
     * content-hashes regardless; waiting on the blob makes the precondition an assertion.
     */
    private static void awaitPreprocessed(Cas cas, String... contents) throws InterruptedException {
        for (String content : contents) {
            String hex = Hashing.sha256Hex(content.getBytes());
            Await.until(
                    Duration.ofSeconds(30),
                    () -> Files.exists(cas.pathFor(hex)),
                    () -> "the prewriter never ingested " + content + " (sha " + hex + ")");
        }
    }

    /**
     * {@code finish} walks and hashes the whole output tree and can fail on what a dying compiler
     * left behind; {@code close} only stops the watch. The compile's failure path uses close so a
     * walk failure cannot replace the compiler's own exception.
     */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void close_does_not_walk_the_tree_finish_would_fail_on(@TempDir Path tempDir) throws Exception {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Cas cas = new Cas(tempDir.resolve("cas"));
        Path unreadable = classes.resolve("Half.class");
        Files.writeString(unreadable, "half-written");
        Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("---------"));
        try {
            CasPrewriter failing = CasPrewriter.watching(cas, classes);
            assertThatThrownBy(failing::finish).isInstanceOf(IOException.class);

            CasPrewriter abandoned = CasPrewriter.watching(cas, classes);
            abandoned.close();
        } finally {
            Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void finish_picks_up_files_added_during_watching(@TempDir Path tempDir) throws Exception {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Cas cas = new Cas(tempDir.resolve("cas"));

        CasPrewriter prewriter = CasPrewriter.watching(cas, classes);
        try {
            Files.writeString(classes.resolve("A.class"), "AAAAAA");
            Files.writeString(classes.resolve("B.class"), "BBBBBB");
            awaitPreprocessed(cas, "AAAAAA", "BBBBBB");
        } finally {
            Map<String, String> outputs = prewriter.finish();
            assertThat(outputs).containsKeys("A.class", "B.class");
            assertThat(outputs.get("A.class")).isEqualTo(Hashing.sha256Hex("AAAAAA".getBytes()));
            assertThat(outputs.get("B.class")).isEqualTo(Hashing.sha256Hex("BBBBBB".getBytes()));
            // Both shas should be hard-linked into the CAS.
            assertThat(Files.exists(cas.pathFor(requireNonNull(outputs.get("A.class")))))
                    .isTrue();
            assertThat(Files.exists(cas.pathFor(requireNonNull(outputs.get("B.class")))))
                    .isTrue();
        }
    }

    @Test
    void finish_catches_files_the_watch_has_not_settled(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Cas cas = new Cas(tempDir.resolve("cas"));

        CasPrewriter prewriter = CasPrewriter.watching(cas, classes);
        // Written just before finish: no tick has seen it hold still, so the final pass handles it.
        Files.writeString(classes.resolve("Late.class"), "late-content");

        Map<String, String> outputs = prewriter.finish();

        assertThat(outputs).containsEntry("Late.class", Hashing.sha256Hex("late-content".getBytes()));
        assertThat(Files.exists(cas.pathFor(requireNonNull(outputs.get("Late.class")))))
                .isTrue();
    }

    @Test
    void finish_uses_latest_content_when_file_is_modified_after_pre_processing(@TempDir Path tempDir) throws Exception {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Cas cas = new Cas(tempDir.resolve("cas"));

        CasPrewriter prewriter = CasPrewriter.watching(cas, classes);
        try {
            Path file = classes.resolve("Mut.class");
            Files.writeString(file, "first-version");
            awaitPreprocessed(cas, "first-version");
            // Now mutate the file — the recorded snapshot will mismatch
            // current state, so finish should re-hash.
            Files.writeString(file, "second-version-longer");
        } finally {
            Map<String, String> outputs = prewriter.finish();
            assertThat(outputs).containsEntry("Mut.class", Hashing.sha256Hex("second-version-longer".getBytes()));
        }
    }

    @Test
    void freshness_stamp_is_not_treated_as_an_output(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Cas cas = new Cas(tempDir.resolve("cas"));
        // Simulate left-over freshness stamps from a previous build.
        Files.writeString(classes.resolve(BuildStamps.JAVA), "stamp body");
        Files.writeString(classes.resolve(BuildStamps.KOTLIN), "stamp body");

        CasPrewriter prewriter = CasPrewriter.watching(cas, classes);
        Map<String, String> outputs = prewriter.finish();

        assertThat(outputs).doesNotContainKey(BuildStamps.JAVA);
        assertThat(outputs).doesNotContainKey(BuildStamps.KOTLIN);
    }

    @Test
    void empty_output_dir_yields_empty_map(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Cas cas = new Cas(tempDir.resolve("cas"));

        Map<String, String> outputs = CasPrewriter.watching(cas, classes).finish();

        assertThat(outputs).isEmpty();
    }

    /**
     * Same size + same mtime as a poll-stable file must still re-content-hash on finish — coarse
     * mtime filesystems can rewrite bytes without advancing mtime within one tick.
     */
    @Test
    void finish_rehashes_when_content_changes_without_size_mtime_change(@TempDir Path tempDir) throws Exception {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Cas cas = new Cas(tempDir.resolve("cas"));
        Path file = classes.resolve("Same.class");
        // Equal-length payloads so size stays constant; force identical mtime after rewrite.
        Files.writeString(file, "AAAAAA");
        var mtime = Files.getLastModifiedTime(file);

        CasPrewriter prewriter = CasPrewriter.watching(cas, classes);
        try {
            // A rewrite the watch settles; a file untouched since watching began is finish's alone.
            Files.writeString(file, "AAAAAA");
            Files.setLastModifiedTime(file, mtime);
            awaitPreprocessed(cas, "AAAAAA");
            Files.writeString(file, "BBBBBB");
            Files.setLastModifiedTime(file, mtime);
        } finally {
            Map<String, String> outputs = prewriter.finish();
            assertThat(outputs).containsEntry("Same.class", Hashing.sha256Hex("BBBBBB".getBytes()));
            assertThat(outputs.get("Same.class")).isNotEqualTo(Hashing.sha256Hex("AAAAAA".getBytes()));
        }
    }

    /** A one-file incremental compile leaves the rest of the tree untouched: finish reads none of it. */
    @Test
    void finish_reads_no_file_the_memo_already_vouches_for(@TempDir Path tempDir) throws Exception {
        Path classes = Files.createDirectories(tempDir.resolve("classes"));
        Cas cas = new Cas(tempDir.resolve("cas"));
        for (int i = 0; i < 50; i++) {
            Path f = classes.resolve("C" + i + ".class");
            Files.writeString(f, "class " + i);
            Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() - 3_600_000));
            FileHashMemo.rememberContent(f, Hashing.sha256Hex(Files.readAllBytes(f)));
        }
        FileHashMemo.resetStats();

        Map<String, String> outputs = CasPrewriter.watching(cas, classes).finish();

        assertThat(outputs).hasSize(50).containsEntry("C7.class", Hashing.sha256Hex("class 7".getBytes()));
        assertThat(FileHashMemo.memoHits())
                .as("every file answered from the memo")
                .isEqualTo(50);
        assertThat(FileHashMemo.contentReads())
                .as("every file answered from the memo")
                .isZero();
    }

    /** However long the compile runs, the tree is walked to watch its directories and in finish. */
    @Test
    void a_compile_spanning_many_ticks_walks_the_tree_twice(@TempDir Path tempDir) throws Exception {
        Path classes = Files.createDirectories(tempDir.resolve("classes"));
        Files.createDirectories(classes.resolve("pkg"));
        Cas cas = new Cas(tempDir.resolve("cas"));

        CasPrewriter prewriter = CasPrewriter.watching(cas, classes);
        Map<String, String> outputs;
        try {
            // Each file is ingested before the next is written, so the compile spans many ticks.
            for (int i = 0; i < 5; i++) {
                Files.writeString(classes.resolve("pkg/C" + i + ".class"), "class " + i);
                awaitPreprocessed(cas, "class " + i);
            }
            // A package directory the compiler creates mid-compile is watched too.
            Files.createDirectories(classes.resolve("fresh/sub"));
            Files.writeString(classes.resolve("fresh/sub/D.class"), "class D");
            awaitPreprocessed(cas, "class D");
        } finally {
            outputs = prewriter.finish();
        }

        assertThat(outputs).containsKeys("pkg/C0.class", "pkg/C4.class", "fresh/sub/D.class");
        assertThat(prewriter.treeWalks())
                .as("one walk to watch the directories, one in finish")
                .isEqualTo(2);
    }

    /** Compiles in flight at once share one watcher thread rather than starting one each. */
    @Test
    void compiles_in_flight_share_one_thread(@TempDir Path tempDir) throws Exception {
        Cas cas = new Cas(tempDir.resolve("cas"));
        List<CasPrewriter> open = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                Path classes = Files.createDirectories(tempDir.resolve("m" + i + "/classes"));
                open.add(CasPrewriter.watching(cas, classes));
                Files.writeString(classes.resolve("A.class"), "module " + i);
            }
            awaitPreprocessed(cas, "module 0", "module 1", "module 2");
            long threads = Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> t.isAlive() && CasPrewriter.THREAD_NAME.equals(t.getName()))
                    .count();
            assertThat(threads)
                    .as("prewriter threads with three compiles in flight")
                    .isEqualTo(1);
        } finally {
            for (CasPrewriter p : open) p.close();
        }
    }
}
