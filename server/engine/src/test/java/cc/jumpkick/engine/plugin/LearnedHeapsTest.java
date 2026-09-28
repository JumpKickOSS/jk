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
    void the_size_is_peak_times_1_3_rounded_up_floored_and_capped() {
        long step = LearnedHeaps.STEP_BYTES;
        // 100 MiB × 1.3 = 130 MiB, which rounds up to 192 MiB and then meets the 128 MiB floor.
        assertThat(LearnedHeaps.size(100L << 20, 0)).isEqualTo(192L << 20);
        // 10 MiB × 1.3 rounds to 64 MiB, then the floor lifts it to 128 MiB.
        assertThat(LearnedHeaps.size(10L << 20, 0)).isEqualTo(LearnedHeaps.FLOOR_BYTES);
        // 400 MiB × 1.3 = 520 MiB, rounded up to 576 MiB.
        assertThat(LearnedHeaps.size(400L << 20, 0)).isEqualTo(9 * step);
        long budget = WorkerLeases.jvmLease(128L << 20);
        assertThat(LearnedHeaps.size(400L << 20, budget)).isLessThanOrEqualTo(128L << 20);
    }

    @Test
    void the_last_five_peaks_size_from_their_maximum_and_an_old_spike_ages_out(@TempDir Path dir) throws Exception {
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
                .contains("g:app\ttest\t25\t" + (50L << 20) + "," + (200L << 20) + "," + (100L << 20));
        long sized = heaps.choose(project, "g:app", HeapScope.TEST, 25, 512L << 20);
        assertThat(sized)
                .isEqualTo(LearnedHeaps.size(200L << 20, WorkerLeases.engine().capacityBytes()));
        assertThat(heaps.choose(project, "g:other", HeapScope.TEST, 25, 512L << 20))
                .isEqualTo(512L << 20);

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
