// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The sizing rule and the per-project record. */
class LearnedHeapsTest {

    @Test
    void the_size_is_the_peak_with_headroom_rounded_up_floored_and_capped() {
        long step = LearnedHeaps.STEP_BYTES;
        // 10 MiB × 1.3 rounds to 64 MiB, then the floor lifts it to 256 MiB.
        assertThat(LearnedHeaps.size(10L << 20, 0, false)).isEqualTo(LearnedHeaps.FLOOR_BYTES);
        assertThat(LearnedHeaps.FLOOR_BYTES).isEqualTo(256L << 20);
        // 400 MiB × 1.3 = 520 MiB, rounded up to 576 MiB.
        assertThat(LearnedHeaps.size(400L << 20, 0, false)).isEqualTo(9 * step);
        // On CI: 400 MiB × 1.5 = 600 MiB, rounded up to 640 MiB.
        assertThat(LearnedHeaps.size(400L << 20, 0, true)).isEqualTo(10 * step);
        long budget = WorkerLeases.jvmLease(128L << 20);
        assertThat(LearnedHeaps.size(400L << 20, budget, false)).isLessThanOrEqualTo(128L << 20);
    }

    @Test
    void an_unseen_worker_starts_at_a_quarter_of_the_budget_up_to_2_gib() {
        long gib = 1L << 30;
        assertThat(LearnedHeaps.firstHeap(128L << 20, 4 * gib)).isEqualTo(gib);
        assertThat(LearnedHeaps.firstHeap(128L << 20, 64 * gib)).isEqualTo(LearnedHeaps.FIRST_CAP_BYTES);
        assertThat(LearnedHeaps.firstHeap(3 * gib, 64 * gib))
                .as("never below the plan")
                .isEqualTo(3 * gib);
        long small = WorkerLeases.jvmLease(512L << 20);
        assertThat(LearnedHeaps.firstHeap(1024L << 20, small))
                .as("never more than the budget can lease")
                .isLessThanOrEqualTo(512L << 20);
        assertThat(LearnedHeaps.firstHeap(128L << 20, 0)).isEqualTo(128L << 20);
    }

    @Test
    void only_retried_kinds_start_generously(@TempDir Path dir) {
        LearnedHeaps heaps = new LearnedHeaps(dir);
        long budget = WorkerLeases.engine().capacityBytes();
        assertThat(heaps.choose(dir, "g:app", HeapScope.TEST, 25, 128L << 20))
                .isEqualTo(LearnedHeaps.firstHeap(128L << 20, budget));
        assertThat(heaps.choose(dir, "g:app", HeapScope.KOTLIN_COMPILE, 25, 128L << 20))
                .isEqualTo(LearnedHeaps.firstHeap(128L << 20, budget));
        assertThat(heaps.choose(dir, "g:app", HeapScope.PLUGIN, 25, 128L << 20)).isEqualTo(128L << 20);
        assertThat(heaps.choose(dir, "g:app", HeapScope.KTS_HOST, 25, 128L << 20))
                .isEqualTo(128L << 20);
    }

    @Test
    void a_heap_that_succeeded_after_an_exhaustion_is_the_floor_from_then_on(@TempDir Path dir) throws Exception {
        LearnedHeaps heaps = new LearnedHeaps(dir);
        Path project = dir.resolve("app");
        HeapScope.Key key = new HeapScope.Key(project, "g:app", HeapScope.TEST, 25);
        heaps.note(key, 256L << 20);
        heaps.good(key, 1024L << 20);
        heaps.note(key, 300L << 20);
        heaps.good(key, 512L << 20);
        assertThat(heaps.good(key)).isEqualTo(1024L << 20);
        long budget = WorkerLeases.engine().capacityBytes();
        assertThat(heaps.choose(project, "g:app", HeapScope.TEST, 25, 128L << 20))
                .isEqualTo(Math.max(LearnedHeaps.size(300L << 20, budget), WorkerLeases.clampXmx(1024L << 20, budget)));
        assertThat(Files.readString(heaps.file(project)))
                .contains("g:app\ttest\t25\t" + (300L << 20) + "," + (256L << 20) + "\t" + (1024L << 20));
    }

    @Test
    void the_last_ten_peaks_size_from_their_maximum_and_an_old_spike_ages_out(@TempDir Path dir) throws Exception {
        LearnedHeaps heaps = new LearnedHeaps(dir);
        Path project = dir.resolve("app");
        heaps.note(project, "g:app", HeapScope.TEST, 25, 100L << 20);
        heaps.note(project, "g:app", HeapScope.TEST, 25, 200L << 20);
        heaps.note(project, "g:app", HeapScope.JAVA_COMPILE, 25, 64L << 20);
        assertThat(heaps.peak(project, "g:app", HeapScope.TEST, 25)).isEqualTo(200L << 20);
        heaps.note(project, "g:app", HeapScope.TEST, 25, 50L << 20);
        assertThat(heaps.peak(project, "g:app", HeapScope.TEST, 25)).isEqualTo(200L << 20);
        assertThat(heaps.peak(project, "g:app", HeapScope.JAVA_COMPILE, 25)).isEqualTo(64L << 20);
        assertThat(heaps.peak(project, "g:app", HeapScope.TEST, 21)).isZero();
        assertThat(heaps.file(project)).isRegularFile();
        assertThat(Files.readString(heaps.file(project)))
                .contains("g:app\ttest\t25\t" + (50L << 20) + "," + (200L << 20) + "," + (100L << 20) + "\t0");
        long sized = heaps.choose(project, "g:app", HeapScope.TEST, 25, 512L << 20);
        assertThat(sized)
                .isEqualTo(LearnedHeaps.size(200L << 20, WorkerLeases.engine().capacityBytes()));
        assertThat(heaps.choose(project, "g:other", HeapScope.TEST, 25, 512L << 20))
                .isEqualTo(
                        LearnedHeaps.firstHeap(512L << 20, WorkerLeases.engine().capacityBytes()));

        for (int i = 1; i <= LearnedHeaps.WINDOW; i++) {
            heaps.note(project, "g:app", HeapScope.TEST, 25, i * (1L << 20));
        }
        assertThat(heaps.peak(project, "g:app", HeapScope.TEST, 25)).isEqualTo(LearnedHeaps.WINDOW * (1L << 20));
        assertThat(heaps.peak(project, "g:app", HeapScope.JAVA_COMPILE, 25)).isEqualTo(64L << 20);
    }

    @Test
    void a_sizing_override_replaces_the_estimate(@TempDir Path dir) {
        LearnedHeaps heaps = new LearnedHeaps(dir, 128L << 20);
        assertThat(heaps.choose(dir, "g:app", HeapScope.TEST, 25, 512L << 20)).isEqualTo(128L << 20);
    }

    /**
     * Every worker fork asks the store; a file nobody changed is parsed once, and one another
     * engine rewrote is parsed again.
     */
    @Test
    void an_unchanged_file_is_parsed_once(@TempDir Path dir) throws Exception {
        LearnedHeaps heaps = new LearnedHeaps(dir);
        Path project = dir.resolve("app");
        HeapScope.Key key = new HeapScope.Key(project, "g:app", HeapScope.TEST, 25);
        heaps.note(key, 256L << 20);
        int afterWrite = heaps.parses();
        for (int i = 0; i < 20; i++) {
            assertThat(heaps.peak(key)).isEqualTo(256L << 20);
            heaps.choose(project, "g:app", HeapScope.TEST, 25, 128L << 20);
        }
        assertThat(heaps.parses()).isEqualTo(afterWrite);

        Path file = heaps.file(project);
        Files.writeString(file, Files.readString(file).replace(String.valueOf(256L << 20), String.valueOf(512L << 20)));
        Files.setLastModifiedTime(
                file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5_000));
        assertThat(heaps.peak(key)).as("another engine's write is read").isEqualTo(512L << 20);
        assertThat(heaps.parses()).isEqualTo(afterWrite + 1);
    }

    @Test
    void a_worker_is_priced_at_its_resident_peak_else_its_kinds_on_this_host_else_a_prior(@TempDir Path dir)
            throws Exception {
        LearnedHeaps heaps = new LearnedHeaps(dir);
        long mib = 1L << 20;
        HeapScope.Key a = new HeapScope.Key(dir.resolve("a"), "g:a", HeapScope.TEST, 25);
        HeapScope.Key b = new HeapScope.Key(dir.resolve("b"), "g:b", HeapScope.TEST, 25);
        HeapScope.Key image = new HeapScope.Key(dir.resolve("a"), "g:a", HeapScope.NATIVE_IMAGE, 25);

        assertThat(heaps.price(a)).as("nothing seen: the prior x 1.25").isEqualTo(640 * mib);
        assertThat(heaps.price(image)).as("native prior x 1.3").isEqualTo(4032 * mib);

        heaps.noteResident(a, 300 * mib);
        heaps.noteResident(a, 200 * mib);
        // 300 MiB x 1.25 = 375, rounded up to 384.
        assertThat(heaps.price(a)).as("its own largest peak").isEqualTo(384 * mib);
        assertThat(heaps.price(b))
                .as("another module: the kind's host-wide peak")
                .isEqualTo(384 * mib);
        heaps.noteResident(a, 10 * mib);
        assertThat(heaps.price(a)).isEqualTo(384 * mib);

        HeapScope.Key small = new HeapScope.Key(dir.resolve("s"), "g:s", HeapScope.TEST, 25);
        heaps.noteResident(small, 50 * mib);
        assertThat(heaps.price(small)).as("floor").isEqualTo(160 * mib);

        heaps.noteResident(image, 1800 * mib);
        assertThat(heaps.price(image)).as("native: 1.3x, floor 2 GiB").isEqualTo(2368 * mib);
        heaps.noteResident(new HeapScope.Key(dir.resolve("c"), "g:c", HeapScope.NATIVE_IMAGE, 25), 100 * mib);
        assertThat(heaps.price(new HeapScope.Key(dir.resolve("c"), "g:c", HeapScope.NATIVE_IMAGE, 25)))
                .isEqualTo(LearnedHeaps.NATIVE_PRICE_FLOOR_BYTES);

        assertThat(Files.readString(heaps.file(dir.resolve("a"))))
                .contains("g:a	test	25		0		" + (10 * mib) + "," + (200 * mib) + "," + (300 * mib));
        assertThat(Files.readString(dir.resolve(LearnedHeaps.HOST_FILE))).contains("*	test	0	");
    }
}
