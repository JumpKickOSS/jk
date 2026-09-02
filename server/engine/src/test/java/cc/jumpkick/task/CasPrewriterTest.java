// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.testing.Await;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CasPrewriterTest {

    /**
     * The background poller's only externally observable effect: a two-poll-stable file is hashed
     * and its blob copied into the CAS. Polling for the blob is what the three tests below used to
     * approximate with {@code Thread.sleep(350)} — 3.5 poll intervals, which was simultaneously a
     * guess about this machine and no assertion at all. Two of them ({@code
     * finish_uses_latest_content_...}, {@code finish_rehashes_when_content_changes_...}) claim to
     * exercise a rewrite <em>after</em> pre-processing, and would have passed unchanged if
     * pre-processing had never happened, because {@code finish} content-hashes regardless. Waiting
     * on the blob makes the precondition an assertion.
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
            assertThat(Files.exists(cas.pathFor(outputs.get("A.class")))).isTrue();
            assertThat(Files.exists(cas.pathFor(outputs.get("B.class")))).isTrue();
        }
    }

    @Test
    void finish_catches_files_missed_by_the_poller(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Cas cas = new Cas(tempDir.resolve("cas"));

        CasPrewriter prewriter = CasPrewriter.watching(cas, classes);
        // Write the file just before finish — poller won't have had time
        // to see it twice, so the final pass should handle it.
        Files.writeString(classes.resolve("Late.class"), "late-content");

        Map<String, String> outputs = prewriter.finish();

        assertThat(outputs).containsEntry("Late.class", Hashing.sha256Hex("late-content".getBytes()));
        assertThat(Files.exists(cas.pathFor(outputs.get("Late.class")))).isTrue();
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
            awaitPreprocessed(cas, "AAAAAA"); // the poller settled the first content
            Files.writeString(file, "BBBBBB");
            Files.setLastModifiedTime(file, mtime);
        } finally {
            Map<String, String> outputs = prewriter.finish();
            assertThat(outputs).containsEntry("Same.class", Hashing.sha256Hex("BBBBBB".getBytes()));
            assertThat(outputs.get("Same.class")).isNotEqualTo(Hashing.sha256Hex("AAAAAA".getBytes()));
        }
    }
}
