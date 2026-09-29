// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
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
}
