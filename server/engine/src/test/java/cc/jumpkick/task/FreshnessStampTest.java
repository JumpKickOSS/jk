// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.BuildStamps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FreshnessStampTest {

    private static final int RELEASE = 21;

    /** The option digest every stamp here is written and checked with; the digest test varies it. */
    private static final String DIGEST = "options-a";

    @Test
    void removed_sources_are_detected_and_absent_stamp_is_not(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path kept = writeFile(tempDir.resolve("Kept.java"), "class Kept {}");
        Path dropped = writeFile(tempDir.resolve("Dropped.java"), "class Dropped {}");
        // No stamp yet: nothing recorded, nothing removed.
        assertThat(FreshnessStamp.hasRemovedSources(classes, BuildStamps.JAVA, List.of(kept)))
                .isFalse();
        FreshnessStamp.write(
                classes,
                BuildStamps.JAVA,
                "compile-main",
                "key123",
                List.of(kept, dropped),
                List.of(),
                RELEASE,
                DIGEST);
        // Same set: no removals. Grown set: no removals. Shrunk set: removal detected —
        // the variant-switch case (an extra-src root left the selection).
        assertThat(FreshnessStamp.hasRemovedSources(classes, BuildStamps.JAVA, List.of(kept, dropped)))
                .isFalse();
        assertThat(FreshnessStamp.hasRemovedSources(
                        classes, BuildStamps.JAVA, List.of(kept, dropped, tempDir.resolve("New.java"))))
                .isFalse();
        assertThat(FreshnessStamp.hasRemovedSources(classes, BuildStamps.JAVA, List.of(kept)))
                .isTrue();
    }

    /**
     * A token-spelled classpath is compared as a set and never stat'ed: the entry behind a token
     * may be rewritten at will (a sibling jar after a body-only change) and the stamp holds; a
     * different token set is stale; and a stamp written by path never satisfies a token check.
     */
    @Test
    void token_spelled_classpath_holds_on_equal_tokens_and_ignores_mtimes(@TempDir Path tempDir) throws IOException {
        Path classes = Files.createDirectories(tempDir.resolve("classes"));
        Path src = writeFile(tempDir.resolve("A.java"), "class A {}");
        Files.setLastModifiedTime(src, FileTime.fromMillis(System.currentTimeMillis() - 60_000));
        Path dep = writeFile(tempDir.resolve("dep.jar"), "v1");
        List<String> tokens = List.of("cp:abi:0001", "pp:file:0002");
        FreshnessStamp.write(
                classes,
                BuildStamps.JAVA,
                "compile-main",
                "key123",
                List.of(src),
                FreshnessStamp.ClasspathTokens.of(tokens),
                RELEASE,
                DIGEST);

        // The dependency's bytes and mtime move; the tokens (its ABI) do not.
        writeFile(dep, "v2-rewritten-later");
        Files.setLastModifiedTime(dep, FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        assertThat(FreshnessStamp.isFresh(
                        classes,
                        BuildStamps.JAVA,
                        List.of(src),
                        FreshnessStamp.ClasspathTokens.of(List.of("pp:file:0002", "cp:abi:0001")),
                        RELEASE,
                        DIGEST))
                .as("same token set, in any order")
                .isTrue();
        assertThat(FreshnessStamp.isFresh(
                        classes,
                        BuildStamps.JAVA,
                        List.of(src),
                        FreshnessStamp.ClasspathTokens.of(List.of("cp:abi:0009", "pp:file:0002")),
                        RELEASE,
                        DIGEST))
                .as("a moved token is a moved key")
                .isFalse();
        assertThat(FreshnessStamp.isFresh(classes, BuildStamps.JAVA, List.of(src), List.of(dep), RELEASE, DIGEST))
                .as("a token stamp does not answer a path check")
                .isFalse();
        assertThat(FreshnessStamp.stampedKey(classes, BuildStamps.JAVA)).contains("key123");

        FreshnessStamp.write(
                classes, BuildStamps.JAVA, "compile-main", "", List.of(src), List.of(dep), RELEASE, DIGEST);
        assertThat(FreshnessStamp.isFresh(
                        classes,
                        BuildStamps.JAVA,
                        List.of(src),
                        FreshnessStamp.ClasspathTokens.of(tokens),
                        RELEASE,
                        DIGEST))
                .as("a path stamp does not answer a token check")
                .isFalse();
        assertThat(FreshnessStamp.stampedKey(classes, BuildStamps.JAVA))
                .as("a stamp that names no key")
                .isEmpty();
    }

    @Test
    void absent_stamp_is_not_fresh(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        assertThat(FreshnessStamp.isFresh(classes, BuildStamps.JAVA, List.of(), List.of(), RELEASE, DIGEST))
                .isFalse();
    }

    @Test
    void looks_fresh_with_a_digest_requires_the_recorded_one(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path src = writeFile(tempDir.resolve("A.kt"), "class A");
        Files.setLastModifiedTime(src, FileTime.fromMillis(System.currentTimeMillis() - 5_000));

        FreshnessStamp.write(
                classes, BuildStamps.KOTLIN, "compile-kotlin", "", List.of(src), List.of(), RELEASE, DIGEST);

        // The forecast reproduces the digest the build wrote: fresh. A classpath entry whose ABI
        // moved (or an option that changed) yields another digest: stale, before any mtime is read.
        assertThat(FreshnessStamp.looksFresh(classes, BuildStamps.KOTLIN, List.of(src), DIGEST))
                .isTrue();
        assertThat(FreshnessStamp.looksFresh(classes, BuildStamps.KOTLIN, List.of(src), "options-b"))
                .isFalse();
        assertThat(FreshnessStamp.looksFresh(classes, BuildStamps.KOTLIN, List.of(src)))
                .as("the digest-free probe stays a source-mtime check")
                .isTrue();
    }

    @Test
    void unchanged_inputs_are_fresh(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path src = writeFile(tempDir.resolve("A.java"), "class A {}");
        Path jar = writeFile(tempDir.resolve("dep.jar"), "stub");

        FreshnessStamp.write(
                classes, BuildStamps.JAVA, "compile-main", "key123", List.of(src), List.of(jar), RELEASE, DIGEST);
        // Backdate the inputs by a second to make sure the mtime comparison
        // sees them as <= the stamp's millis (filesystem timestamp resolution
        // varies; same-millisecond can flake either way).
        Files.setLastModifiedTime(src, FileTime.fromMillis(System.currentTimeMillis() - 1000));
        Files.setLastModifiedTime(jar, FileTime.fromMillis(System.currentTimeMillis() - 1000));

        assertThat(FreshnessStamp.isFresh(classes, BuildStamps.JAVA, List.of(src), List.of(jar), RELEASE, DIGEST))
                .isTrue();
    }

    @Test
    void different_release_is_not_fresh(@TempDir Path tempDir) throws IOException {
        // A stamp written for a different --release (e.g. a JDK/toolchain switch) must
        // not be trusted even when every file mtime still looks unchanged.
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path src = writeFile(tempDir.resolve("A.java"), "class A {}");
        FreshnessStamp.write(classes, BuildStamps.JAVA, "compile-main", "key123", List.of(src), List.of(), 17, DIGEST);
        Files.setLastModifiedTime(src, FileTime.fromMillis(System.currentTimeMillis() - 1000));

        assertThat(FreshnessStamp.isFresh(classes, BuildStamps.JAVA, List.of(src), List.of(), 21, DIGEST))
                .isFalse();
    }

    @Test
    void source_touched_after_stamp_is_not_fresh(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path src = writeFile(tempDir.resolve("A.java"), "class A {}");
        FreshnessStamp.write(
                classes, BuildStamps.JAVA, "compile-main", "key123", List.of(src), List.of(), RELEASE, DIGEST);
        // Bump mtime forward; the stat will now exceed the stamp time.
        Files.setLastModifiedTime(src, FileTime.fromMillis(System.currentTimeMillis() + 5_000));

        assertThat(FreshnessStamp.isFresh(classes, BuildStamps.JAVA, List.of(src), List.of(), RELEASE, DIGEST))
                .isFalse();
    }

    @Test
    void jar_classpath_mtime_churn_does_not_bust_freshness(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path src = writeFile(tempDir.resolve("A.java"), "class A {}");
        Path jar = writeFile(tempDir.resolve("guava-33.4.8.jar"), "payload");

        FreshnessStamp.write(
                classes, BuildStamps.JAVA, "compile-main", "key123", List.of(src), List.of(jar), RELEASE, DIGEST);
        Files.setLastModifiedTime(src, FileTime.fromMillis(System.currentTimeMillis() - 1000));
        Files.setLastModifiedTime(jar, FileTime.fromMillis(System.currentTimeMillis() + 5_000));

        assertThat(FreshnessStamp.isFresh(classes, BuildStamps.JAVA, List.of(src), List.of(jar), RELEASE, DIGEST))
                .isTrue();
    }

    @Test
    void jar_content_change_busts_freshness(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path src = writeFile(tempDir.resolve("A.java"), "class A {}");
        Path localJar = writeFile(tempDir.resolve("dep.jar"), "stub");

        FreshnessStamp.write(
                classes, BuildStamps.JAVA, "compile-main", "key123", List.of(src), List.of(localJar), RELEASE, DIGEST);
        Files.setLastModifiedTime(src, FileTime.fromMillis(System.currentTimeMillis() - 1000));
        Files.writeString(localJar, "stub-MUTATED");

        assertThat(FreshnessStamp.isFresh(classes, BuildStamps.JAVA, List.of(src), List.of(localJar), RELEASE, DIGEST))
                .isFalse();
    }

    @Test
    void input_modified_in_the_same_millisecond_as_the_stamp_is_not_fresh(@TempDir Path tempDir) throws IOException {
        // Regression: a build that finishes writing its stamp in the same
        // millisecond a source is edited must NOT be treated as fresh — it has
        // to fall through to the content-hashing action cache. (This is the
        // race BuildCacheTest.editing_a_source_invalidates_cache hit on fast disks.)
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path src = writeFile(tempDir.resolve("A.java"), "class A {}");
        FreshnessStamp.write(
                classes, BuildStamps.JAVA, "compile-main", "key123", List.of(src), List.of(), RELEASE, DIGEST);

        long stampMillis =
                FreshnessStamp.read(classes, BuildStamps.JAVA).orElseThrow().stampMillis();
        Files.setLastModifiedTime(src, FileTime.fromMillis(stampMillis));

        assertThat(FreshnessStamp.isFresh(classes, BuildStamps.JAVA, List.of(src), List.of(), RELEASE, DIGEST))
                .isFalse();
    }

    @Test
    void added_source_invalidates_stamp(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path a = writeFile(tempDir.resolve("A.java"), "class A {}");
        FreshnessStamp.write(
                classes, BuildStamps.JAVA, "compile-main", "key123", List.of(a), List.of(), RELEASE, DIGEST);

        Path b = writeFile(tempDir.resolve("B.java"), "class B {}");
        Files.setLastModifiedTime(a, FileTime.fromMillis(System.currentTimeMillis() - 1000));
        Files.setLastModifiedTime(b, FileTime.fromMillis(System.currentTimeMillis() - 1000));

        // Source set composition changed → not fresh, even though both files
        // are older than the stamp.
        assertThat(FreshnessStamp.isFresh(classes, BuildStamps.JAVA, List.of(a, b), List.of(), RELEASE, DIGEST))
                .isFalse();
    }

    @Test
    void removed_source_invalidates_stamp(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path a = writeFile(tempDir.resolve("A.java"), "class A {}");
        Path b = writeFile(tempDir.resolve("B.java"), "class B {}");
        FreshnessStamp.write(
                classes, BuildStamps.JAVA, "compile-main", "key123", List.of(a, b), List.of(), RELEASE, DIGEST);

        // Caller passes a smaller source list — stamp said it covered two.
        Files.setLastModifiedTime(a, FileTime.fromMillis(System.currentTimeMillis() - 1000));
        assertThat(FreshnessStamp.isFresh(classes, BuildStamps.JAVA, List.of(a), List.of(), RELEASE, DIGEST))
                .isFalse();
    }

    @Test
    void missing_input_file_invalidates_stamp(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path src = writeFile(tempDir.resolve("A.java"), "class A {}");
        FreshnessStamp.write(
                classes, BuildStamps.JAVA, "compile-main", "key123", List.of(src), List.of(), RELEASE, DIGEST);

        Files.delete(src);
        assertThat(FreshnessStamp.isFresh(classes, BuildStamps.JAVA, List.of(src), List.of(), RELEASE, DIGEST))
                .isFalse();
    }

    @Test
    void wiping_output_dir_invalidates_stamp(@TempDir Path tempDir) throws IOException {
        // The stamp lives inside the output dir, so removing the dir takes
        // the stamp with it — next build sees no stamp and falls through.
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path src = writeFile(tempDir.resolve("A.java"), "class A {}");
        FreshnessStamp.write(
                classes, BuildStamps.JAVA, "compile-main", "key123", List.of(src), List.of(), RELEASE, DIGEST);

        Files.delete(classes.resolve(BuildStamps.JAVA));
        Files.delete(classes);

        assertThat(FreshnessStamp.isFresh(classes, BuildStamps.JAVA, List.of(src), List.of(), RELEASE, DIGEST))
                .isFalse();
    }

    @Test
    void classpath_touched_after_stamp_is_not_fresh(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path src = writeFile(tempDir.resolve("A.java"), "class A {}");
        Path jar = writeFile(tempDir.resolve("dep.jar"), "stub");
        FreshnessStamp.write(
                classes, BuildStamps.JAVA, "compile-main", "key123", List.of(src), List.of(jar), RELEASE, DIGEST);

        Files.setLastModifiedTime(src, FileTime.fromMillis(System.currentTimeMillis() - 1000));
        Files.writeString(jar, "stub-rebuilt");

        assertThat(FreshnessStamp.isFresh(classes, BuildStamps.JAVA, List.of(src), List.of(jar), RELEASE, DIGEST))
                .isFalse();
    }

    @Test
    void different_release_invalidates_stamp(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path src = writeFile(tempDir.resolve("A.java"), "class A {}");
        FreshnessStamp.write(
                classes, BuildStamps.JAVA, "compile-main", "key123", List.of(src), List.of(), RELEASE, DIGEST);

        assertThat(FreshnessStamp.isFresh(classes, BuildStamps.JAVA, List.of(src), List.of(), 17, DIGEST))
                .isFalse();
    }

    @Test
    void changed_options_digest_is_not_fresh(@TempDir Path tempDir) throws IOException {
        // Compiler options, plugins and the JDK move no file mtime; the digest is the only
        // input that records them, so a stamp written under other options is stale.
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path src = writeFile(tempDir.resolve("A.java"), "class A {}");
        FreshnessStamp.write(
                classes, BuildStamps.JAVA, "compile-main", "key123", List.of(src), List.of(), RELEASE, DIGEST);
        Files.setLastModifiedTime(src, FileTime.fromMillis(System.currentTimeMillis() - 1000));

        assertThat(FreshnessStamp.isFresh(classes, BuildStamps.JAVA, List.of(src), List.of(), RELEASE, DIGEST))
                .isTrue();
        assertThat(FreshnessStamp.isFresh(classes, BuildStamps.JAVA, List.of(src), List.of(), RELEASE, "options-b"))
                .isFalse();
        assertThat(FreshnessStamp.read(classes, BuildStamps.JAVA).orElseThrow().optionsDigest())
                .isEqualTo(DIGEST);
    }

    @Test
    void a_stamp_without_a_digest_matches_only_a_producer_without_options(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path src = writeFile(tempDir.resolve("A.java"), "class A {}");
        FreshnessStamp.write(classes, BuildStamps.JAVA, "compile-main", "key123", List.of(src), List.of(), RELEASE, "");
        Files.setLastModifiedTime(src, FileTime.fromMillis(System.currentTimeMillis() - 1000));

        assertThat(FreshnessStamp.isFresh(classes, BuildStamps.JAVA, List.of(src), List.of(), RELEASE, ""))
                .isTrue();
        assertThat(FreshnessStamp.isFresh(classes, BuildStamps.JAVA, List.of(src), List.of(), RELEASE, DIGEST))
                .isFalse();
    }

    @Test
    void options_digest_is_order_sensitive_and_part_boundaries_are_kept() {
        String ab = FreshnessStamp.optionsDigest(List.of("arg:--add-modules", "arg:a"));
        assertThat(FreshnessStamp.optionsDigest(List.of("arg:--add-modules", "arg:a")))
                .isEqualTo(ab);
        assertThat(FreshnessStamp.optionsDigest(List.of("arg:a", "arg:--add-modules")))
                .isNotEqualTo(ab);
        assertThat(FreshnessStamp.optionsDigest(List.of("arg:--add-modulesarg:a")))
                .isNotEqualTo(ab);
    }

    /**
     * A stamp records the producer's read clock, not the write: a source edited between the two
     * (while the compiler ran) is stale on the next check, and one edited before the read clock
     * is what the compiler read and stays fresh.
     */
    @Test
    void source_edited_after_the_read_clock_is_not_fresh(@TempDir Path tempDir) throws IOException {
        Path classes = Files.createDirectories(tempDir.resolve("classes"));
        Path settled = writeFile(tempDir.resolve("A.java"), "class A {}");
        Path edited = writeFile(tempDir.resolve("B.java"), "class B {}");
        long readClock = FreshnessStamp.clockNow(classes);
        Files.setLastModifiedTime(settled, FileTime.fromMillis(readClock - 60_000));
        // Landed while the compile ran: at the read clock, before the stamp is written.
        Files.setLastModifiedTime(edited, FileTime.fromMillis(readClock));
        List<Path> sources = List.of(settled, edited);
        FreshnessStamp.write(
                classes, BuildStamps.JAVA, "compile-main", "key123", sources, List.of(), RELEASE, DIGEST, readClock);

        assertThat(FreshnessStamp.isFresh(classes, BuildStamps.JAVA, sources, List.of(), RELEASE, DIGEST))
                .isFalse();
        assertThat(FreshnessStamp.isFresh(classes, BuildStamps.JAVA, List.of(settled), List.of(), RELEASE, DIGEST))
                .as("a different source set never matches")
                .isFalse();

        FreshnessStamp.write(
                classes,
                BuildStamps.JAVA,
                "compile-main",
                "key123",
                List.of(settled),
                List.of(),
                RELEASE,
                DIGEST,
                readClock);
        assertThat(FreshnessStamp.isFresh(classes, BuildStamps.JAVA, List.of(settled), List.of(), RELEASE, DIGEST))
                .isTrue();
    }

    private static Path writeFile(Path file, String body) throws IOException {
        Files.writeString(file, body);
        return file;
    }
}
