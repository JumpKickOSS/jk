// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.guard.extract.FactsIndexing.Ensured;
import cc.jumpkick.guard.extract.fixture.FixtureBytes;
import cc.jumpkick.guard.extract.fixture.Sample;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class FactsIndexingTest {

    private static Path classes(Path dir) throws IOException {
        Path pkg = dir.resolve("classes/cc/jumpkick/guard/extract/fixture");
        Files.createDirectories(pkg);
        Files.write(pkg.resolve("Sample.class"), FixtureBytes.of(Sample.class));
        Files.write(pkg.resolve("Sample$Inner.class"), FixtureBytes.of(Sample.Inner.class));
        return dir.resolve("classes");
    }

    @Test
    void cold_then_fresh_then_incremental(@TempDir Path dir) throws IOException {
        Path classes = classes(dir);
        Path idx = FactsIndexing.indexPath(dir.resolve("target"), "main");

        Ensured cold = FactsIndexing.ensure(classes, idx);
        assertThat(cold.tier()).isEqualTo(Ensured.Tier.COLD);
        assertThat(cold.classes()).isEqualTo(2);
        assertThat(cold.reextracted()).isEqualTo(2);
        assertThat(Files.isRegularFile(idx)).isTrue();

        Ensured fresh = FactsIndexing.ensure(classes, idx);
        assertThat(fresh.tier()).isEqualTo(Ensured.Tier.FRESH);
        assertThat(fresh.bodyDigest()).isEqualTo(cold.bodyDigest());
        assertThat(fresh.reextracted()).isZero();

        // Touch one class: only it is re-read; the digest is unchanged because the bytes are.
        Path inner = classes.resolve("cc/jumpkick/guard/extract/fixture/Sample$Inner.class");
        Files.setLastModifiedTime(
                inner, FileTime.fromMillis(Files.getLastModifiedTime(inner).toMillis() + 5_000));
        Ensured touched = FactsIndexing.ensure(classes, idx);
        assertThat(touched.tier()).isEqualTo(Ensured.Tier.INCREMENTAL);
        assertThat(touched.reextracted()).isEqualTo(1);
        assertThat(touched.bodyDigest()).isEqualTo(cold.bodyDigest());

        // Remove one class: the table shrinks and the digest moves.
        Files.delete(inner);
        Ensured removed = FactsIndexing.ensure(classes, idx);
        assertThat(removed.classes()).isEqualTo(1);
        assertThat(removed.reextracted()).isZero();
        assertThat(removed.bodyDigest()).isNotEqualTo(cold.bodyDigest());

        FactsIndex loaded = FactsIndexing.load(removed);
        assertThat(loaded.classes()).containsOnlyKeys("cc/jumpkick/guard/extract/fixture/Sample");
    }

    /**
     * The module lane and the workspace lane index the same classes directory from one engine at
     * the same time. One of them writes; the other waits and reads what was written. Several
     * rounds, because the collision needs the two writers inside the same few microseconds.
     */
    @Test
    void concurrent_callers_share_one_writer_and_one_index(@TempDir Path dir) throws Exception {
        Path classes = classes(dir);
        // More class files widen the window in which two writers overlap.
        byte[] sample = FixtureBytes.of(Sample.class);
        for (int i = 0; i < 40; i++) {
            Path pkg = classes.resolve("copy" + i);
            Files.createDirectories(pkg);
            Files.write(pkg.resolve("Sample.class"), sample);
        }
        Path idx = FactsIndexing.indexPath(dir.resolve("target"), "main");
        int callers = 4;
        try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
            for (int round = 0; round < 25; round++) {
                Files.deleteIfExists(idx);
                CyclicBarrier start = new CyclicBarrier(callers);
                List<Future<Ensured>> futures = new ArrayList<>();
                for (int i = 0; i < callers; i++) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        return FactsIndexing.ensure(classes, idx);
                    }));
                }
                List<Ensured> results = new ArrayList<>();
                for (Future<Ensured> f : futures) results.add(f.get());

                Set<String> digests = results.stream().map(Ensured::bodyDigest).collect(Collectors.toSet());
                assertThat(digests)
                        .as("round " + round + ": every caller sees the same index")
                        .hasSize(1);
                List<Ensured.Tier> tiers = results.stream().map(Ensured::tier).toList();
                assertThat(tiers)
                        .as("round " + round + ": one writer, the rest read its index")
                        .containsOnlyOnce(Ensured.Tier.COLD)
                        .containsOnly(Ensured.Tier.COLD, Ensured.Tier.FRESH);
                Set<String> left = new TreeSet<>();
                PathUtil.forEachChild(Objects.requireNonNull(idx.getParent()), (p, attrs) -> {
                    left.add(p.getFileName().toString());
                    return true;
                });
                assertThat(left)
                        .as("round " + round + ": no staging file survives")
                        .containsExactly(idx.getFileName().toString());
                assertThat(FactsIndexing.load(results.get(0)).stamps()).hasSize(42);
            }
        }
    }

    @Test
    void no_classes_directory_is_absent_not_an_error(@TempDir Path dir) throws IOException {
        Ensured e = FactsIndexing.ensure(dir.resolve("nope"), dir.resolve("g.idx"));
        assertThat(e.tier()).isEqualTo(Ensured.Tier.ABSENT);
        assertThat(FactsIndexing.load(e).classes()).isEmpty();
        assertThat(Files.exists(dir.resolve("g.idx"))).isFalse();
    }

    /**
     * A read failure that is not a vanished file names its exception type, so the message carries
     * more than the path. The unreadable file here is the portable stand-in for any such failure.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void an_unreadable_class_file_names_the_failure(@TempDir Path dir) throws IOException {
        Path classes = classes(dir);
        Path locked = classes.resolve("cc/jumpkick/guard/extract/fixture/Sample.class");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        assumeTrue(!Files.isReadable(locked), "not running as root");
        try {
            Path idx = FactsIndexing.indexPath(dir.resolve("target"), "main");
            assertThatThrownBy(() -> FactsIndexing.ensure(classes, idx))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("AccessDeniedException")
                    .hasMessageContaining("Sample.class")
                    .hasCauseInstanceOf(AccessDeniedException.class);
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rw-r--r--"));
        }
    }

    @Test
    void a_denial_on_windows_reads_as_a_vanished_file_and_on_posix_as_a_permission() {
        Path classes = Path.of("classes");
        var denied = new AccessDeniedException("Sample.class");

        assertThat(FactsIndexing.readFailure("Sample.class", classes, denied, true))
                .as("Windows: delete-pending keeps the name listed and refuses the reopen")
                .hasMessageContaining("vanished")
                .hasMessageContaining("missing from the step's requires");

        assertThat(FactsIndexing.readFailure("Sample.class", classes, denied, false))
                .as("POSIX: a denial is a permission fault, not a concurrent write")
                .hasMessageContaining("AccessDeniedException")
                .hasMessageNotContaining("vanished");
    }

    @Test
    void a_half_written_class_file_is_an_io_failure_naming_it(@TempDir Path dir) throws IOException {
        Path classes = classes(dir);
        Path idx = FactsIndexing.indexPath(dir.resolve("target"), "main");
        // A compile that has created the file and not yet written it: what a lane that reads
        // another module's classes sees mid-build.
        Files.write(classes.resolve("cc/jumpkick/guard/extract/fixture/Late.class"), new byte[0]);

        assertThatThrownBy(() -> FactsIndexing.ensure(classes, idx))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Late.class")
                .hasMessageContaining("0 bytes")
                .hasMessageContaining("still writing it");
        assertThat(Files.exists(idx))
                .as("nothing is written for a tree that did not read")
                .isFalse();
    }

    @Test
    void a_missing_class_file_reads_as_vanished_on_either_platform() {
        Path classes = Path.of("classes");
        var missing = new NoSuchFileException("Sample.class");
        for (boolean onWindows : new boolean[] {true, false}) {
            assertThat(FactsIndexing.readFailure("Sample.class", classes, missing, onWindows))
                    .hasMessageContaining("vanished")
                    .hasCause(missing);
        }
    }
}
